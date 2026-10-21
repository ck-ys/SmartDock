package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.engine.ProfileRecalcBatch;
import com.smartdock.profile.engine.ProfileRecalcService;
import com.smartdock.profile.event.ProfileEventBo;
import com.smartdock.profile.event.ProfileEventServiceImpl;
import com.smartdock.profile.mapper.ProfileRecalcBatchMapper;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画像管理接口: 事件灌数(演示/运维) + 重算触发/进度。
 *
 * <p>业务模块喂数走代码侧 {@link ProfileEventPublisher}, 不该走本 HTTP 口;
 * 本口留给灌数脚本与人工排查。</p>
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileAdminController {

    private final ProfileEventServiceImpl eventService;
    private final ProfileRecalcService recalcService;
    private final ProfileRecalcBatchMapper batchMapper;

    /**
     * 事件灌数(数组): 逐条幂等, 返回写入/重复/校验失败三类结果。
     */
    @SaCheckPermission("profile:event:publish")
    @PostMapping("/event/publish")
    public R<Map<String, Object>> publish(@RequestBody List<ProfileEventBo> events) {
        return R.ok(eventService.publishBatchDetailed(events));
    }

    /**
     * 触发全量重算(同步执行; 大租户耗时可观, 客户端注意超时)。
     */
    @SaCheckPermission("profile:recalc:run")
    @PostMapping("/recalc/{source}")
    public R<ProfileRecalcBatch> recalc(@PathVariable String source) {
        return R.ok(recalcService.startAndRun(source));
    }

    /**
     * 批次进度(亦可作为断点续跑入口的查询面)。
     */
    @SaCheckPermission("profile:recalc:run")
    @GetMapping("/recalc/{batchId}")
    public R<ProfileRecalcBatch> batch(@PathVariable Long batchId) {
        return R.ok(batchMapper.selectById(batchId));
    }

    /**
     * 重算批次断点续跑(批10 Q3: 方法早已有, 这里补受权限保护的 HTTP 入口)。
     * RUNNING 中拒绝并发续跑; 进程崩溃遗留的 FAILED/PARTIAL 可从 last_subject_id 续。
     */
    @SaCheckPermission("profile:recalc:run")
    @Log(title = "画像重算", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/recalc/{batchId}/resume")
    public R<ProfileRecalcBatch> resume(@PathVariable Long batchId) {
        return R.ok(recalcService.resume(batchId));
    }
}
