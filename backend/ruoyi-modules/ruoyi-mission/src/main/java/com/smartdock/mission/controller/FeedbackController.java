package com.smartdock.mission.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.mission.domain.Feedback;
import com.smartdock.mission.domain.bo.FeedbackBo;
import com.smartdock.mission.domain.bo.FeedbackHandleBo;
import com.smartdock.mission.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 产品反馈（N3 2026-10-23）。
 *
 * <p>全员可提交与查看自己的反馈；管理端列表/处理需要反馈中心权限。
 * 写操作统一 POST（与全站 WAF 口径一致）。</p>
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/mission/feedback")
@RequiredArgsConstructor
public class FeedbackController extends BaseController {

    private final FeedbackService feedbackService;

    /** 提交反馈（全员）。 */
    @SaCheckLogin
    @RepeatSubmit
    @PostMapping
    public R<Long> submit(@Validated(FeedbackBo.Submit.class) @RequestBody FeedbackBo bo) {
        return R.ok(feedbackService.submit(bo));
    }

    /** 我的反馈（只看自己的）。 */
    @SaCheckLogin
    @GetMapping("/my")
    public TableDataInfo<Feedback> my(FeedbackBo bo, PageQuery pq) {
        return feedbackService.myPage(bo, pq, LoginHelper.getUserId());
    }

    /** 我的反馈四态计数。 */
    @SaCheckLogin
    @GetMapping("/my/summary")
    public R<Map<String, Long>> mySummary() {
        return R.ok(feedbackService.mySummary(LoginHelper.getUserId()));
    }

    /** 管理端列表（本租户）。 */
    @SaCheckPermission("smartdock:feedback:list")
    @GetMapping("/list")
    public TableDataInfo<Feedback> list(FeedbackBo bo, PageQuery pq) {
        return feedbackService.adminPage(bo, pq);
    }

    /** 管理端四态计数。 */
    @SaCheckPermission("smartdock:feedback:list")
    @GetMapping("/status-summary")
    public R<Map<String, Long>> statusSummary() {
        return R.ok(feedbackService.statusSummary());
    }

    /** 详情。 */
    @SaCheckPermission("smartdock:feedback:query")
    @GetMapping("/{feedbackId}")
    public R<Feedback> getInfo(@PathVariable Long feedbackId) {
        return R.ok(feedbackService.getById(feedbackId));
    }

    /** 处理（四态流转 + 通知提交人）。 */
    @SaCheckPermission("smartdock:feedback:handle")
    @PostMapping("/handle")
    public R<Void> handle(@Validated @RequestBody FeedbackHandleBo bo) {
        feedbackService.handle(bo);
        return R.ok();
    }
}
