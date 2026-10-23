package com.smartdock.dcs.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.dcs.domain.DcsFeedback;
import com.smartdock.dcs.domain.bo.DcsFeedbackBo;
import com.smartdock.dcs.domain.bo.DcsFeedbackHandleBo;
import com.smartdock.dcs.service.DcsFeedbackService;
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
@RequestMapping("/dcs/feedback")
@RequiredArgsConstructor
public class DcsFeedbackController extends BaseController {

    private final DcsFeedbackService feedbackService;

    /** 提交反馈（全员）。 */
    @SaCheckLogin
    @RepeatSubmit
    @PostMapping
    public R<Long> submit(@Validated(DcsFeedbackBo.Submit.class) @RequestBody DcsFeedbackBo bo) {
        return R.ok(feedbackService.submit(bo));
    }

    /** 我的反馈（只看自己的）。 */
    @SaCheckLogin
    @GetMapping("/my")
    public TableDataInfo<DcsFeedback> my(DcsFeedbackBo bo, PageQuery pq) {
        return feedbackService.myPage(bo, pq, LoginHelper.getUserId());
    }

    /** 我的反馈四态计数。 */
    @SaCheckLogin
    @GetMapping("/my/summary")
    public R<Map<String, Long>> mySummary() {
        return R.ok(feedbackService.mySummary(LoginHelper.getUserId()));
    }

    /** 管理端列表（本租户）。 */
    @SaCheckPermission("dcs:feedback:list")
    @GetMapping("/list")
    public TableDataInfo<DcsFeedback> list(DcsFeedbackBo bo, PageQuery pq) {
        return feedbackService.adminPage(bo, pq);
    }

    /** 管理端四态计数。 */
    @SaCheckPermission("dcs:feedback:list")
    @GetMapping("/status-summary")
    public R<Map<String, Long>> statusSummary() {
        return R.ok(feedbackService.statusSummary());
    }

    /** 详情。 */
    @SaCheckPermission("dcs:feedback:query")
    @GetMapping("/{feedbackId}")
    public R<DcsFeedback> getInfo(@PathVariable Long feedbackId) {
        return R.ok(feedbackService.getById(feedbackId));
    }

    /** 处理（四态流转 + 通知提交人）。 */
    @SaCheckPermission("dcs:feedback:handle")
    @PostMapping("/handle")
    public R<Void> handle(@Validated @RequestBody DcsFeedbackHandleBo bo) {
        feedbackService.handle(bo);
        return R.ok();
    }
}
