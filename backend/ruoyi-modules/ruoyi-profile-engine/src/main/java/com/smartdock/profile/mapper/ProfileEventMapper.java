package com.smartdock.profile.mapper;

import org.apache.ibatis.annotations.Delete;
import com.smartdock.profile.event.ProfileEvent;
import com.smartdock.profile.event.ProfileSubject;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 画像事件 Mapper: 幂等写入 + 单表聚合(算子族的全部 SQL 形态)。
 *
 * <p>聚合方法不含 tenant 条件——TenantLine 拦截器自动追加;
 * props 键以 ${} 内嵌的仅有 filterKey/prop/dedupKey 三处, 调用方(DimensionEvaluator)
 * 已用 PROP_KEY 白名单校验(^[A-Za-z][A-Za-z0-9_]{0,62}$, 首字符须字母——dspro P2-5
 * 勘误: 此前注释写 [A-Za-z0-9_] 与实现不符), 不接受外部输入直接进 ${}。</p>
 *
 * @author smartdock
 */
@Mapper
public interface ProfileEventMapper extends BaseMapperPlus<ProfileEvent, ProfileEvent> {

    /**
     * 幂等写入: 唯一约束 (tenant_id, source, event_uid) 冲突时整行跳过。
     *
     * @return 1=新写入, 0=重复
     */
    @Insert("""
        INSERT INTO profile_event
            (event_id, tenant_id, source, event_type, event_name, event_uid, subject_type, subject_id,
             anonymous_id, session_id, schema_version, occurred_time, props, context, traits, identifiers,
             create_time)
        VALUES
            (#{e.eventId}, #{e.tenantId}, #{e.source}, #{e.eventType}, #{e.eventName}, #{e.eventUid},
             #{e.subjectType}, #{e.subjectId}, #{e.anonymousId}, #{e.sessionId}, #{e.schemaVersion},
             #{e.occurredTime}, #{e.props}::jsonb, #{e.context}::jsonb, #{e.traits}::jsonb,
             #{e.identifiers}::jsonb, CURRENT_TIMESTAMP)
        ON CONFLICT (tenant_id, source, event_uid) DO NOTHING
        """)
    int insertIgnore(@Param("e") ProfileEvent e);

    /**
     * 主体登记 upsert: 首末事件时间取 min/max, 计数 +1(仅事件新写入后调用)。
     * properties 做 JSONB 浅合并, 新 traits 覆盖旧同名键。
     */
    @Insert("""
        INSERT INTO profile_subject
            (id, tenant_id, source, subject_type, subject_id, display_name,
             first_event_time, last_event_time, event_count, properties, create_time)
        VALUES
            (#{s.id}, #{s.tenantId}, #{s.source}, #{s.subjectType}, #{s.subjectId}, #{s.displayName},
             #{s.firstEventTime}, #{s.lastEventTime}, 1, #{s.properties}::jsonb, CURRENT_TIMESTAMP)
        ON CONFLICT (tenant_id, source, subject_type, subject_id) DO UPDATE SET
            last_event_time  = GREATEST(profile_subject.last_event_time, EXCLUDED.last_event_time),
            first_event_time = LEAST(profile_subject.first_event_time, EXCLUDED.first_event_time),
            event_count      = profile_subject.event_count + 1,
            display_name     = COALESCE(EXCLUDED.display_name, profile_subject.display_name),
            properties       = profile_subject.properties || EXCLUDED.properties,
            update_time      = CURRENT_TIMESTAMP
        """)
    int upsertSubject(@Param("s") ProfileSubject s);

    // ------------------------------------------------------------ 算子聚合

