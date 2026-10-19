package com.smartdock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import com.smartdock.domain.DcsAiCreditLedger;
import com.smartdock.domain.DcsAiCreditPlan;
import com.smartdock.domain.DcsAiCreditSubscription;
import com.smartdock.domain.bo.DcsAiCreditPlanBo;
import com.smartdock.service.IDcsAiCreditService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 积分套餐 Controller（五需求批 R5，2026-10-13）。
 *
 * <p>管理端（dcs:aicredit:*）：档位列表/订阅/加油包/流水对账——作用于当前登录租户；
 * 用户端（dcs:assistant:use）：助手输入框积分 pill 的余额快照与近十条消耗，
 * 只回积分语言不回金额（产品口径：积分，1 积分=1 分钱）。</p>
 *
 * @author smartdock
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dcs/ai-credit")
public class DcsAiCreditController extends BaseController {

    private final IDcsAiCreditService creditService;

    /**
     * 档位列表 + 当前租户订阅/用量概览（管理页首屏一次拿全）。
     * 业务批8：附消费方式 + 各档在订租户数（改价影响提示）。
     */
    @SaCheckPermission("dcs:aicredit:list")
    @GetMapping("/plans")
    public R<Map<String, Object>> plans() {
        String tenantId = LoginHelper.getTenantId();
        Map<String, Object> data = new LinkedHashMap<>();
        // step5 P2-5：超管看全量（含下架行，弹窗可再上架）；租户管理员只见在售
        boolean superAdmin = LoginHelper.isSuperAdmin();
        List<DcsAiCreditPlan> plans = superAdmin ? creditService.plansAll() : creditService.plans();
        IDcsAiCreditService.CreditSummary summary = creditService.summary(tenantId);
        data.put("plans", plans);
        data.put("summary", summary);
        data.put("activeSubscription", creditService.activeSubscription(tenantId).orElse(null));
        data.put("consumeMode", creditService.consumeMode(tenantId));
        // dspro P2-3：跨租户在订计数只给超管（普通租管无权看全网档位热度）
        data.put("planActiveCounts", superAdmin ? creditService.activeTenantCountByPlan() : java.util.Map.of());
        return R.ok(data);
    }

