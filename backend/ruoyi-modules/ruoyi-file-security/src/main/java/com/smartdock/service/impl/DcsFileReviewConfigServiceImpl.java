package com.smartdock.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.DcsFileReviewConfig;
import com.smartdock.domain.bo.DcsFileReviewConfigBo;
import com.smartdock.domain.vo.DcsFileReviewConfigVo;
import com.smartdock.enums.FileReviewCategory;
import com.smartdock.fileguard.DcsFileGuardCheckerImpl;
import com.smartdock.mapper.DcsFileReviewConfigMapper;
import com.smartdock.mapper.DcsFileReviewMapper;
import com.smartdock.service.IDcsFileReviewConfigService;
import org.dromara.system.domain.SysRole;
import org.dromara.system.domain.SysUserRole;
import org.dromara.system.mapper.SysRoleMapper;
import org.dromara.system.mapper.SysUserRoleMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 租户级文件审核配置服务实现（12b）
 *
 * <p>读取路径是上传钩子/读拦截的热路径：selectOne 走 UNIQUE(tenant_id) 单行查，
 * 查无行即返回关闭态常量，不缓存、不落库、不做隐式初始化（合同#14 短路序）。</p>
 *
 * @author smartdock
 */
@RequiredArgsConstructor
@Service
public class DcsFileReviewConfigServiceImpl implements IDcsFileReviewConfigService {

    private final DcsFileReviewConfigMapper baseMapper;
    private final DcsFileReviewMapper reviewMapper;
    /** 审核员计数用（file_reviewer 角色下的用户数） */
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;

    /** 功能整体关闭的常量配置（查无行兜底，禁止返回 null） */
    private static final DcsFileReviewConfig DISABLED = buildDisabled();

    /** 审核员角色 key（migration_2026_09_17 种子） */
    private static final String REVIEWER_ROLE_KEY = "file_reviewer";

    private static DcsFileReviewConfig buildDisabled() {
        DcsFileReviewConfig cfg = new DcsFileReviewConfig();
        cfg.setEnabled("0");
        cfg.setCategories("");
        return cfg;
    }

    @Override
    public DcsFileReviewConfig getEffective() {
        // 显式租户条件(批2 2026-09-25, glm53 模块审查 P2-3): 不能纯靠租户拦截器——
        // 无租户上下文线程(定时/@Async)拦截器对全表放行, 多租户各存一行时 selectOne 会抛
        // "query result is multiple records"; 无租户直接按禁用处理
        String tenantId = TenantHelper.getTenantId();
        if (StrUtil.isBlank(tenantId)) {
            return DISABLED;
        }
        DcsFileReviewConfig cfg = baseMapper.selectOne(Wrappers.lambdaQuery(DcsFileReviewConfig.class)
            .eq(DcsFileReviewConfig::getTenantId, tenantId));
        return cfg != null ? cfg : DISABLED;
    }

    @Override
    public DcsFileReviewConfigVo getConfig() {
        DcsFileReviewConfig cfg = getEffective();
        DcsFileReviewConfigVo vo = new DcsFileReviewConfigVo();
        vo.setEnabled(cfg.getEnabled());
        vo.setCategories(cfg.getCategories());
        vo.setPendingCount(reviewMapper.countPending(TenantHelper.getTenantId()));
        vo.setReviewerCount(countReviewers());
        // file-guard 展示口径（批三）：空值兜底 OBSERVE/50MB，与读侧（checker）解析口径一致
        vo.setGuardMode(StrUtil.blankToDefault(cfg.getGuardMode(), DcsFileGuardCheckerImpl.MODE_OBSERVE));
        vo.setGuardWhitelist(cfg.getGuardWhitelist());
        vo.setGuardDeepScanMaxMb(cfg.getGuardDeepScanMaxMb() == null
            ? DcsFileGuardCheckerImpl.DEFAULT_DEEP_SCAN_MAX_MB : cfg.getGuardDeepScanMaxMb());
        return vo;
    }

