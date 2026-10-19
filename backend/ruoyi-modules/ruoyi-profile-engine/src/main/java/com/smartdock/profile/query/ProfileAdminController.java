package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.engine.ProfileRecalcBatch;
import com.smartdock.profile.engine.ProfileRecalcService;
import com.smartdock.profile.mapper.ProfileRecalcBatchMapper;
import com.smartdock.profile.event.ProfileEventBo;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
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

    private final ProfileEventPublisher eventPublisher;
    private final ProfileRecalcService recalcService;
    private final ProfileRecalcBatchMapper batchMapper;

    /**
     * 事件灌数(数组): 逐条幂等, 返回新写入条数。
     */
    @SaCheckPermission("profile:event:publish")
    @PostMapping("/event/publish")
    public R<Map<String, Object>> publish(@RequestBody List<ProfileEventBo> events) {
        int written = eventPublisher.publishBatch(events);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", events.size());
        out.put("written", written);
        out.put("duplicated", events.size() - written);
        return R.ok(out);
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
}
