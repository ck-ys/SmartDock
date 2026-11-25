package com.smartdock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import com.smartdock.domain.vo.TenantWalletLedgerVo;
import com.smartdock.domain.vo.WalletLedgerSummaryVo;
import com.smartdock.service.support.TenantWalletLedgerService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户钱包流水 Controller。
 *
 * <p>2026-11-25 钱包流水批：查询与合计下沉到 {@link TenantWalletLedgerService}
 * （原 Controller 直接注入 Mapper + 拼 wrapper，超 40 行且合计口径会与列表漂移）。</p>
 *
 * @author smartdock
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/tenant-wallet-ledger")
public class TenantWalletLedgerController extends BaseController {

    private final TenantWalletLedgerService ledgerService;

    /**
     * 分页查询
     *
     * @param pageQuery 分页参数
     * @param tenantId 租户ID（可选，非超管强制收窄本租户）
     * @param walletId 钱包ID（可选）
     * @param serviceCode 服务编码（可选）
     * @param direction 收支方向（可选）
     * @param bizType 业务类型（可选）
     * @param beginTime 起始日 yyyy-MM-dd（含，可选；2026-11-25 Q5 新增）
     * @param endTime 结束日 yyyy-MM-dd（含当天，可选；2026-11-25 Q5 新增）
     * @return 分页的租户钱包流水列表
     */
    @SaCheckPermission("smartdock:wallet-ledger:list")
    @GetMapping("/list")
    public TableDataInfo<TenantWalletLedgerVo> list(PageQuery pageQuery,
                                                    @RequestParam(required = false) String tenantId,
                                                    @RequestParam(required = false) Long walletId,
                                                    @RequestParam(required = false) String serviceCode,
                                                    @RequestParam(required = false) String direction,
                                                    @RequestParam(required = false) String bizType,
                                                    @RequestParam(required = false) String beginTime,
                                                    @RequestParam(required = false) String endTime) {
        return ledgerService.queryPage(pageQuery, tenantId, walletId, serviceCode, direction, bizType, beginTime, endTime);
    }

    /**
     * 按当前筛选条件合计（收入 / 支出 / 净额 / 笔数）。
     *
     * <p>与 {@code /list} 同口径：同一组筛选、同一套租户收窄、同一个时间半开区间。
     * 前端「合计」不许再拿当前页累加——翻页就变，会被读成数字在跳。</p>
     */
    @SaCheckPermission("smartdock:wallet-ledger:list")
    @GetMapping("/summary")
    public R<WalletLedgerSummaryVo> summary(@RequestParam(required = false) String tenantId,
                                            @RequestParam(required = false) Long walletId,
                                            @RequestParam(required = false) String serviceCode,
                                            @RequestParam(required = false) String direction,
                                            @RequestParam(required = false) String bizType,
                                            @RequestParam(required = false) String beginTime,
                                            @RequestParam(required = false) String endTime) {
        return R.ok(ledgerService.summary(tenantId, walletId, serviceCode, direction, bizType, beginTime, endTime));
    }
}
