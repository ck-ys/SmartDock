package com.smartdock.mission.controller;

import org.dromara.common.satoken.utils.LoginHelper;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.mission.domain.Audit;
import com.smartdock.mission.domain.Submission;
import com.smartdock.mission.service.AuditService;
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

import java.util.List;

/**
 * 审核接口(端点组 8-9)。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController extends BaseController {

    private final AuditService auditService;

    @SaCheckPermission("smartdock:audit:audit")
    @GetMapping("/pending")
    public TableDataInfo<Submission> pending(PageQuery pq) {
        return auditService.pending(pq, LoginHelper.getUserId());
    }

    @SaCheckPermission("smartdock:audit:audit")
    @PostMapping("/decide")
    public R<Void> decide(@RequestBody DecideBo bo) {
        auditService.decide(bo.getSubmissionId(), bo.getDecision(), bo.getComment(), LoginHelper.getUserId());
        return R.ok();
    }

    // 轨迹查看方=审核人/发布人/提交人三方, 锚到成员也有的提交权限, 由服务层做涉及方鉴权
    @SaCheckPermission("smartdock:submission:submit")
    @GetMapping("/trail")
    public R<List<Audit>> trail(@RequestParam Long submissionId) {
        return R.ok(auditService.trailOf(submissionId, LoginHelper.getUserId()));
    }

    @Data
    public static class DecideBo {
        private Long submissionId;
        private String decision;
        private String comment;
    }
}
