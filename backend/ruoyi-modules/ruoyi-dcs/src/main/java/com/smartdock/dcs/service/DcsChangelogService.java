package com.smartdock.dcs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.smartdock.dcs.domain.ChangelogItem;
import com.smartdock.dcs.domain.DcsChangelog;
import com.smartdock.dcs.domain.bo.DcsChangelogBo;
import com.smartdock.dcs.mapper.DcsChangelogMapper;
import com.smartdock.dcs.mapper.DcsUserLookupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 更新日志服务（N3 2026-10-23）。
 *
 * <p>超管可写；其他角色只读「已发布 + 推送名单含本租户」的版本。
 * 发布时通知目标租户的管理员，已发布版本再编辑只补发新增租户，不重复打扰。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DcsChangelogService {

    private static final String NOTICE_TYPE = "CHANGELOG_PUBLISH";
    private static final String URL = "/support/changelog";
    private static final int MAX_ITEMS = 50;

    private final DcsChangelogMapper changelogMapper;
    private final DcsUserLookupMapper userLookupMapper;
    private final DcsNoticeService noticeService;

    /** 列表：超管看全部；其他角色只看已发布且名单含本租户。 */
    public TableDataInfo<DcsChangelog> list(DcsChangelogBo bo, PageQuery pq) {
        boolean superAdmin = LoginHelper.isSuperAdmin();
        QueryWrapper<DcsChangelog> w = new QueryWrapper<>();
        w.eq("del_flag", "0");
        if (!superAdmin) {
            w.eq("status", "1");
            w.like("target_tenants", "\"" + LoginHelper.getTenantId() + "\"");
        } else if (bo.getStatus() != null && !bo.getStatus().isBlank()) {
            w.eq("status", bo.getStatus());
        }
        w.like(bo.getVersion() != null && !bo.getVersion().isBlank(), "version", bo.getVersion());
        w.like(bo.getTitle() != null && !bo.getTitle().isBlank(), "title", bo.getTitle());
        w.last("ORDER BY string_to_array(ltrim(version, 'v'), '.')::int[] DESC, create_time DESC");
        Page<DcsChangelog> page = changelogMapper.selectPage(pq.build(), w);
        page.getRecords().forEach(this::parse);
        return TableDataInfo.build(page);
    }

    /** 详情：非超管必须已发布且名单含本租户。 */
    public DcsChangelog getById(Long logId) {
        DcsChangelog c = changelogMapper.selectOne(new LambdaQueryWrapper<DcsChangelog>()
            .eq(DcsChangelog::getLogId, logId)
            .eq(DcsChangelog::getDelFlag, "0"));
        if (c == null) {
            throw new ServiceException("更新日志不存在");
        }
        parse(c);
        boolean visible = LoginHelper.isSuperAdmin()
            || ("1".equals(c.getStatus()) && c.getTargetTenantList().contains(LoginHelper.getTenantId()));
        if (!visible) {
            throw new ServiceException("更新日志不存在或未发布");
        }
        return c;
    }

    /** 新增（只能存草稿）。 */
    @Transactional(rollbackFor = Exception.class)
    public Long add(DcsChangelogBo bo) {
        assertSuperAdmin();
        List<ChangelogItem> items = validateItems(bo.getItemList());
        List<String> targets = normalizeTenants(bo.getTargetTenantList());
        DcsChangelog c = new DcsChangelog();
        c.setLogId(IdWorker.getId());
        c.setVersion(bo.getVersion().trim());
        c.setTitle(bo.getTitle().trim());
        c.setItems(JsonUtils.toJsonString(items));
        c.setTargetTenants(JsonUtils.toJsonString(targets));
        c.setStatus("0");
        c.setDelFlag("0");
        c.setCreateBy(LoginHelper.getUserId());
        c.setCreateTime(new Date());
        try {
            changelogMapper.insert(c);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("版本号 " + c.getVersion() + " 已存在，请换一个");
        }
        return c.getLogId();
    }

    /** 编辑 / 发布。 */
    @Transactional(rollbackFor = Exception.class)
    public void edit(DcsChangelogBo bo) {
        assertSuperAdmin();
        DcsChangelog old = changelogMapper.selectForUpdate(bo.getLogId());
        if (old == null) {
            throw new ServiceException("更新日志不存在");
        }
        // status 缺省=沿用旧状态，避免前端漏传时把已发布版本置空/误发通知
        String nextStatus = bo.getStatus() == null ? old.getStatus() : bo.getStatus();
        if ("1".equals(old.getStatus()) && "0".equals(nextStatus)) {
            throw new ServiceException("已发布的版本不能撤回为草稿；如需更正请直接编辑内容");
        }
        List<ChangelogItem> items = validateItems(bo.getItemList());
        List<String> oldTargets = parseTenants(old.getTargetTenants());
        List<String> targets = bo.getTargetTenantList() == null ? oldTargets : normalizeTenants(bo.getTargetTenantList());
        boolean publishing = "0".equals(old.getStatus()) && "1".equals(nextStatus);
        if ("1".equals(nextStatus) && targets.isEmpty()) {
            throw new ServiceException("发布前至少选择一家推送单位");
        }

        old.setVersion(bo.getVersion().trim());
        old.setTitle(bo.getTitle().trim());
        old.setItems(JsonUtils.toJsonString(items));
        old.setTargetTenants(JsonUtils.toJsonString(targets));
        old.setStatus(nextStatus);
        if ("1".equals(nextStatus) && old.getPublishTime() == null) {
            old.setPublishTime(new Date());
        }
        old.setUpdateBy(LoginHelper.getUserId());
        old.setUpdateTime(new Date());
        try {
            changelogMapper.updateById(old);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("版本号 " + old.getVersion() + " 已存在，请换一个");
        }

        if (!"1".equals(nextStatus)) {
            return;
        }
        Set<String> notifyTenants = new LinkedHashSet<>();
        if (publishing) {
            notifyTenants.addAll(targets);
        } else {
            for (String t : targets) {
                if (!oldTargets.contains(t)) {
                    notifyTenants.add(t);
                }
            }
        }
        notifyTenants(notifyTenants, old);
    }

    /** 逻辑删除。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(List<Long> logIds) {
        assertSuperAdmin();
        for (Long id : logIds) {
            DcsChangelog c = new DcsChangelog();
            c.setLogId(id);
            c.setDelFlag("1");
            c.setUpdateBy(LoginHelper.getUserId());
            c.setUpdateTime(new Date());
            changelogMapper.updateById(c);
        }
    }

    /** 发布通知：每个目标租户的管理员各一条（排除操作人自己）。 */
    private void notifyTenants(Set<String> tenantIds, DcsChangelog c) {
        if (tenantIds.isEmpty()) {
            return;
        }
        Long operator = LoginHelper.getUserId();
        for (String tenantId : tenantIds) {
            // 三审回修（dspro P1-2）：TenantHelper.dynamic 的 finally 会 clearDynamic，
            // 在登录线程上会连超管「切换单位」的持久态一起删——先存后还，发布完仍停在原租户
            String prevDynamic = TenantHelper.getDynamic();
            try {
                TenantHelper.dynamic(tenantId, () -> {
                    List<Long> admins = userLookupMapper.selectAdminUserIds();
                    for (Long adminId : admins) {
                        if (adminId.equals(operator)) {
                            continue;
                        }
                        noticeService.notifyIdem(adminId, NOTICE_TYPE, "系统已更新至 " + c.getVersion(),
                            "《" + c.getTitle() + "》已发布，点击查看更新日志。", null,
                            "changelog:" + c.getLogId() + ":" + tenantId, URL);
                    }
                    return null;
                });
            } catch (Exception e) {
                log.warn("[Changelog] 发布通知失败 tenant={} log={}: {}", tenantId, c.getLogId(), e.getMessage());
            } finally {
                if (org.dromara.common.core.utils.StringUtils.isNotBlank(prevDynamic)) {
                    TenantHelper.setDynamic(prevDynamic, true);
                }
            }
        }
    }

    private List<ChangelogItem> validateItems(List<ChangelogItem> items) {
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new ServiceException("每个版本需 1~" + MAX_ITEMS + " 条更新");
        }
        List<ChangelogItem> out = new ArrayList<>();
        for (ChangelogItem item : items) {
            if (item == null || item.getType() == null || item.getText() == null) {
                throw new ServiceException("更新条目缺少类型或内容");
            }
            String type = item.getType().trim();
            if (!ChangelogItem.TYPE_NEW.equals(type) && !ChangelogItem.TYPE_OPTIMIZE.equals(type)
                && !ChangelogItem.TYPE_FIX.equals(type)) {
                throw new ServiceException("更新条目类型只能是 new / optimize / fix");
            }
            String text = item.getText().trim();
            if (text.isEmpty() || text.length() > 200) {
                throw new ServiceException("每条更新内容需 1~200 字");
            }
            out.add(new ChangelogItem(type, text));
        }
        return out;
    }

    private List<String> normalizeTenants(List<String> tenants) {
        if (tenants == null) {
            return new ArrayList<>();
        }
        return tenants.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).distinct().toList();
    }

    private void parse(DcsChangelog c) {
        try {
            c.setItemList(JsonUtils.parseObject(c.getItems(), new TypeReference<List<ChangelogItem>>() {
            }));
        } catch (Exception e) {
            c.setItemList(new ArrayList<>());
        }
        if (c.getItemList() == null) {
            c.setItemList(new ArrayList<>());
        }
        c.setTargetTenantList(parseTenants(c.getTargetTenants()));
    }

    private List<String> parseTenants(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> list = JsonUtils.parseObject(json, new TypeReference<List<String>>() {
            });
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void assertSuperAdmin() {
        if (!LoginHelper.isSuperAdmin()) {
            throw new ServiceException("只有超级管理员可以维护更新日志");
        }
    }
}
