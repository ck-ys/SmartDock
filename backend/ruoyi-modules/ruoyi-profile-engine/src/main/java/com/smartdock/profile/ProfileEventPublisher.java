package com.smartdock.profile;

import com.smartdock.profile.event.ProfileEventBo;

import java.util.List;
import java.util.Map;

/**
 * 画像事件写入门面——任何业务模块依赖本接口即可喂数(唯一入口)。
 *
 * <p>语义: append-only + 幂等。同一 (tenant, source, event_uid) 重复投递返回 false, 不报错。
 * 派生属性(responseHours/qualityScore 等)由业务写入前算好放 props, 引擎不做跨事件 join。</p>
 *
 * @author smartdock
 */
public interface ProfileEventPublisher {

    /**
     * 单条发布。
     *
     * @return true = 新写入; false = 重复(event_uid 已存在), 幂等跳过
     */
    boolean publish(ProfileEventBo event);

    /**
     * 批量发布(逐条幂等, 不整体事务——事件允许部分成功, 自然键保证可重放补齐)。
     *
     * @return 新写入条数
     */
    int publishBatch(List<ProfileEventBo> events);

    /**
     * 批量发布并区分「新写入 / 重复 / 逐条失败」——采集质量报表的口径来源。
     * 默认实现退化为 publishBatch(重复数不可知); ProfileEventServiceImpl 提供精确分类。
     */
    default Map<String, Object> publishBatchDetailed(List<ProfileEventBo> events) {
        return Map.of("received", events == null ? 0 : events.size(),
            "written", publishBatch(events == null ? List.of() : events),
            "duplicated", 0, "failures", List.of());
    }
}
