package com.smartdock.profile.spi;

import java.util.List;
import java.util.Map;

/**
 * 受限的事件只读查询口——自定义维度策略({@link DimensionStrategy})通过它取数,
 * 引擎不向策略暴露 Mapper/EntityManager 等底层设施。
 *
 * @author smartdock
 */
public interface EventQueryPort {

    /**
     * 计数: 指定事件类型在窗口内发生次数。
     *
     * @param eventType 事件类型(标准词表)
     * @param windowDays 滚动窗口天数; null = 全量
     * @param propFilter props 等值过滤(可空)
     */
    long count(String eventType, Integer windowDays, Map<String, String> propFilter);

    /**
     * 属性均值: AVG((props-&gt;&gt;prop)::numeric)。
     *
     * @return 无样本时返回 null
     */
    Double avgProp(String eventType, String prop, Integer windowDays);

    /**
     * 属性求和: SUM((props-&gt;&gt;prop)::numeric), 空集返回 0。
     */
    Double sumProp(String eventType, String prop, Integer windowDays);

    /**
     * 最近一次事件的 props(按 occurred_time DESC 取第一条)。
     *
     * @return 无事件返回 null
     */
    Map<String, Object> lastEventProps(String eventType);

    /**
     * 窗口内事件列表(props 视图), 最多 limit 条(按时间倒序)——轻量取证据用, 禁当分页使。
     */
    List<Map<String, Object>> recentEvents(String eventType, Integer windowDays, int limit);
}
