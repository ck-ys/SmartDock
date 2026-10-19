package com.smartdock.dcs.controller;

import org.dromara.common.satoken.utils.LoginHelper;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.dcs.domain.DcsSubmission;
import com.smartdock.dcs.domain.DcsTask;
import com.smartdock.dcs.domain.bo.DcsTaskBo;
import com.smartdock.dcs.service.DcsSubmissionService;
import com.smartdock.dcs.service.DcsTaskService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务接口(发布人管理 + 成员视角, design-mini-core §6 端点组 1-5)。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/dcs/task")
@RequiredArgsConstructor
public class DcsTaskController extends BaseController {

    private final DcsTaskService taskService;
    private final DcsSubmissionService submissionService;

    /** 发布人任务管理页 */
    @SaCheckPermission("dcs:task:list")
    @GetMapping("/page")
    public TableDataInfo<DcsTask> page(String title, String status, PageQuery pq) {
        return taskService.page(title, status, pq, LoginHelper.getUserId());
    }

    @SaCheckPermission("dcs:task:add")
    @PostMapping
    public R<DcsTask> create(@RequestBody DcsTaskBo bo) {
        return R.ok(taskService.create(bo, LoginHelper.getUserId()));
    }

    @SaCheckPermission("dcs:task:edit")
    @PutMapping
    public R<Void> update(@RequestBody DcsTaskBo bo) {
        taskService.update(bo, LoginHelper.getUserId());
        return R.ok();
    }

    @SaCheckPermission("dcs:task:publish")
    @PostMapping("/{id}/publish")
    public R<Void> publish(@PathVariable Long id) {
        taskService.publish(id, LoginHelper.getUserId());
        return R.ok();
    }

    @SaCheckPermission("dcs:task:publish")
    @PostMapping("/{id}/close")
    public R<Void> close(@PathVariable Long id) {
        taskService.close(id, LoginHelper.getUserId());
        return R.ok();
    }

    /** 字段定义(随任务读; 保存走 create/update 整体提交) */
    @SaCheckPermission("dcs:task:list")
    @GetMapping("/{id}/fields")
    public R<?> fields(@PathVariable Long id) {
        taskService.requireVisible(id, LoginHelper.getUserId());
        return R.ok(taskService.fieldsOf(id));
    }

    /** 成员: 我的任务 */
    @SaCheckPermission("dcs:submission:submit")
    @GetMapping("/my")
    public TableDataInfo<DcsTask> my(PageQuery pq) {
        return taskService.myTasks(pq, LoginHelper.getUserId());
    }

    /** 详情: 任务 + 字段 + 我的最新提交状态 */
    @SaCheckPermission("dcs:submission:submit")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        DcsTask t = taskService.requireVisible(id, LoginHelper.getUserId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", t);
        out.put("fields", taskService.fieldsOf(id));
        DcsSubmission mine = submissionService.latestOf(id, LoginHelper.getUserId());
        out.put("mySubmission", mine);
        return R.ok(out);
    }
}