    /**
     * 订阅/续订某档位（旧生效行翻 0）。业务批8 Q4：默认从租户钱包扣款（余额不足 409 人话）；
     * grant=true 赠送开通不扣钱，仅超级管理员可用。订阅成功自动切积分消费。
     */
    @SaCheckPermission("dcs:aicredit:subscribe")
    @Log(title = "AI积分-订阅档位", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/subscribe")
    public R<DcsAiCreditSubscription> subscribe(
            @RequestParam @jakarta.validation.constraints.NotBlank(message = "套餐编码不能为空") String planCode,
            @RequestParam(required = false, defaultValue = "false") boolean grant) {
        requireSuperAdminForGrant(grant, "赠送订阅");
        return R.ok(creditService.subscribe(LoginHelper.getTenantId(), planCode, LoginHelper.getUserId(), grant));
    }

    /**
     * 购买加油包（一次性积分余额，不受 5h 窗口限制）；grant 口径同 subscribe
     */
    @SaCheckPermission("dcs:aicredit:subscribe")
    @Log(title = "AI积分-加油包", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/boost")
    public R<Void> boost(
            @RequestParam @jakarta.validation.constraints.NotBlank(message = "套餐编码不能为空") String planCode,
            @RequestParam(required = false, defaultValue = "false") boolean grant) {
        requireSuperAdminForGrant(grant, "赠送加油包");
        creditService.boost(LoginHelper.getTenantId(), planCode, LoginHelper.getUserId(), grant);
        return R.ok();
    }

    /**
     * 消费方式开关（业务批8 Q7 丁）：WALLET=钱包按量（默认）/ CREDIT=积分订阅。
     * 切 CREDIT 前置校验有生效订阅（409 人话）。
     */
    @SaCheckPermission("dcs:aicredit:list")
    @GetMapping("/billing-mode")
    public R<Map<String, Object>> billingMode() {
        String tenantId = LoginHelper.getTenantId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", creditService.consumeMode(tenantId));
        data.put("subscribed", creditService.activeSubscription(tenantId).isPresent());
        return R.ok(data);
    }

    @SaCheckPermission("dcs:aicredit:subscribe")
    @Log(title = "AI积分-切换消费方式", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/billing-mode")
    public R<Void> setBillingMode(@RequestParam String mode) {
        creditService.setConsumeMode(LoginHelper.getTenantId(), mode, LoginHelper.getUserId());
        return R.ok();
    }

    /**
     * 档位新增/编辑（业务批8 Q4：任何参数可改；id 空=新增）。跨租户种子表，仅超管侧授权。
     */
    @SaCheckPermission("dcs:aicredit:plan-edit")
    @Log(title = "AI积分-套餐改价", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/plan/save")
    public R<DcsAiCreditPlan> savePlan(@RequestBody @jakarta.validation.Valid DcsAiCreditPlanBo bo) {
        return R.ok(creditService.savePlan(bo, LoginHelper.getUserId()));
    }

    /** 档位上下架（enabled 1/0） */
    @SaCheckPermission("dcs:aicredit:plan-edit")
    @Log(title = "AI积分-套餐上下架", businessType = BusinessType.UPDATE)
    @PostMapping("/plan/change-status")
    public R<Void> changePlanStatus(@RequestParam Long id, @RequestParam String enabled) {
        creditService.changePlanStatus(id, enabled, LoginHelper.getUserId());
        return R.ok();
    }

    /** grant=true 只放超级管理员（赠送=免费开通，租户管理员自赠=资损） */
    private void requireSuperAdminForGrant(boolean grant, String action) {
        if (grant && !LoginHelper.isSuperAdmin()) {
            throw new org.dromara.common.core.exception.ServiceException(
                action + "仅超级管理员可用，租户管理员请走正常购买（扣租户钱包）。", 403);
        }
    }

    /**
     * 积分流水分页（业务批7 Q4 真分页；业务批8 Q6 双视角）：bizType/beginTime/endTime 可选；
     * onlyMine=true 只看本人消耗（filterUserId=登录人），userId=按人筛选（管理员全景视角）。
     * 行带 operatorName 消耗人昵称。
     */
    @SaCheckPermission("dcs:aicredit:list")
    @GetMapping("/ledger")
    public R<org.dromara.common.mybatis.core.page.TableDataInfo<DcsAiCreditLedger>> ledger(
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String beginTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false, defaultValue = "false") boolean onlyMine,
            @RequestParam(required = false) Long userId,
            org.dromara.common.mybatis.core.page.PageQuery pageQuery) {
        Long filterUserId = onlyMine ? LoginHelper.getUserId() : userId;
        return R.ok(creditService.ledgerPage(LoginHelper.getTenantId(), bizType, beginTime, endTime,
            filterUserId, pageQuery));
    }

    /** 业务批8 Q6：按人筛选下拉——近 90 天有流水的操作人（userId+昵称） */
    @SaCheckPermission("dcs:aicredit:list")
    @GetMapping("/ledger/users")
    public R<List<Map<String, Object>>> ledgerUsers() {
        return R.ok(creditService.ledgerOperators(LoginHelper.getTenantId()));
    }

    /**
     * AI 缺口批 Q7（2026-10-14）：积分流水导出 Excel——与 /ledger 同筛选口径
     * （bizType/beginTime/endTime/onlyMine/userId），上限 1 万行。
     */
    @SaCheckPermission("dcs:aicredit:list")
    @PostMapping("/ledger/export")
    public void exportLedger(jakarta.servlet.http.HttpServletResponse response,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String beginTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false, defaultValue = "false") boolean onlyMine,
            @RequestParam(required = false) Long userId) {
        Long filterUserId = onlyMine ? LoginHelper.getUserId() : userId;
        List<DcsAiCreditLedger> rows = creditService.ledgerList(LoginHelper.getTenantId(),
            bizType, beginTime, endTime, filterUserId);
        List<com.smartdock.domain.vo.DcsAiCreditLedgerExportVo> vos = new java.util.ArrayList<>(rows.size());
        for (DcsAiCreditLedger r : rows) {
            com.smartdock.domain.vo.DcsAiCreditLedgerExportVo vo = new com.smartdock.domain.vo.DcsAiCreditLedgerExportVo();
            vo.setCreateTime(fmtTime(r.getCreateTime()));
            vo.setBizType(ledgerBizLabel(r.getBizType()));
            vo.setCredits("DEDUCT".equals(r.getBizType()) && r.getCredits() != null && r.getCredits() > 0
                ? "-" + r.getCredits() : String.valueOf(r.getCredits() == null ? 0 : r.getCredits()));
            vo.setWeeklyCredits(String.valueOf(r.getWeeklyCredits() == null ? 0 : r.getWeeklyCredits()));
            vo.setTankCredits(String.valueOf(r.getTankCredits() == null ? 0 : r.getTankCredits()));
            vo.setScene(r.getScene() == null ? "" : r.getScene());
            vo.setOperatorName(r.getOperatorName() == null ? "--" : r.getOperatorName());
            vo.setRemark(r.getRemark() == null ? "" : r.getRemark());
            vos.add(vo);
        }
        org.dromara.common.excel.utils.ExcelUtil.exportExcel(vos, "积分流水",
            com.smartdock.domain.vo.DcsAiCreditLedgerExportVo.class, response);
    }

    private static String ledgerBizLabel(String bizType) {
        if (bizType == null) return "--";
        return switch (bizType) {
            case "SUBSCRIBE" -> "订阅";
            case "BOOST" -> "加油包";
            case "DEDUCT" -> "消耗";
            case "ADJUST" -> "调整";
            default -> bizType;
        };
    }

    private static String fmtTime(java.util.Date d) {
        return d == null ? "" : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }


    /** 业务批8 Q2：模型积分倍率表（当前租户在用 CHAT 模型 × 倍率 + 预估积分/轮） */
    @SaCheckPermission("dcs:aicredit:list")
    @GetMapping("/model-rates")
    public R<List<Map<String, Object>>> modelRates() {
        return R.ok(creditService.modelRates(LoginHelper.getTenantId()));
    }

    /**
     * 用户端积分快照（助手输入框 pill）：订阅态+三本账+近十条消耗
     */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/my-summary")
    public R<Map<String, Object>> mySummary() {
        String tenantId = LoginHelper.getTenantId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", creditService.summary(tenantId));
        data.put("recent", creditService.recent(tenantId, 10));
        return R.ok(data);
    }
}
