package com.smartdock.mission.controller;

import org.dromara.common.satoken.utils.LoginHelper;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.mission.domain.Submission;
import com.smartdock.mission.service.SubmissionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提交接口(端点组 6-7)。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/submission")
@RequiredArgsConstructor
public class SubmissionController extends BaseController {

    private final SubmissionService submissionService;

    @SaCheckPermission("smartdock:submission:submit")
    @PostMapping("/submit")
    public R<Submission> submit(@RequestBody SubmitBo bo) {
        return R.ok(submissionService.submit(bo.getTaskId(), bo.getData(), LoginHelper.getUserId()));
    }

    @SaCheckPermission("smartdock:submission:submit")
    @GetMapping("/my")
    public TableDataInfo<Submission> my(@RequestParam(required = false) Long taskId, PageQuery pq) {
        return submissionService.mySubmissions(taskId, pq, LoginHelper.getUserId());
    }

    @Data
    public static class SubmitBo {
        private Long taskId;
        private Map<String, Object> data;
    }
}
