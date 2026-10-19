package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * AI 用量分析聚合 Mapper（AI 缺口批 Q5/Q6，拍板全A，2026-10-14）。
 *
 * <p>管理台首组 echarts 分析图的数据源：按日趋势 / 模型占比 / Top10 用户 / 部门占比 /
 * 汇总卡。全部 SQL 聚合下推（不拉全量行进内存），口径与用量明细同表同租户。</p>
 *
 * <p>类级 {@code @InterceptorIgnore(tenantLine="true")}：JOIN sys_user/sys_dept 的多表
 * SQL 不交给租户行级拦截器改写，租户隔离由显式谓词 {@code u.tenant_id} 自带——与
 * {@link WalletAlertRecipientMapper} 同配方（模块内既定先例）。</p>
 *
 * @author smartdock
 */
@InterceptorIgnore(tenantLine = "true")
public interface AiUsageAnalysisMapper {

    /** 按日趋势：day/calls/cost（日期轴补零由服务层负责） */
    @Select("""
        SELECT to_char(create_time, 'YYYY-MM-DD') AS day,
               COUNT(*) AS calls,
               COALESCE(SUM(cost_amount), 0) AS cost
          FROM dcs_ai_usage
         WHERE tenant_id = #{tenantId}
           AND create_time >= #{begin}
           AND create_time < #{endExcl}
         GROUP BY 1
         ORDER BY 1
        """)
    List<Map<String, Object>> trend(@Param("tenantId") String tenantId,
                                    @Param("begin") Date begin, @Param("endExcl") Date endExcl);

    /** 模型占比（按消耗降序 Top20）：provider/model_name/calls/cost */
    @Select("""
        SELECT provider, model_name, COUNT(*) AS calls,
               COALESCE(SUM(cost_amount), 0) AS cost
          FROM dcs_ai_usage
         WHERE tenant_id = #{tenantId}
           AND create_time >= #{begin}
           AND create_time < #{endExcl}
         GROUP BY provider, model_name
         ORDER BY cost DESC
         LIMIT 20
        """)
    List<Map<String, Object>> modelShare(@Param("tenantId") String tenantId,
                                         @Param("begin") Date begin, @Param("endExcl") Date endExcl);

    /** Top10 用户（含部门名，Q6 部门维度观测）：operator_id/user_name/dept_name/calls/cost */
    @Select("""
        SELECT u.operator_id,
               COALESCE(s.nick_name, CASE WHEN u.operator_id IS NULL THEN '系统' ELSE '已注销用户' END) AS user_name,
               COALESCE(d.dept_name, '未分配部门') AS dept_name,
               COUNT(*) AS calls,
               COALESCE(SUM(u.cost_amount), 0) AS cost
          FROM dcs_ai_usage u
          LEFT JOIN sys_user s ON s.user_id = u.operator_id
          LEFT JOIN sys_dept d ON d.dept_id = s.dept_id
         WHERE u.tenant_id = #{tenantId}
           AND u.create_time >= #{begin}
           AND u.create_time < #{endExcl}
         GROUP BY u.operator_id, s.nick_name, d.dept_name
         ORDER BY cost DESC
         LIMIT 10
        """)
    List<Map<String, Object>> topUsers(@Param("tenantId") String tenantId,
                                       @Param("begin") Date begin, @Param("endExcl") Date endExcl);

    /** 部门占比（Q6：先看后管——只观测不拦截）：dept_name/calls/cost */
    @Select("""
        SELECT COALESCE(d.dept_name, '未分配部门') AS dept_name,
               COUNT(*) AS calls,
               COALESCE(SUM(u.cost_amount), 0) AS cost
          FROM dcs_ai_usage u
          LEFT JOIN sys_user s ON s.user_id = u.operator_id
          LEFT JOIN sys_dept d ON d.dept_id = s.dept_id
         WHERE u.tenant_id = #{tenantId}
           AND u.create_time >= #{begin}
           AND u.create_time < #{endExcl}
         GROUP BY d.dept_name
         ORDER BY cost DESC
         LIMIT 20
        """)
    List<Map<String, Object>> deptShare(@Param("tenantId") String tenantId,
                                        @Param("begin") Date begin, @Param("endExcl") Date endExcl);

    /** 汇总单行：calls/success_calls/avg_latency_ms/cost */
    @Select("""
        SELECT COUNT(*) AS calls,
               COUNT(*) FILTER (WHERE success = '1') AS success_calls,
               COALESCE(AVG(latency_ms), 0) AS avg_latency_ms,
               COALESCE(SUM(cost_amount), 0) AS cost
          FROM dcs_ai_usage
         WHERE tenant_id = #{tenantId}
           AND create_time >= #{begin}
           AND create_time < #{endExcl}
        """)
    Map<String, Object> summaryRow(@Param("tenantId") String tenantId,
                                   @Param("begin") Date begin, @Param("endExcl") Date endExcl);

    /** 分析报表导出：按日×模型聚合行（day/provider/model_name/calls/success_calls/avg_latency_ms/cost） */
    @Select("""
        SELECT to_char(create_time, 'YYYY-MM-DD') AS day,
               provider, model_name,
               COUNT(*) AS calls,
               COUNT(*) FILTER (WHERE success = '1') AS success_calls,
               COALESCE(AVG(latency_ms), 0) AS avg_latency_ms,
               COALESCE(SUM(cost_amount), 0) AS cost
          FROM dcs_ai_usage
         WHERE tenant_id = #{tenantId}
           AND create_time >= #{begin}
           AND create_time < #{endExcl}
         GROUP BY 1, 2, 3
         ORDER BY 1, cost DESC
        """)
    List<Map<String, Object>> dailyModelRows(@Param("tenantId") String tenantId,
                                             @Param("begin") Date begin, @Param("endExcl") Date endExcl);
}
