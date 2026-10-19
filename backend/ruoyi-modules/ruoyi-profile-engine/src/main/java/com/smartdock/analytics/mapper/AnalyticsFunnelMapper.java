package com.smartdock.analytics.mapper;

import com.smartdock.analytics.domain.AnalyticsFunnel;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 漏斗定义。
 *
 * @author smartdock
 */
@Mapper
public interface AnalyticsFunnelMapper extends BaseMapperPlus<AnalyticsFunnel, AnalyticsFunnel> {

    @Insert("""
        INSERT INTO analytics_funnel
            (funnel_id, tenant_id, source, funnel_code, funnel_name, steps, window_days,
             enabled, builtin, remark, create_by, create_time)
        VALUES
            (#{f.funnelId}, #{f.tenantId}, #{f.source}, #{f.funnelCode}, #{f.funnelName},
             #{f.steps}::jsonb, #{f.windowDays}, #{f.enabled}, #{f.builtin}, #{f.remark},
             #{f.createBy}, CURRENT_TIMESTAMP)
        """)
    int insertJsonb(@Param("f") AnalyticsFunnel funnel);

    @Insert("""
        UPDATE analytics_funnel SET
            funnel_name = #{f.funnelName}, steps = #{f.steps}::jsonb,
            window_days = #{f.windowDays}, enabled = #{f.enabled}, remark = #{f.remark},
            update_by = #{f.updateBy}, update_time = CURRENT_TIMESTAMP
        WHERE funnel_id = #{f.funnelId} AND tenant_id = #{f.tenantId}
        """)
    int updateJsonb(@Param("f") AnalyticsFunnel funnel);
}
