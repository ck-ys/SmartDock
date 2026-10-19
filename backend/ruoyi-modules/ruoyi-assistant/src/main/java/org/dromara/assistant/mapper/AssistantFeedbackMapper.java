package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.assistant.domain.DcsAssistantFeedback;

/**
 * 助手反馈 Mapper（批G G6）。管理端列表查询显式租户谓词。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantFeedbackMapper extends BaseMapper<DcsAssistantFeedback> {

    /** 管理端反馈列表（租户内全量，最新在前） */
    @Select("SELECT * FROM dcs_assistant_feedback WHERE tenant_id=#{tenantId} ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    java.util.List<DcsAssistantFeedback> selectPage(@Param("tenantId") String tenantId,
        @Param("limit") int limit, @Param("offset") int offset);

    /** 租户反馈总数（列表分页 total 真值，dsflash P2-6） */
    @Select("SELECT COUNT(*) FROM dcs_assistant_feedback WHERE tenant_id=#{tenantId}")
    long countForTenant(@Param("tenantId") String tenantId);

    /**
     * AU-40(1008)：幂等窗探测——同租户同人同消息同评价 10 秒内的最近一条（连点去重判据）。
     * messageId 为空（整会话评价）时按 user_id+rating 空消息列判定。
     */
    @Select("SELECT * FROM dcs_assistant_feedback WHERE tenant_id=#{tenantId} AND user_id=#{userId} "
        + "AND ((CAST(#{messageId} AS bigint) IS NULL AND message_id IS NULL) OR message_id=#{messageId}) "
        + "AND rating=#{rating} AND create_time >= (now() - INTERVAL '10 seconds') "
        + "ORDER BY id DESC LIMIT 1")
    DcsAssistantFeedback selectRecentDuplicate(@Param("tenantId") String tenantId, @Param("userId") Long userId,
        @Param("messageId") Long messageId, @Param("rating") String rating);

    /** Q10（AI 缺口批 2026-10-14）：好评率统计——总量/UP/DOWN 按窗口 */
    @Select("SELECT rating, COUNT(*) AS cnt FROM dcs_assistant_feedback "
        + "WHERE tenant_id=#{tenantId} AND create_time >= (now() - (#{days} || ' days')::interval) "
        + "GROUP BY rating")
    java.util.List<java.util.Map<String, Object>> statsByRating(@Param("tenantId") String tenantId,
        @Param("days") int days);

    /** Q10：点踩原因分布（note 精确值分组 Top N——问卷选项是固定文案，自由文本占少数） */
    @Select("SELECT COALESCE(NULLIF(note, ''), '(未填原因)') AS reason, COUNT(*) AS cnt "
        + "FROM dcs_assistant_feedback WHERE tenant_id=#{tenantId} AND rating='DOWN' "
        + "AND create_time >= (now() - (#{days} || ' days')::interval) "
        + "GROUP BY 1 ORDER BY cnt DESC LIMIT #{topN}")
    java.util.List<java.util.Map<String, Object>> statsDownReasons(@Param("tenantId") String tenantId,
        @Param("days") int days, @Param("topN") int topN);

    /** Q10：近 N 周按周趋势（周一起算；返回 week=周一日期, rating, cnt） */
    @Select("SELECT to_char(date_trunc('week', create_time), 'YYYY-MM-DD') AS week, rating, COUNT(*) AS cnt "
        + "FROM dcs_assistant_feedback WHERE tenant_id=#{tenantId} "
        + "AND create_time >= (now() - (#{weeks} || ' weeks')::interval) "
        + "GROUP BY 1, 2 ORDER BY 1")
    java.util.List<java.util.Map<String, Object>> statsWeeklyTrend(@Param("tenantId") String tenantId,
        @Param("weeks") int weeks);
}
