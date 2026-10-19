package com.smartdock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import com.smartdock.domain.DcsTenantWalletLedger;
import com.smartdock.domain.vo.DcsTenantWalletLedgerVo;
import com.smartdock.mapper.DcsTenantWalletLedgerMapper;
import com.smartdock.service.support.AiServiceCodeLabels;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户钱包流水 Controller。
 *
 * @author smartdock
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dcs/tenant-wallet-ledger")
public class DcsTenantWalletLedgerController extends BaseController {

    private final DcsTenantWalletLedgerMapper ledgerMapper;

    /**
     * 分页查询
     *
     * @param pageQuery 分页参数
     * @param tenantId 租户ID（可选）
     * @param walletId 钱包ID（可选）
     * @param serviceCode 服务编码（可选）
     * @param direction 收支方向（可选）
     * @param bizType 业务类型（可选）
     * @return 分页的租户钱包流水列表
     */
    @SaCheckPermission("dcs:wallet-ledger:list")
    @GetMapping("/list")
    public TableDataInfo<DcsTenantWalletLedgerVo> list(PageQuery pageQuery,
                                                        @RequestParam(required = false) String tenantId,
                                                        @RequestParam(required = false) Long walletId,
                                                        @RequestParam(required = false) String serviceCode,
                                                        @RequestParam(required = false) String direction,
                                                        @RequestParam(required = false) String bizType) {
        LambdaQueryWrapper<DcsTenantWalletLedger> wrapper = new LambdaQueryWrapper<>();
        // 深扫回修(2026-10-16 P0-2): 本表 Mapper 关闭租户拦截器, 非超管强制收窄本租户——
        // 不传 tenantId 也不能拉全平台资金流水(与 walletSummary/queryPageList 同口径)
        String scopeTenantId = LoginHelper.isSuperAdmin() ? tenantId : LoginHelper.getTenantId();
        if (StrUtil.isNotBlank(scopeTenantId)) {
            wrapper.eq(DcsTenantWalletLedger::getTenantId, scopeTenantId);
        } else if (!LoginHelper.isSuperAdmin()) {
            return TableDataInfo.build(new Page<>());
        }
        if (walletId != null) {
            wrapper.eq(DcsTenantWalletLedger::getWalletId, walletId);
        }
        if (StrUtil.isNotBlank(serviceCode)) {
            wrapper.eq(DcsTenantWalletLedger::getServiceCode, serviceCode);
        }
        if (StrUtil.isNotBlank(direction)) {
            wrapper.eq(DcsTenantWalletLedger::getDirection, direction);
        }
        if (StrUtil.isNotBlank(bizType)) {
            wrapper.eq(DcsTenantWalletLedger::getBizType, bizType);
        }
        wrapper.orderByDesc(DcsTenantWalletLedger::getCreateTime);
        Page<DcsTenantWalletLedgerVo> page = ledgerMapper.selectVoPage(pageQuery.build(), wrapper);
        // 业务批(2026-10-15) Q2：服务码中文标签后端统一下发（唯一权威映射见 AiServiceCodeLabels）
        page.getRecords().forEach(r -> r.setServiceCodeLabel(AiServiceCodeLabels.label(r.getServiceCode())));
        return TableDataInfo.build(page);
    }
}
