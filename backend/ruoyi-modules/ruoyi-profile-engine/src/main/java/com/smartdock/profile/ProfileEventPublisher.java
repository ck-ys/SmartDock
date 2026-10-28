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
 * <p>接入三条红线(违反会静默丢数/错算, 详见 docs/profile-integration.md):
 * ① eventUid 第一段带模块名(如 myapp.order:paid:No1); 同一对象同一动作会发生多次的必须四段式带序号,
 *    否则第二条被幂等键静默吞掉;
 * ② 异步/定时/MQ 线程发事件必须 TenantHelper.dynamic(租户号, ...) 包裹, 否则缺租户上下文整条被拒;
 * ③ props 数值属性必须放 JSON 数字(不是字符串), 布尔放 1/0, 否则算分取数被静默跳过。</p>
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
     * 批量发布并区分「新写入 / 重复 / 逐条失败」——批量造数与导入类功能的对账口径来源。
     * 默认实现退化为 publishBatch(重复数不可知); ProfileEventServiceImpl 提供精确分类。
     */
    default Map<String, Object> publishBatchDetailed(List<ProfileEventBo> events) {
        return Map.of("received", events == null ? 0 : events.size(),
            "written", publishBatch(events == null ? List.of() : events),
            "duplicated", 0, "failures", List.of());
    }
}
