package com.smartdock.analytics.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.analytics.domain.AnalyticsReplayTask;
import com.smartdock.analytics.engine.AnalyticsReplayService;
import com.smartdock.analytics.query.AnalyticsReplayBo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 事件回放接口(批10 Q3-A): 发起/进度/断点续跑/暂停, 全部受权限保护。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/analytics/replay")
@RequiredArgsConstructor
public class AnalyticsReplayController {

    private final AnalyticsReplayService replayService;

    /**
     * 发起回放(异步执行, 立即返回任务行, 进度经 /list 或 /{id} 轮询)。
     */
    @SaCheckPermission("profile:replay:edit")
    @Log(title = "事件回放", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<AnalyticsReplayTask> start(@Valid @RequestBody AnalyticsReplayBo bo) {
        return R.ok(replayService.start(bo));
    }

    @SaCheckPermission("profile:replay:query")
    @GetMapping("/list")
    public R<List<AnalyticsReplayTask>> list(@RequestParam(required = false) String source) {
        return R.ok(replayService.list(source));
    }

    @SaCheckPermission("profile:replay:query")
    @GetMapping("/{replayId}")
    public R<AnalyticsReplayTask> get(@PathVariable Long replayId) {
        return R.ok(replayService.get(replayId));
    }

    /**
     * 断点续跑: 从 current_day 继续; 僵尸 RUNNING(30 分钟无进度)可拉起, 活任务拒绝双跑。
     */
    @SaCheckPermission("profile:replay:edit")
    @Log(title = "事件回放", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{replayId}/resume")
    public R<AnalyticsReplayTask> resume(@PathVariable Long replayId) {
        return R.ok(replayService.resume(replayId));
    }

    @SaCheckPermission("profile:replay:edit")
    @Log(title = "事件回放", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{replayId}/pause")
    public R<AnalyticsReplayTask> pause(@PathVariable Long replayId) {
        return R.ok(replayService.pause(replayId));
    }
}