    /**
     * COUNT(可选 props 等值过滤)。
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM profile_event
        WHERE source = #{source} AND subject_type = #{subjectType} AND subject_id = #{subjectId}
          AND event_type = #{eventType}
          <if test="windowStart != null">AND occurred_time &gt;= #{windowStart}</if>
          <if test="filterKey != null and filterVal != null">AND props-&gt;&gt;'${filterKey}' = #{filterVal}</if>
        </script>
        """)
    long countEvents(@Param("source") String source, @Param("subjectType") String subjectType,
                     @Param("subjectId") String subjectId, @Param("eventType") String eventType,
                     @Param("windowStart") Date windowStart,
                     @Param("filterKey") String filterKey, @Param("filterVal") String filterVal);

    /**
     * COUNT_DISTINCT(按 props 去重键, 防刷: 同一任务多次催动只计一次)。
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT props-&gt;&gt;'${dedupKey}') FROM profile_event
        WHERE source = #{source} AND subject_type = #{subjectType} AND subject_id = #{subjectId}
          AND event_type = #{eventType}
          <if test="windowStart != null">AND occurred_time &gt;= #{windowStart}</if>
        </script>
        """)
    long countDistinct(@Param("source") String source, @Param("subjectType") String subjectType,
                       @Param("subjectId") String subjectId, @Param("eventType") String eventType,
                       @Param("windowStart") Date windowStart, @Param("dedupKey") String dedupKey);

    /**
     * SUM((props-&gt;&gt;prop)::numeric), 空集 0。数值正则守卫(dspro P1-2): 脏字符串行
     * (如 "N/A")跳过不计, 不让 22P02 炸整条聚合。
     */
    @Select("""
        <script>
        SELECT COALESCE(SUM(CASE WHEN props-&gt;&gt;'${prop}' ~ '^[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?$' THEN (props-&gt;&gt;'${prop}')::numeric END), 0) FROM profile_event
        WHERE source = #{source} AND subject_type = #{subjectType} AND subject_id = #{subjectId}
          AND event_type = #{eventType}
          <if test="windowStart != null">AND occurred_time &gt;= #{windowStart}</if>
        </script>
        """)
    Double sumProp(@Param("source") String source, @Param("subjectType") String subjectType,
                   @Param("subjectId") String subjectId, @Param("eventType") String eventType,
                   @Param("windowStart") Date windowStart, @Param("prop") String prop);

    /**
     * AVG((props-&gt;&gt;prop)::numeric), 无样本返回 null(触发 missing 策略)。同款正则守卫。
     */
    @Select("""
        <script>
        SELECT AVG(CASE WHEN props-&gt;&gt;'${prop}' ~ '^[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?$' THEN (props-&gt;&gt;'${prop}')::numeric END) FROM profile_event
        WHERE source = #{source} AND subject_type = #{subjectType} AND subject_id = #{subjectId}
          AND event_type = #{eventType}
          <if test="windowStart != null">AND occurred_time &gt;= #{windowStart}</if>
        </script>
        """)
    Double avgProp(@Param("source") String source, @Param("subjectType") String subjectType,
                   @Param("subjectId") String subjectId, @Param("eventType") String eventType,
                   @Param("windowStart") Date windowStart, @Param("prop") String prop);

    /**
     * LAST_VALUE: 最近一次事件的指定属性(状态型维度), 无事件返回 null。
     */
    @Select("""
        <script>
        SELECT props-&gt;&gt;'${prop}' FROM profile_event
        WHERE source = #{source} AND subject_type = #{subjectType} AND subject_id = #{subjectId}
          AND event_type = #{eventType}
          <if test="windowStart != null">AND occurred_time &gt;= #{windowStart}</if>
        ORDER BY occurred_time DESC LIMIT 1
        </script>
        """)
    String lastValueProp(@Param("source") String source, @Param("subjectType") String subjectType,
                         @Param("subjectId") String subjectId, @Param("eventType") String eventType,
                         @Param("windowStart") Date windowStart, @Param("prop") String prop);

    /**
     * 最近事件 props 整体(LAST_VALUE/策略取证据用)。
     */
    @Select("""
        <script>
        SELECT props::text FROM profile_event
        WHERE source = #{source} AND subject_type = #{subjectType} AND subject_id = #{subjectId}
          AND event_type = #{eventType}
          <if test="windowStart != null">AND occurred_time &gt;= #{windowStart}</if>
        ORDER BY occurred_time DESC LIMIT 1
        </script>
        """)
    String lastEventPropsJson(@Param("source") String source, @Param("subjectType") String subjectType,
                              @Param("subjectId") String subjectId, @Param("eventType") String eventType,
                              @Param("windowStart") Date windowStart);

    /**
     * 窗口内事件 props 列表(时间倒序, limit 封顶)。
     */
    @Select("""
        <script>
        SELECT props::text FROM profile_event
        WHERE source = #{source} AND subject_type = #{subjectType} AND subject_id = #{subjectId}
          AND event_type = #{eventType}
          <if test="windowStart != null">AND occurred_time &gt;= #{windowStart}</if>
        ORDER BY occurred_time DESC LIMIT #{limit}
        </script>
        """)
    List<String> recentEventPropsJson(@Param("source") String source, @Param("subjectType") String subjectType,
                                      @Param("subjectId") String subjectId, @Param("eventType") String eventType,
                                      @Param("windowStart") Date windowStart, @Param("limit") int limit);

    /**
     * 保留期清理(跨租户, 调用方须 TenantHelper.ignore), 单轮限量。
     *
     * @return 删除行数
     */
    @Delete("""
        DELETE FROM profile_event WHERE event_id IN (
            SELECT event_id FROM profile_event WHERE occurred_time &lt; #{before} LIMIT #{limit}
        )
        """)
    int deleteBefore(@Param("before") Date before, @Param("limit") int limit);
}
