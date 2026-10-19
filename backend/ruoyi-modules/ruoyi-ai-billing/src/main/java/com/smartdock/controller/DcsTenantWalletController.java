package com.smartdock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import com.smartdock.domain.bo.WalletOperationBo;
import com.smartdock.domain.bo.WalletThresholdBo;
import com.smartdock.domain.vo.DcsTenantWalletVo;
import com.smartdock.service.support.TenantWalletService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 租户钱包 Controller。
 *
 * @author smartdock
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dcs/tenant-wallet")
public class DcsTenantWalletController extends BaseController {

    private final TenantWalletService walletService;

    /**
     * 分页查询
     *
     * @param pageQuery 分页参数
     * @param tenantId 租户ID（可选）
     * @return 分页的租户钱包列表
     */
    @SaCheckPermission("dcs:wallet:list")
    @GetMapping("/list")
    public TableDataInfo<DcsTenantWalletVo> list(PageQuery pageQuery,
                                                  @RequestParam(required = false) String tenantId) {
        return walletService.queryPageList(pageQuery, tenantId);
    }

    /**
     * 钱包全量汇总(账户与账单页 KPI: 总余额/冻结/低于阈值/异常)。
     * 深审回修(2026-10-16 P1-3): 全平台口径仅超管可见, 非超管强制收窄到自己租户——
     * 该表 Mapper 关闭了租户拦截器, 不加守卫则未来任何授权 9067 的角色一次拿到全平台资金总量。
     * 深扫回修(P1-1): 传 null 而非登录租户——此前超管 KPI 被无声缩到 000000 与下方全平台表格口径撕裂。
     */
    @SaCheckPermission("dcs:wallet:list")
    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        return R.ok(walletService.walletSummary(null));
    }

    /**
     * 详情
     *
     * @param walletId 钱包ID
     * @return 租户钱包业务数据
     */
    @SaCheckPermission("dcs:wallet:query")
    @GetMapping("/{walletId}")
    public R<DcsTenantWalletVo> getInfo(@PathVariable Long walletId) {
        return R.ok(walletService.queryById(walletId));
    }

    /**
     * 为指定租户初始化主钱包(幂等, 赠送体验金)。
     *
     * @param tenantId 租户ID（可选，为空时取当前登录租户）
     * @return 初始化后的租户钱包业务数据
     */
    @SaCheckPermission("dcs:wallet:recharge")
    @Log(title = "租户钱包-初始化", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/init-main")
    public R<DcsTenantWalletVo> initMain(@RequestParam(required = false) String tenantId) {
        String resolved = StrUtil.blankToDefault(tenantId,
            org.dromara.common.satoken.utils.LoginHelper.getTenantId());
        return R.ok(walletService.initMainWallet(resolved));
    }

    /**
     * 手动充值(管理员调账, 走幂等键防重复, 写流水)
     *
     * @param walletId 钱包ID
     * @param bo 钱包操作业务对象
     * @return 充值后的租户钱包业务数据
     */
    @SaCheckPermission("dcs:wallet:recharge")
    @Log(title = "租户钱包-手动充值", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{walletId}/manual-recharge")
    public R<DcsTenantWalletVo> manualRecharge(@PathVariable Long walletId,
                                                @Valid @RequestBody WalletOperationBo bo) {
        return R.ok(walletService.manualRecharge(walletId, bo));
    }

    /**
     * 手动扣减(管理员调账/补偿扣款, 校验余额, 写流水)
     *
     * @param walletId 钱包ID
     * @param bo 钱包操作业务对象
     * @return 扣减后的租户钱包业务数据
     */
    @SaCheckPermission("dcs:wallet:deduct")
    @Log(title = "租户钱包-手动扣减", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{walletId}/manual-deduct")
    public R<DcsTenantWalletVo> manualDeduct(@PathVariable Long walletId,
                                              @Valid @RequestBody WalletOperationBo bo) {
        return R.ok(walletService.manualDeduct(walletId, bo));
    }

    /**
     * 修改钱包低余额阈值(低于该值触发预警)
     *
     * @param walletId 钱包ID
     * @param bo 钱包低余额阈值业务对象
     * @return 更新后的租户钱包业务数据
     */
    @SaCheckPermission("dcs:wallet:status")
    @Log(title = "租户钱包-修改阈值", businessType = BusinessType.UPDATE)
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/{walletId}/threshold")
    public R<DcsTenantWalletVo> updateThreshold(@PathVariable Long walletId,
                                                 @Valid @RequestBody WalletThresholdBo bo) {
        return R.ok(walletService.updateThreshold(walletId, bo.getLowBalanceThreshold()));
    }

    /**
     * 修改钱包状态(启用/冻结, 冻结后阻止扣费)
     *
     * @param walletId 钱包ID
     * @param status 钱包状态（启用/冻结）
     * @return 更新后的租户钱包业务数据
     */
    @SaCheckPermission("dcs:wallet:status")
    @Log(title = "租户钱包-修改状态", businessType = BusinessType.UPDATE)
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/{walletId}/status")
    public R<DcsTenantWalletVo> updateStatus(@PathVariable Long walletId,
                                              @RequestParam String status) {
        return R.ok(walletService.updateStatus(walletId, status));
    }
}
