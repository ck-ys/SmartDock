package com.smartdock.service.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.common.text.DayRange;
import com.smartdock.domain.TenantWalletLedger;
import com.smartdock.domain.vo.TenantWalletLedgerVo;
import com.smartdock.domain.vo.WalletLedgerSummaryVo;
import com.smartdock.mapper.TenantWalletLedgerMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 租户钱包流水查询服务（2026-11-25 钱包流水批）。
 *
 * <p>列表与合计必须<b>同口径</b>：同一组筛选条件、同一套租户收窄、同一个时间半开区间。
 * 两处各写一遍必然漂移（此前「当前页合计」与列表就差在这里），所以统一收在本服务。</p>
 *
 * <p>租户收窄：流水表 Mapper 关闭了租户拦截器（同钱包表，跨租户管理），
 * 非超管一律强制收窄到登录租户；登录上下文取不到租户时返回空结果，不放大权限。</p>
 *
 * @author smartdock
 */
@Service
@RequiredArgsConstructor
public class TenantWalletLedgerService {

    private final TenantWalletLedgerMapper ledgerMapper;

    /**
     * 分页查询钱包流水。
     *
     * @param beginDay 起始日 {@code yyyy-MM-dd}（含），空=不限
     * @param endDay   结束日 {@code yyyy-MM-dd}（含当天），空=不限
     */
    public TableDataInfo<TenantWalletLedgerVo> queryPage(PageQuery pageQuery, String tenantId, Long walletId,
                                                         String serviceCode, String direction, String bizType,
                                                         String beginDay, String endDay) {
        String scopeTenantId = scopeTenantId(tenantId);
        if (StrUtil.isBlank(scopeTenantId) && !LoginHelper.isSuperAdmin()) {
            return TableDataInfo.build(new Page<>());
        }
        DayRange range = DayRange.of(beginDay, endDay);
        LambdaQueryWrapper<TenantWalletLedger> wrapper = new LambdaQueryWrapper<>();
        applyFilter(wrapper, scopeTenantId, walletId, serviceCode, direction, bizType, range);
        // 按日期分组展示要求「同一天内顺序稳定」：create_time 相同再按主键倒序，翻页才不会跳行
        wrapper.orderByDesc(TenantWalletLedger::getCreateTime).orderByDesc(TenantWalletLedger::getLedgerId);
        Page<TenantWalletLedgerVo> page = ledgerMapper.selectVoPage(pageQuery.build(), wrapper);
        // 服务码中文标签后端统一下发（唯一权威映射见 AiServiceCodeLabels）
        page.getRecords().forEach(r -> {
            String label = AiServiceCodeLabels.label(r.getServiceCode());
            r.setServiceCodeLabel(label);
            r.setSummary(StrUtil.blankToDefault(reasonOf(r.getExtraJson()), label));
        });
        return TableDataInfo.build(page);
    }

    /**
     * 从 extra_json 取操作人填写的原因（{@code {"reason":"..."}}）。
     *
     * <p>手动充值/扣减的 service_code 为空、原因只落在 extra_json，
     * 解析失败一律当没有原因（老数据/手工写入的异常 JSON 不能让整页 500）。</p>
     *
     * <p>用 hutool {@code JSONUtil} 而非 {@code JsonUtils}：后者静态持有 Spring 容器里的
     * ObjectMapper，纯单测环境下类初始化即失败（本类必须有可单测的解析逻辑）。</p>
     */
    private String reasonOf(String extraJson) {
        if (StrUtil.isBlank(extraJson)) {
            return null;
        }
        try {
            cn.hutool.json.JSONObject obj = cn.hutool.json.JSONUtil.parseObj(extraJson);
            return obj == null ? null : StrUtil.trimToNull(obj.getStr("reason"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按与列表相同的筛选条件合计收入/支出/净额/笔数。
     *
     * <p>无匹配行时返回全 0 而不是 null 或报错——空区间合计为 0 是正确结果，不是异常。</p>
     */
    public WalletLedgerSummaryVo summary(String tenantId, Long walletId, String serviceCode, String direction,
                                         String bizType, String beginDay, String endDay) {
        String scopeTenantId = scopeTenantId(tenantId);
        if (StrUtil.isBlank(scopeTenantId) && !LoginHelper.isSuperAdmin()) {
            return emptySummary();
        }
        DayRange range = DayRange.of(beginDay, endDay);
        WalletLedgerSummaryVo vo = ledgerMapper.sumByFilter(scopeTenantId, walletId,
            StrUtil.emptyToNull(serviceCode), StrUtil.emptyToNull(direction), StrUtil.emptyToNull(bizType),
            range.beginInclusive(), range.endExclusive());
        return vo == null ? emptySummary() : vo;
    }

    private void applyFilter(LambdaQueryWrapper<TenantWalletLedger> wrapper, String scopeTenantId, Long walletId,
                             String serviceCode, String direction, String bizType, DayRange range) {
        if (StrUtil.isNotBlank(scopeTenantId)) {
            wrapper.eq(TenantWalletLedger::getTenantId, scopeTenantId);
        }
        if (walletId != null) {
            wrapper.eq(TenantWalletLedger::getWalletId, walletId);
        }
        if (StrUtil.isNotBlank(serviceCode)) {
            wrapper.eq(TenantWalletLedger::getServiceCode, serviceCode);
        }
        if (StrUtil.isNotBlank(direction)) {
            wrapper.eq(TenantWalletLedger::getDirection, direction);
        }
        if (StrUtil.isNotBlank(bizType)) {
            wrapper.eq(TenantWalletLedger::getBizType, bizType);
        }
        if (range.beginInclusive() != null) {
            wrapper.ge(TenantWalletLedger::getCreateTime, range.beginInclusive());
        }
        if (range.endExclusive() != null) {
            wrapper.lt(TenantWalletLedger::getCreateTime, range.endExclusive());
        }
    }

    /**
     * 非超管一律取登录租户（传参不可放大范围）；超管可指定任意租户，空=全平台。
     */
    private String scopeTenantId(String tenantId) {
        return LoginHelper.isSuperAdmin() ? tenantId : LoginHelper.getTenantId();
    }

    private WalletLedgerSummaryVo emptySummary() {
        WalletLedgerSummaryVo vo = new WalletLedgerSummaryVo();
        vo.setTotalIn(BigDecimal.ZERO);
        vo.setTotalOut(BigDecimal.ZERO);
        vo.setNet(BigDecimal.ZERO);
        vo.setCount(0);
        return vo;
    }
}
