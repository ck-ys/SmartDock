package com.smartdock.risk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import com.smartdock.risk.domain.DcsProfileReview;
import com.smartdock.risk.domain.DcsRiskAction;
import com.smartdock.risk.domain.bo.RiskBatchReviewCommand;
import com.smartdock.risk.domain.bo.RiskReviewCommand;
import com.smartdock.risk.domain.bo.RiskUserReviewCommand;
import com.smartdock.risk.domain.vo.ProfileReviewAggregateVo;
import com.smartdock.risk.domain.vo.ProfileReviewVo;
import com.smartdock.risk.domain.vo.RiskActionVo;
import com.smartdock.risk.service.ProfileRiskReviewService;
import com.smartdock.risk.service.RiskActionService;
import com.smartdock.risk.support.RiskUserEnricher;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 风控处置与画像复核 Controller（从 DcsRiskController 拆出）。
 *
 * <p>职责：
 * <ul>
 *   <li>L3/L4 安全处置（自动处置）：列表 / 详情 / 单条解除 / 单条复核 / 批量解除 / 批量复核</li>
 *   <li>画像风险复核队列：列表 / 单条复核 / 批量复核</li>
 * </ul>
 *
 * <p>两类业务隔离：安全处置走安全评分链路（含 IP/验证码/冻结等敏感证据），
 * 画像复核走业务画像评分链路（仅业务风险，无安全证据）。
 *
 * <p>路由前缀 {@code /dcs/risk} 与拆分前保持一致，前端零改动。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/dcs/risk")
@RequiredArgsConstructor
public class DcsRiskActionController extends BaseController {

    /** 风险处置业务逻辑服务（L3/L4 安全处置列表/解除/复核） */
    private final RiskActionService riskActionService;
    /** 画像风险复核业务服务（复核队列与批量复核） */
    private final ProfileRiskReviewService profileRiskReviewService;
    /** 风险数据用户名补全器（VO 填充 userName/nickName） */
    private final RiskUserEnricher userEnricher;

    // ============ 自动处置（L3/L4 安全处置） ============

    /**
     * 分页查询安全处置列表。
     *
     * @param status     状态筛选：{@code ACTIVE} / {@code EXPIRED} / {@code RELEASED}，可选
     * @param actionType 处置类型：{@code ENHANCED_VERIFICATION} / {@code TEMP_FREEZE}，可选
     * @param userId     用户 ID，可选
     * @param pageQuery  分页参数
     * @return 分页的安全处置列表
     */
    @SaCheckPermission("dcs:risk:action:list")
    @GetMapping("/actions")
    public TableDataInfo<RiskActionVo> actions(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String actionType,
        @RequestParam(required = false) Long userId,
        PageQuery pageQuery
    ) {
        var page = riskActionService.page(LoginHelper.getTenantId(), status, actionType, userId, pageQuery);
        List<RiskActionVo> rows = page.getRecords().stream().map(this::toActionVo).toList();
        return TableDataInfo.build(new Page<RiskActionVo>(
            page.getCurrent(), page.getSize(), page.getTotal()).setRecords(rows));
    }

    /**
     * 查询单条处置详情。
     *
     * @param actionId 处置 ID
     * @return 处置详情业务数据
     */
    @SaCheckPermission("dcs:risk:action:list")
    @GetMapping("/actions/{actionId}")
    public R<RiskActionVo> actionDetail(@PathVariable Long actionId) {
        return R.ok(toActionVo(riskActionService.detail(LoginHelper.getTenantId(), actionId)));
    }

    /**
     * 解除单条生效中的处置（管理员人工干预）。
     *
     * <p>仅 ACTIVE 状态可解除；同时若有待复核（PENDING），会一并标记为 DISMISSED。
     *
     * @param actionId 处置 ID
     * @param command  操作理由（必填，留痕）
     * @return 操作结果
     */
    @SaCheckPermission("dcs:risk:action:release")
    @Log(title = "风控处置解除", businessType = BusinessType.UPDATE)
    @PostMapping("/actions/{actionId}/release")
    public R<Void> releaseAction(@PathVariable Long actionId, @Valid @RequestBody RiskReviewCommand command) {
        riskActionService.release(LoginHelper.getTenantId(), actionId, LoginHelper.getUserId(), command.reason());
        return R.ok();
    }

    /**
     * 复核单条处置（CONFIRMED 确认 / DISMISSED 驳回）。
     *
     * @param actionId 处置 ID
     * @param command  复核指令（conclusion + reason）
     * @return 操作结果
     */
    @SaCheckPermission("dcs:risk:review")
    @Log(title = "风控处置复核", businessType = BusinessType.UPDATE)
    @PostMapping("/actions/{actionId}/review")
    public R<Void> reviewAction(@PathVariable Long actionId, @Valid @RequestBody RiskReviewCommand command) {
        riskActionService.review(LoginHelper.getTenantId(), actionId, LoginHelper.getUserId(), command.conclusion(), command.reason());
        return R.ok();
    }

    /**
     * 批量解除处置（B 档新增）。
     *
     * <p>部分失败聚合消息返回，不回滚已成功的。详见 {@link RiskActionService#batchRelease}。
     *
     * @param command 批量解除指令（ids + reason）
     * @return 聚合结果消息（如 "3 条处理成功，1 条失败：..."）
     */
    @SaCheckPermission("dcs:risk:action:release")
    @Log(title = "风控处置批量解除", businessType = BusinessType.UPDATE)
    @PostMapping("/actions/batch-release")
    public R<String> batchReleaseAction(@Valid @RequestBody RiskBatchReviewCommand command) {
        String msg = riskActionService.batchRelease(
            LoginHelper.getTenantId(), command.getIds(), LoginHelper.getUserId(), command.getReason());
        return R.ok(msg);
    }