    @Override
    public void saveConfig(DcsFileReviewConfigBo bo) {
        // 类目归一：trim + 大写 + 枚举白名单校验（审查修复：前端只挡 UI，
        // 直调 API 存进拼错/空格类目会"开关开着却零审核"静默空转，必须服务端兜底）
        Set<String> validNames = java.util.Arrays.stream(FileReviewCategory.values())
            .map(Enum::name).collect(Collectors.toSet());
        List<String> normalized = new ArrayList<>();
        if (bo.getCategories() != null) {
            for (String raw : bo.getCategories()) {
                String name = StrUtil.trimToEmpty(raw).toUpperCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                if (!validNames.contains(name)) {
                    throw new ServiceException("未知文件类目：" + raw);
                }
                if (!normalized.contains(name)) {
                    normalized.add(name);
                }
            }
        }
        if (Boolean.TRUE.equals(bo.getEnabled()) && normalized.isEmpty()) {
            throw new ServiceException("开启审核至少须勾选一种文件类型");
        }
        String categories = String.join(",", normalized);

        // file-guard 归一（批三，Q5=B 界面化管理）：模式三档白名单校验（常量与检测器共用防漂移）；
        // 白名单 token trim/去前导点/小写/去重；深扫上限 1-500（上传上限 500MB，再大无意义）
        String guardMode = StrUtil.isBlank(bo.getGuardMode()) ? DcsFileGuardCheckerImpl.MODE_OBSERVE
            : bo.getGuardMode().trim().toUpperCase(Locale.ROOT);
        if (!Set.of(DcsFileGuardCheckerImpl.MODE_OFF, DcsFileGuardCheckerImpl.MODE_OBSERVE,
            DcsFileGuardCheckerImpl.MODE_ENFORCE).contains(guardMode)) {
            throw new ServiceException("未知检测模式：" + bo.getGuardMode() + "（仅支持 OFF/OBSERVE/ENFORCE）");
        }
        String guardWhitelist = normalizeGuardWhitelist(bo.getGuardWhitelist());
        Integer guardMaxMb = bo.getGuardDeepScanMaxMb();
        if (guardMaxMb == null) {
            guardMaxMb = DcsFileGuardCheckerImpl.DEFAULT_DEEP_SCAN_MAX_MB;
        } else if (guardMaxMb < 1 || guardMaxMb > 500) {
            throw new ServiceException("深扫大小上限须为 1-500 MB");
        }
        // configId 每次新生成雪花 ID：首存作主键，冲突转 UPDATE 时被忽略（PK 非冲突判定列）
        baseMapper.upsertConfig(IdWorker.getId(), TenantHelper.getTenantId(),
            Boolean.TRUE.equals(bo.getEnabled()) ? "1" : "0", categories, LoginHelper.getUserId(),
            guardMode, guardWhitelist, guardMaxMb);
    }

    /** 白名单归一：逗号串 → trim/去前导点/小写/去重；空串=清空（读侧回退七类并集默认） */
    private String normalizeGuardWhitelist(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        Set<String> tokens = new java.util.LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith(".")) {
                t = t.substring(1);
            }
            if (t.isEmpty()) {
                continue;
            }
            if (!t.matches("[a-z0-9]{1,10}")) {
                throw new ServiceException("白名单扩展名格式不合法：" + token);
            }
            tokens.add(t);
        }
        return String.join(",", tokens);
    }

    /**
     * 审核员数：file_reviewer 角色下的用户数（MP 查询租户拦截器自动隔离角色表；
     * 走系统 Mapper 避免 dcs 侧裸 SQL 撞租户拦截器 JSqlParser 改写坑）
     */
    private long countReviewers() {
        List<SysRole> roles = sysRoleMapper.selectList(Wrappers.lambdaQuery(SysRole.class)
            .eq(SysRole::getRoleKey, REVIEWER_ROLE_KEY));
        if (CollUtil.isEmpty(roles)) {
            return 0;
        }
        return sysUserRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
            .in(SysUserRole::getRoleId, roles.stream().map(SysRole::getRoleId).toList()));
    }
}
