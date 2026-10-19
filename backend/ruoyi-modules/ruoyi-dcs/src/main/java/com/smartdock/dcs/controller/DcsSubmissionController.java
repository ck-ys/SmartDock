package com.smartdock.dcs.controller;

import org.dromara.common.satoken.utils.LoginHelper;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.dcs.domain.DcsSubmission;
import com.smartdock.dcs.service.DcsSubmissionService;
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
@RequestMapping("/dcs/submission")
@RequiredArgsConstructor
public class DcsSubmissionController extends BaseController {

    private final DcsSubmissionService submissionService;

    @SaCheckPermission("dcs:submission:submit")
    @PostMapping("/submit")
    public R<DcsSubmission> submit(@RequestBody SubmitBo bo) {
        return R.ok(submissionService.submit(bo.getTaskId(), bo.getData(), LoginHelper.getUserId()));
    }

    @SaCheckPermission("dcs:submission:submit")
    @GetMapping("/my")
    public TableDataInfo<DcsSubmission> my(@RequestParam(required = false) Long taskId, PageQuery pq) {
        return submissionService.mySubmissions(taskId, pq, LoginHelper.getUserId());
    }

    @Data
    public static class SubmitBo {
        private Long taskId;
        private Map<String, Object> data;
    }
}
