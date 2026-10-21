package com.smartdock.analytics.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.analytics.domain.AnalyticsReplayTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Date;
import java.util.List;

/**
 * 回放任务 Mapper(批10 Q3)。手写 SQL 自带租户条件 + 忽略租户插件(模块铁律)。
 *
 * @author smartdock
 */
@Mapper
public interface AnalyticsReplayTaskMapper extends BaseMapperPlus<AnalyticsReplayTask, AnalyticsReplayTask> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        <script>
        SELECT * FROM analytics_replay_task
        WHERE tenant_id = #{tenantId}
          <if test="source != null and source != ''">AND source = #{source}</if>
          AND del_flag = '0'
        ORDER BY create_time DESC, replay_id DESC
        LIMIT 100
        </script>
        """)
    List<AnalyticsReplayTask> selectRecent(@Param("tenantId") String tenantId, @Param("source") String source);

    /** 断点续跑的僵尸判断: 进度行最近一次刷新时间(调度线程无租户上下文, 忽略插件带显式条件)。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT update_time FROM analytics_replay_task
        WHERE replay_id = #{replayId} AND tenant_id = #{tenantId}
        """)
    Date selectUpdateTime(@Param("tenantId") String tenantId, @Param("replayId") Long replayId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT count(*) FROM analytics_replay_task
        WHERE tenant_id = #{tenantId} AND replay_id = #{replayId}
          AND status = 'RUNNING' AND del_flag = '0'
        """)
    long countRunning(@Param("tenantId") String tenantId, @Param("replayId") Long replayId);

    /** 暂停: 只有执行中(且不是已被 Runner 收尾的)任务可置 PAUSED, 返回 0 行说明状态已变。 */
    @InterceptorIgnore(tenantLine = "true")
    @org.apache.ibatis.annotations.Update("""
        UPDATE analytics_replay_task
        SET status = 'PAUSED', update_time = CURRENT_TIMESTAMP
        WHERE replay_id = #{replayId} AND tenant_id = #{tenantId}
          AND status = 'RUNNING' AND del_flag = '0'
        """)
    int markPaused(@Param("tenantId") String tenantId, @Param("replayId") Long replayId);

    /**
     * 断点推进: 仅执行中可写, 返回 0 行=已被外部暂停/取消, 调用方应立即让位。
     * 不用整行 updateById——那会把内存里的 RUNNING 写回去, 复活外部刚落的 PAUSED。
     */
    @InterceptorIgnore(tenantLine = "true")
    @org.apache.ibatis.annotations.Update("""
        UPDATE analytics_replay_task
        SET current_day = #{day}, days_done = #{done}, update_time = CURRENT_TIMESTAMP
        WHERE replay_id = #{replayId} AND tenant_id = #{tenantId}
          AND status = 'RUNNING' AND del_flag = '0'
        """)
    int updateProgressIfRunning(@Param("tenantId") String tenantId, @Param("replayId") Long replayId,
                                @Param("day") Date day, @Param("done") Integer done);

    /** 失败落库: 仅执行中可写(防覆盖外部 PAUSED)。 */
    @InterceptorIgnore(tenantLine = "true")
    @org.apache.ibatis.annotations.Update("""
        UPDATE analytics_replay_task
        SET status = 'FAILED', error_msg = #{errorMsg}, update_time = CURRENT_TIMESTAMP
        WHERE replay_id = #{replayId} AND tenant_id = #{tenantId}
          AND status = 'RUNNING' AND del_flag = '0'
        """)
    int markFailedIfRunning(@Param("tenantId") String tenantId, @Param("replayId") Long replayId,
                            @Param("errorMsg") String errorMsg);
}