    /**
     * 批量复核处置（B 档新增）。语义同 {@link #batchReleaseAction}，部分失败聚合消息。
     *
     * @param command 批量复核指令（ids + conclusion + reason）
     * @return 批量操作受理结果说明
     */
    @SaCheckPermission("dcs:risk:review")
    @Log(title = "风控处置批量复核", businessType = BusinessType.UPDATE)
    @PostMapping("/actions/batch-review")
    public R<String> batchReviewAction(@Valid @RequestBody RiskBatchReviewCommand command) {
        String msg = riskActionService.batchReview(
            LoginHelper.getTenantId(), command.getIds(), LoginHelper.getUserId(), command.getConclusion(), command.getReason());
        return R.ok(msg);
    }

    // ============ 画像风险复核 ============

    /**
     * 分页查询画像复核队列。
     *
     * @param status    状态：{@code PENDING} / {@code CONFIRMED} / {@code DISMISSED}，可选
     * @param userId    用户 ID，可选
     * @param pageQuery 分页参数
     * @return 分页的画像复核队列列表
     */
    @SaCheckPermission("dcs:profile:review")
    @GetMapping("/profile-reviews")
    public TableDataInfo<ProfileReviewVo> profileReviews(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Long userId,
        PageQuery pageQuery
    ) {
        var page = profileRiskReviewService.page(LoginHelper.getTenantId(), status, userId, pageQuery);
        List<ProfileReviewVo> rows = page.getRecords().stream().map(this::toProfileReviewVo).toList();
        return TableDataInfo.build(new Page<ProfileReviewVo>(
            page.getCurrent(), page.getSize(), page.getTotal()).setRecords(rows));
    }

    /**
     * 复核单条画像风险（CONFIRMED / DISMISSED）。
     *
     * @param reviewId 复核 ID
     * @param command  复核指令（conclusion + reason）
     * @return 操作结果
     */
    @SaCheckPermission("dcs:profile:review")
    @Log(title = "画像风险复核", businessType = BusinessType.UPDATE)
    @PostMapping("/profile-reviews/{reviewId}/review")
    public R<Void> reviewProfile(@PathVariable Long reviewId, @Valid @RequestBody RiskReviewCommand command) {
        profileRiskReviewService.review(LoginHelper.getTenantId(), reviewId, LoginHelper.getUserId(), command.conclusion(), command.reason());
        return R.ok();
    }

    /**
     * 批量复核画像风险（B 档新增）。部分失败聚合消息返回。
     *
     * @param command 批量复核指令（ids + conclusion + reason）
     * @return 批量操作受理结果说明
     */
    @SaCheckPermission("dcs:profile:review")
    @Log(title = "画像风险批量复核", businessType = BusinessType.UPDATE)
    @PostMapping("/profile-reviews/batch-review")
    public R<String> batchReviewProfile(@Valid @RequestBody RiskBatchReviewCommand command) {
        String msg = profileRiskReviewService.batchReview(
            LoginHelper.getTenantId(), command.getIds(), LoginHelper.getUserId(), command.getConclusion(), command.getReason());
        return R.ok(msg);
    }

    /**
     * 聚合分页查询待复核画像(2026-08-23 复核页主视图数据源)。
     *
     * <p>每用户一行: 最新快照字段 + 积压快照数(pendingCount) + 首末入队时间;
     * 入队语义已改为同人仅一条 PENDING, pendingCount&gt;1 仅为迁移过渡期的历史残留;
     * userId 可选, 用于按人精确查找。</p>
     *
     * @param userId 用户 ID（可选，按人精确查找）
     * @param pageQuery 分页参数
     * @return 分页的待复核画像聚合列表
     */
    @SaCheckPermission("dcs:profile:review")
    @GetMapping("/profile-reviews/aggregate")
    public TableDataInfo<ProfileReviewAggregateVo> profileReviewAggregate(
        @RequestParam(required = false) Long userId,
        PageQuery pageQuery
    ) {
        var page = profileRiskReviewService.pageAggregate(LoginHelper.getTenantId(), userId, pageQuery);
        page.getRecords().forEach(vo -> userEnricher.fillUserName(vo.getUserId(), vo::setUserName, vo::setNickName));
        return TableDataInfo.build(page);
    }

    /**
     * 按用户复核全部待复核画像(CONFIRMED/DISMISSED), 一键消化同人积压(2026-08-23)。
     *
     * @param command 按人复核指令（userId + conclusion + reason）
     * @return 聚合消息(如 "3 条画像已复核")
     */
    @SaCheckPermission("dcs:profile:review")
    @Log(title = "画像风险按人复核", businessType = BusinessType.UPDATE)
    @PostMapping("/profile-reviews/review-user")
    public R<String> reviewProfileByUser(@Valid @RequestBody RiskUserReviewCommand command) {
        long rows = profileRiskReviewService.reviewAllForUser(
            LoginHelper.getTenantId(), command.userId(), LoginHelper.getUserId(),
            command.conclusion(), command.reason());
        return R.ok(rows + " 条画像已复核");
    }

    // ============ 私有 helper ============

    private RiskActionVo toActionVo(DcsRiskAction action) {
        RiskActionVo vo = new RiskActionVo();
        BeanUtils.copyProperties(action, vo);
        vo.setEnforcementEnabled(riskActionService.isEnforcementEnabled());
        userEnricher.fillUserName(action.getUserId(), vo::setUserName, vo::setNickName);
        return vo;
    }

    private ProfileReviewVo toProfileReviewVo(DcsProfileReview review) {
        ProfileReviewVo vo = new ProfileReviewVo();
        BeanUtils.copyProperties(review, vo);
        userEnricher.fillUserName(review.getUserId(), vo::setUserName, vo::setNickName);
        return vo;
    }
}
