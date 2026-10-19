package com.smartdock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import com.smartdock.domain.DcsWalletPaymentOrder;
import com.smartdock.domain.vo.DcsWalletPaymentOrderVo;
import com.smartdock.mapper.DcsWalletPaymentOrderMapper;
import com.smartdock.service.support.TenantWalletService;
import com.smartdock.domain.bo.WalletOperationBo;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

/**
 * 钱包充值订单 Controller。
 *
 * <p>第一批 MANUAL 渠道即调即充; WECHAT_PAY/ALIPAY 留接口待后续集成。</p>
 *
 * @author smartdock
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dcs/wallet-payment-order")
public class DcsWalletPaymentOrderController extends BaseController {

    private final DcsWalletPaymentOrderMapper orderMapper;
    private final TenantWalletService walletService;

    /**
     * 分页查询
     *
     * @param pageQuery 分页参数
     * @param tenantId 租户ID（可选）
     * @param walletId 钱包ID（可选）
     * @param status 订单状态（可选）
     * @return 分页的钱包充值订单列表
     */
    @SaCheckPermission("dcs:payment-order:list")
    @GetMapping("/list")
    public TableDataInfo<DcsWalletPaymentOrderVo> list(PageQuery pageQuery,
                                                        @RequestParam(required = false) String tenantId,
                                                        @RequestParam(required = false) Long walletId,
                                                        @RequestParam(required = false) String status) {
        LambdaQueryWrapper<DcsWalletPaymentOrder> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(tenantId)) {
            wrapper.eq(DcsWalletPaymentOrder::getTenantId, tenantId);
        }
        if (walletId != null) {
            wrapper.eq(DcsWalletPaymentOrder::getWalletId, walletId);
        }
        if (StrUtil.isNotBlank(status)) {
            wrapper.eq(DcsWalletPaymentOrder::getStatus, status);
        }
        wrapper.orderByDesc(DcsWalletPaymentOrder::getCreateTime);
        Page<DcsWalletPaymentOrderVo> page = orderMapper.selectVoPage(pageQuery.build(), wrapper);
        return TableDataInfo.build(page);
    }

    /**
     * 创建充值订单。
     * <p>MANUAL 渠道: 创建即支付完成, 立即充值钱包。
     * WECHAT_PAY/ALIPAY/MOCK: 创建 PENDING 订单, 等待回调。</p>
     *
     * @param order 充值订单实体
     * @return 创建后的充值订单业务数据
     */
    @SaCheckPermission("dcs:payment-order:list")
    @Log(title = "充值订单-创建", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/create")
    public R<DcsWalletPaymentOrderVo> create(@RequestBody DcsWalletPaymentOrder order) {
        if (order.getAmount() == null || order.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("充值金额必须大于0");
        }
        String channel = StrUtil.blankToDefault(order.getChannel(), "MANUAL");
        order.setOrderNo("WPO" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        order.setChannel(channel);
        order.setCurrency(StrUtil.blankToDefault(order.getCurrency(), "CNY"));
        order.setIdempotencyKey(StrUtil.blankToDefault(order.getIdempotencyKey(), order.getOrderNo()));
        order.setOperatorId(LoginHelper.getUserId());

        if ("MANUAL".equals(channel)) {
            // 手动充值: 直接完成
            order.setStatus("PAID");
            order.setPayTime(new Date());
            orderMapper.insert(order);
            // 立即充值到钱包
            WalletOperationBo rechargeBo = new WalletOperationBo();
            rechargeBo.setAmount(order.getAmount());
            rechargeBo.setReason(StrUtil.blankToDefault(order.getRemark(), "手动充值订单"));
            rechargeBo.setIdempotencyKey("order:" + order.getOrderId());
            walletService.manualRecharge(order.getWalletId(), rechargeBo);
        } else {
            // 在线支付: 创建待支付订单
            order.setStatus("PENDING");
            orderMapper.insert(order);
        }
        return R.ok(orderMapper.selectVoById(order.getOrderId()));
    }

    /**
     * 关闭未支付订单。
     *
     * @param orderId 充值订单ID
     * @return 操作结果
     */
    @SaCheckPermission("dcs:payment-order:close")
    @Log(title = "充值订单-关闭", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/close")
    public R<Void> close(@PathVariable Long orderId) {
        DcsWalletPaymentOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new ServiceException("订单不存在");
        }
        if (!"PENDING".equals(order.getStatus())) {
            throw new ServiceException("仅待支付订单可关闭");
        }
        order.setStatus("CLOSED");
        orderMapper.updateById(order);
        return R.ok();
    }
}
