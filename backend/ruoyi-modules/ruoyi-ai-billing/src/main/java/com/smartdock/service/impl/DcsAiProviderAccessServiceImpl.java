package com.smartdock.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.lock.annotation.Lock4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.DcsAiProviderAccess;
import com.smartdock.domain.bo.DcsAiProbeBo;
import com.smartdock.domain.bo.DcsAiProviderAccessBo;
import com.smartdock.domain.vo.DcsAiBalanceVo;
import com.smartdock.domain.vo.DcsAiProviderAccessVo;
import com.smartdock.enums.DcsAiProvider;
import com.smartdock.mapper.DcsAiConfigMapper;
import com.smartdock.mapper.DcsAiProviderAccessMapper;
import com.smartdock.service.IDcsAiProviderAccessService;
import com.smartdock.service.support.DcsAiProviderProbeClient;
import com.smartdock.service.support.ProviderKeyPool;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 厂商接入层 Service 实现（批B，2026-10-09；AI 缺口批 Q8 2026-10-14 钥匙池扩展）。
 *
 * <p><b>钥匙真源（Q8 起）</b>：每租户每厂商一或多行（唯一约束已放开为普通索引）。
 * 运行时选路走 {@link ProviderKeyPool}（轮询+冷却+零余额跳过）；探测类端点固定
 * <b>主钥匙</b>=enabled 按 id 升序第一行（语义稳定可解释：配置卡余额=主钥匙余额）。</p>
 *
 * <p><b>余额口径（Q8 深度设计）</b>：余额是「钥匙（账号）」的属性，不是模型配置的——
 * 查得非算得，快照落钥匙行四列（amount/currency/checked_at/status）。不支持余额接口
 * 的厂商标 UNSUPPORTED 此后零外呼；探测失败标 FAILED <b>保留旧快照</b>（显示时带
 * 快照时间，管理员自行判断新旧）。刷新三路：手动（本类 refresh*）／定时 04:30
 * （{@link #scheduledBalanceRefresh}）／选路联动（pool 零余额跳过，不回写不造数据）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DcsAiProviderAccessServiceImpl implements IDcsAiProviderAccessService {

    private static final String MASKED_KEY = "****";

    /** 支持厂商侧余额查询的厂商（与 DcsAiProviderProbeClient.balance 路由分支同口径） */
    private static final List<String> BALANCE_SUPPORTED = List.of("SILICONFLOW", "OPENROUTER", "DEEPSEEK");

    /** 定时刷新的串行间隔（毫秒）——防厂商余额端点限流 */
    private static final long REFRESH_INTERVAL_MS = 500L;

    /** 接入表 Mapper（钥匙真源） */
    private final DcsAiProviderAccessMapper accessMapper;
    /** 模型配置 Mapper（affectedModelCount 统计用） */
    private final DcsAiConfigMapper aiConfigMapper;
    /** 厂商探测客户端（余额实查） */
    private final DcsAiProviderProbeClient probeClient;
    /** 钥匙池（删除/禁用时清冷却） */
    private final ProviderKeyPool keyPool;

    /** 查询某租户某厂商的接入（主钥匙行；不存在返回 null，前端据此切「首次配置」形态） */
    @Override
    public DcsAiProviderAccessVo getAccess(String tenantId, String provider) {
        if (StrUtil.isBlank(tenantId) || StrUtil.isBlank(provider)) {
            throw new ServiceException("租户编号与模型厂商不能为空");
        }
        // F9 同口径守卫：普通租户只能看自己租户的接入（防枚举他人掩码）
        DcsAiConfigServiceImpl.ensureCanAccessAiConfig(
            LoginHelper.getTenantId(), tenantId, LoginHelper.isSuperAdmin());
        List<DcsAiProviderAccess> rows = selectRows(tenantId, provider);
        return TenantHelper.ignore(() -> toVo(primaryOf(rows)));
    }

    /** Q8 钥匙列表（掩码+启停+主钥匙标记+余额快照），按 id 升序 */
    @Override
    public List<DcsAiProviderAccessVo> listKeys(String tenantId, String provider) {
        DcsAiConfigServiceImpl.ensureCanAccessAiConfig(
            LoginHelper.getTenantId(), tenantId, LoginHelper.isSuperAdmin());
        List<DcsAiProviderAccess> rows = selectRows(tenantId, provider);
        Long primaryId = idOf(primaryOf(rows));
        List<DcsAiProviderAccessVo> out = new ArrayList<>(rows.size());
        for (DcsAiProviderAccess row : rows) {
            DcsAiProviderAccessVo vo = toVo(row);
            vo.setPrimary(primaryId != null && primaryId.equals(row.getId()));
            out.add(vo);
        }
        return out;
    }

    /** 新增/编辑接入（upsert）：bo.id 指定行；无 id 兼容批B 旧表单（编辑主钥匙/无行新建） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DcsAiProviderAccessVo updateAccess(DcsAiProviderAccessBo bo) {
        if (!DcsAiProvider.supported(bo.getProvider())) {
            throw new ServiceException("暂不支持该模型厂商");
        }
        if (StrUtil.isBlank(bo.getBaseUrl())) {
            // 批1：标准厂商空地址=按官方默认接入（选了厂商不该让人手填 URL）；自定义厂商仍须手填
            String preset = DcsAiProvider.defaultBaseUrl(bo.getProvider());
            if (preset == null) {
                throw new ServiceException("自定义厂商必须填写接口地址");
            }
            bo.setBaseUrl(preset);
        }
        if (StrUtil.isBlank(bo.getTenantId())) {
            throw new ServiceException("租户编号不能为空");
        }
        bo.setBaseUrl(StrUtil.removeSuffix(StrUtil.trim(bo.getBaseUrl()), "/"));
        boolean effectiveChanged = TenantHelper.ignore(() -> upsert(bo));
        DcsAiProviderAccessVo vo;
        if (bo.getId() != null) {
            DcsAiProviderAccess row = TenantHelper.ignore(() -> accessMapper.selectById(bo.getId()));
            vo = TenantHelper.ignore(() -> toVo(row));
        } else {
            List<DcsAiProviderAccess> rows = selectRows(bo.getTenantId(), bo.getProvider());
            vo = TenantHelper.ignore(() -> toVo(primaryOf(rows)));
        }
        if (effectiveChanged && vo != null) {
            vo.setAffectedModelCount(TenantHelper.ignore(() ->
                Math.toIntExact(aiConfigMapper.selectCount(
                    new LambdaQueryWrapper<DcsAiConfig>()
                        .eq(DcsAiConfig::getTenantId, bo.getTenantId())
                        .eq(DcsAiConfig::getProvider, bo.getProvider())))));
        }
        return vo;
    }

    /**
     * upsert 钥匙行；返回是否发生「生效要素」变更（钥匙或接口地址换任何一个都影响整厂调用量）。
     * Q8：bo.id 指定行编辑；无 id 且已有行=编辑主钥匙（批B 旧表单兼容）；无行=新建。
     */
    private boolean upsert(DcsAiProviderAccessBo bo) {
        // 批B 三审 P0：守卫前置（首建分支也须覆盖——非超管不可给他租户建行）
        DcsAiConfigServiceImpl.ensureCanAccessAiConfig(
            LoginHelper.getTenantId(), bo.getTenantId(), LoginHelper.isSuperAdmin());
        DcsAiProviderAccess existing;
        if (bo.getId() != null) {
            existing = accessMapper.selectById(bo.getId());
            if (existing == null || !StrUtil.equals(existing.getTenantId(), bo.getTenantId())
                || !StrUtil.equals(existing.getProvider(), bo.getProvider())) {
                throw new ServiceException("钥匙行不存在或与表单厂商/租户不符，请刷新后重试");
            }
        } else {
            existing = primaryOf(selectRows(bo.getTenantId(), bo.getProvider()));
        }
        boolean realKey = StrUtil.isNotBlank(bo.getApiKey()) && !MASKED_KEY.equals(bo.getApiKey());
        if (existing == null) {
            if (!realKey) {
                throw new ServiceException("新增钥匙必须填写 API Key");
            }
            DcsAiProviderAccess entity = MapstructUtils.convert(bo, DcsAiProviderAccess.class);
            entity.setId(null);
            entity.setApiKey(StrUtil.trim(bo.getApiKey()));
            entity.setConsoleUrl(StrUtil.trimToNull(bo.getConsoleUrl()));
            // Q8：标签缺省自动编号「钥匙 N」（N=现有行数+1）；enabled 缺省启用
            if (StrUtil.isBlank(entity.getLabel())) {
                entity.setLabel("钥匙 " + (selectRows(bo.getTenantId(), bo.getProvider()).size() + 1));
            }
            entity.setEnabled(StrUtil.blankToDefault(bo.getEnabled(), "1"));
            accessMapper.insert(entity);
            return true;
        }
        boolean baseUrlChanged = !StrUtil.equals(existing.getBaseUrl(), bo.getBaseUrl());
        DcsAiProviderAccess patch = new DcsAiProviderAccess();
        patch.setId(existing.getId());
        patch.setBaseUrl(bo.getBaseUrl());
        patch.setConsoleUrl(StrUtil.trimToNull(bo.getConsoleUrl()));
        patch.setRemark(bo.getRemark());
        patch.setLabel(StrUtil.trimToNull(bo.getLabel()));
        if (StrUtil.isNotBlank(bo.getEnabled())) {
            patch.setEnabled(bo.getEnabled());
        }
        if (realKey) {
            patch.setApiKey(StrUtil.trim(bo.getApiKey()));
        }
        accessMapper.updateById(patch);
        return realKey || baseUrlChanged;
    }

    /**
     * Q8 补遗（2026-10-15）：显式新增一把钥匙（「添加新钥匙」专用通路）。
     * upsert 无 id=编辑主钥匙的批B 兼容语义不动，新增语义独立成端——两侧互不干扰。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DcsAiProviderAccessVo addKey(DcsAiProviderAccessBo bo) {
        if (!DcsAiProvider.supported(bo.getProvider())) {
            throw new ServiceException("暂不支持该模型厂商");
        }
        if (StrUtil.isBlank(bo.getBaseUrl())) {
            // 批1：标准厂商空地址=按官方默认接入；自定义厂商仍须手填（同 updateAccess）
            String preset = DcsAiProvider.defaultBaseUrl(bo.getProvider());
            if (preset == null) {
                throw new ServiceException("自定义厂商必须填写接口地址");
            }
            bo.setBaseUrl(preset);
        }
        if (StrUtil.isBlank(bo.getTenantId())) {
            throw new ServiceException("租户编号不能为空");
        }
        boolean realKey = StrUtil.isNotBlank(bo.getApiKey()) && !MASKED_KEY.equals(bo.getApiKey());
        if (!realKey) {
            throw new ServiceException("新增钥匙必须填写 API Key");
        }
        DcsAiConfigServiceImpl.ensureCanAccessAiConfig(
            LoginHelper.getTenantId(), bo.getTenantId(), LoginHelper.isSuperAdmin());
        DcsAiProviderAccess entity = MapstructUtils.convert(bo, DcsAiProviderAccess.class);
        entity.setId(IdWorker.getId());
        entity.setApiKey(StrUtil.trim(bo.getApiKey()));
        entity.setBaseUrl(StrUtil.removeSuffix(StrUtil.trim(bo.getBaseUrl()), "/"));
        entity.setConsoleUrl(StrUtil.trimToNull(bo.getConsoleUrl()));
        if (StrUtil.isBlank(entity.getLabel())) {
            entity.setLabel("钥匙 " + (selectRows(bo.getTenantId(), bo.getProvider()).size() + 1));
        }
        entity.setEnabled(StrUtil.blankToDefault(bo.getEnabled(), "1"));
        // 新钥匙未探测：余额四列置空（UNSUPPORTED 由探测时按厂商能力落）
        entity.setBalanceStatus(null);
        accessMapper.insert(entity);
        log.info("[KeyPool] 新增钥匙 id={} tenant={} provider={} label={} operator={}",
            entity.getId(), bo.getTenantId(), bo.getProvider(), entity.getLabel(), LoginHelper.getUserId());
        return TenantHelper.ignore(() -> toVo(entity));
    }

    /** Q8 钥匙启停：不能禁用最后一把 enabled 钥匙（会切断该厂全部模型调用） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeKeyStatus(Long id, String enabled) {
        if (!"0".equals(enabled) && !"1".equals(enabled)) {
            throw new ServiceException("启停状态只接受 0/1");
        }
        DcsAiProviderAccess row = requireOwnedRow(id);
        if ("0".equals(enabled)) {
            guardNotLastEnabledKey(row);
        }
        DcsAiProviderAccess patch = new DcsAiProviderAccess();
        patch.setId(id);
        patch.setEnabled(enabled);
        accessMapper.updateById(patch);
        keyPool.evict(row.getTenantId(), row.getProvider(), id);
        log.info("[KeyPool] 钥匙启停 id={} enabled={} operator={}", id, enabled, LoginHelper.getUserId());
    }

    /** Q8 删除钥匙（软删）：不能删最后一把 enabled 钥匙 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteKey(Long id) {
        DcsAiProviderAccess row = requireOwnedRow(id);
        guardNotLastEnabledKey(row);
        accessMapper.deleteById(id);
        keyPool.evict(row.getTenantId(), row.getProvider(), id);
        log.info("[KeyPool] 钥匙删除 id={} tenant={} provider={} operator={}",
            id, row.getTenantId(), row.getProvider(), LoginHelper.getUserId());
    }

    /** 删/停守卫失败时抛 409 人话 */
    private void guardNotLastEnabledKey(DcsAiProviderAccess row) {
        long enabledCount = TenantHelper.ignore(() -> accessMapper.selectCount(
            new LambdaQueryWrapper<DcsAiProviderAccess>()
                .eq(DcsAiProviderAccess::getTenantId, row.getTenantId())
                .eq(DcsAiProviderAccess::getProvider, row.getProvider())
                .eq(DcsAiProviderAccess::getEnabled, "1")));
        if (enabledCount <= 1) {
            throw new ServiceException("该厂商仅剩这把启用中的钥匙，不能停用/删除——请先添加新钥匙", 409);
        }
    }

    /** 行归属守卫：行必须存在且属于当前租户（或超管） */
    private DcsAiProviderAccess requireOwnedRow(Long id) {
        DcsAiProviderAccess row = TenantHelper.ignore(() -> accessMapper.selectById(id));
        if (row == null) {
            throw new ServiceException("钥匙行不存在或已删除");
        }
        DcsAiConfigServiceImpl.ensureCanAccessAiConfig(
            LoginHelper.getTenantId(), row.getTenantId(), LoginHelper.isSuperAdmin());
        return row;
    }

    // ==================== 余额（Q8 深度设计：查得非算得，快照落钥匙行） ====================

    /** Q8 单钥匙余额刷新：实查厂商余额端点 → 快照落行 */
    @Override
    public DcsAiProviderAccessVo refreshBalance(String tenantId, Long id) {
        DcsAiProviderAccess row = requireOwnedRow(id);
        doRefreshBalanceSnapshot(row);
        return TenantHelper.ignore(() -> toVo(accessMapper.selectById(id)));
    }

    /** Q8 整厂批量刷新（手动「全部刷新」）：UNSUPPORTED 跳过零外呼，单把失败不断整批 */
    @Override
    public List<DcsAiProviderAccessVo> refreshAllBalances(String tenantId, String provider) {
        DcsAiConfigServiceImpl.ensureCanAccessAiConfig(
            LoginHelper.getTenantId(), tenantId, LoginHelper.isSuperAdmin());
        List<DcsAiProviderAccess> rows = selectRows(tenantId, provider);
        Long primaryId = idOf(primaryOf(rows));
        List<DcsAiProviderAccessVo> out = new ArrayList<>(rows.size());
        for (DcsAiProviderAccess row : rows) {
            if (isBalanceSupported(row.getProvider()) && !"UNSUPPORTED".equals(row.getBalanceStatus())) {
                try {
                    doRefreshBalanceSnapshot(row);
                } catch (Exception e) {
                    log.warn("[KeyPool] 批量余额刷新单把失败 id={}: {}", row.getId(), e.getMessage());
                }
            }
            DcsAiProviderAccessVo vo = toVo(accessMapper.selectById(row.getId()));
            if (vo != null) {
                vo.setPrimary(primaryId != null && primaryId.equals(row.getId()));
            }
            out.add(vo);
        }
        return out;
    }

    /**
     * Q8 定时余额刷新（每日 04:30，与 02:30 对账/09:00 钱包/09:10 积分错峰）：
     * 全库 enabled 且非 UNSUPPORTED 钥匙串行探测（≥500ms/把防限流）。@Lock4j 跨节点互斥；
     * 快照非关键数据，重启错过不补跑（手动/次日自然新鲜）。
     */
    @Override
    @Scheduled(cron = "0 30 4 * * ?")
    @Lock4j(keys = {"'provider-balance-refresh'"})
    public void scheduledBalanceRefresh() {
        List<DcsAiProviderAccess> rows = TenantHelper.ignore(() ->
            accessMapper.selectList(new LambdaQueryWrapper<DcsAiProviderAccess>()
                .eq(DcsAiProviderAccess::getEnabled, "1")));
        int ok = 0;
        int failed = 0;
        int skipped = 0;
        for (DcsAiProviderAccess row : rows) {
            if (!isBalanceSupported(row.getProvider()) || "UNSUPPORTED".equals(row.getBalanceStatus())) {
                skipped++;
                continue;
            }
            try {
                doRefreshBalanceSnapshot(row);
                ok++;
            } catch (Exception e) {
                failed++;
                log.warn("[KeyPool] 定时余额刷新失败 id={} provider={}: {}",
                    row.getId(), row.getProvider(), e.getMessage());
            }
            try {
                Thread.sleep(REFRESH_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (ok + failed > 0) {
            log.info("[KeyPool] 定时余额刷新完成: 成功 {} / 失败 {} / 跳过 {}", ok, failed, skipped);
        }
    }

    /** 厂商是否支持余额查询（与 ProbeClient.balance 路由分支同口径，探测前置判零外呼） */
    static boolean isBalanceSupported(String provider) {
        return provider != null && BALANCE_SUPPORTED.contains(provider.toUpperCase());
    }

    /**
     * 单钥匙余额实查+落快照。三态：OK（有数）/UNSUPPORTED（厂商不支持，此后零外呼）/
     * FAILED（本次失败，保留旧快照只挪 checked_at 与状态）。
     */
    private void doRefreshBalanceSnapshot(DcsAiProviderAccess row) {
        DcsAiProviderAccess patch = new DcsAiProviderAccess();
        patch.setId(row.getId());
        patch.setBalanceCheckedAt(new Date());
        if (!isBalanceSupported(row.getProvider())) {
            patch.setBalanceStatus("UNSUPPORTED");
            TenantHelper.ignore(() -> accessMapper.updateById(patch));
            return;
        }
        try {
            DcsAiProbeBo bo = new DcsAiProbeBo();
            bo.setTenantId(row.getTenantId());
            bo.setProvider(row.getProvider());
            bo.setBaseUrl(row.getBaseUrl());
            bo.setApiKey(row.getApiKey());
            bo.setTimeoutSeconds(10);
            DcsAiBalanceVo vo = probeClient.balance(bo);
            List<DcsAiBalanceVo.BalanceInfo> balances = vo == null ? null : vo.getBalances();
            if (vo == null || !Boolean.TRUE.equals(vo.getAvailable()) || balances == null || balances.isEmpty()) {
                // 厂商支持但查不到数 → 钥匙级失败（key 无效/欠费），标 FAILED 保留旧值
                patch.setBalanceStatus("FAILED");
                TenantHelper.ignore(() -> accessMapper.updateById(patch));
                return;
            }
            DcsAiBalanceVo.BalanceInfo first = balances.get(0);
            patch.setBalanceAmount(parseAmount(first.getTotalBalance()));
            patch.setBalanceCurrency(StrUtil.blankToDefault(first.getCurrency(), "CNY"));
            // OpenRouter 不限额 key（"不限"）amount=null：状态照 OK，选路不据此跳过
            patch.setBalanceStatus("OK");
            TenantHelper.ignore(() -> accessMapper.updateById(patch));
        } catch (Exception e) {
            patch.setBalanceStatus("FAILED");
            TenantHelper.ignore(() -> accessMapper.updateById(patch));
            throw e instanceof ServiceException se ? se : new ServiceException("余额查询失败: " + e.getMessage());
        }
    }

    /** 金额串安全转 BigDecimal（"不限"等非数值 → null=无法量化，仅展示） */
    private static BigDecimal parseAmount(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 公共小件 ====================

    /** 按租户+厂商取全部未删行，id 升序（首行语义=最老钥匙） */
    private List<DcsAiProviderAccess> selectRows(String tenantId, String provider) {
        return TenantHelper.ignore(() -> accessMapper.selectList(
            new LambdaQueryWrapper<DcsAiProviderAccess>()
                .eq(DcsAiProviderAccess::getTenantId, tenantId)
                .eq(DcsAiProviderAccess::getProvider, provider)
                .orderByAsc(DcsAiProviderAccess::getId)));
    }

    /** 主钥匙 = enabled 按 id 升序第一行；全停用时兜底 id 最小行（探测端点仍有钥可探） */
    private static DcsAiProviderAccess primaryOf(List<DcsAiProviderAccess> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.stream()
            .filter(r -> !"0".equals(r.getEnabled()))
            .findFirst()
            .orElse(rows.get(0));
    }

    private static Long idOf(DcsAiProviderAccess row) {
        return row == null ? null : row.getId();
    }

    /** 实体转 VO（掩码 + 显示名） */
    private DcsAiProviderAccessVo toVo(DcsAiProviderAccess access) {
        if (access == null) {
            return null;
        }
        DcsAiProviderAccessVo vo = MapstructUtils.convert(access, DcsAiProviderAccessVo.class);
        vo.setApiKeyMask(DcsAiConfigServiceImpl.maskApiKeyForDisplay(access.getApiKey()));
        if (vo.getProvider() != null) {
            try {
                vo.setProviderName(DcsAiProvider.valueOf(vo.getProvider()).getLabel());
            } catch (IllegalArgumentException e) {
                vo.setProviderName(vo.getProvider());
            }
        }
        return vo;
    }
}
