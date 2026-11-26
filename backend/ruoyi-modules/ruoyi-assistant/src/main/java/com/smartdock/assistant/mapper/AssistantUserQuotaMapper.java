package com.smartdock.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.smartdock.assistant.domain.model.AssistantUserQuota;

import java.util.List;
import java.util.Map;

/**
 * 用户额度覆盖 Mapper（业务批3-③ #16）。
 * 类级 {@code @InterceptorIgnore(tenantLine="true")}：全部谓词显式带 tenant_id
 * （AssistantConfigMapper 同款口径——流线程/管理线程统一显式，防租户线拦截器串扰）。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantUserQuotaMapper extends BaseMapper<AssistantUserQuota> {

    /**
     * 覆盖行 upsert（首插/在位更新二合一，幂等；(tenant_id,user_id) 唯一）。
     * 三业务列 COALESCE 语义=只改传入列，null 列保持原值（清空跟随默认走专用清空 SQL）。
     */
    @Insert("INSERT INTO assistant_user_quota (id, tenant_id, user_id, round_limit_override,"
        + " websearch_limit_override, disabled, create_time)"
        + " VALUES (#{q.id}, #{q.tenantId}, #{q.userId}, #{q.roundLimitOverride},"
        + " #{q.websearchLimitOverride}, COALESCE(#{q.disabled}, '0'), CURRENT_TIMESTAMP)"
        + " ON CONFLICT (tenant_id, user_id) DO UPDATE SET"
        + " round_limit_override = COALESCE(EXCLUDED.round_limit_override, assistant_user_quota.round_limit_override),"
        + " websearch_limit_override = COALESCE(EXCLUDED.websearch_limit_override, assistant_user_quota.websearch_limit_override),"
        + " disabled = COALESCE(EXCLUDED.disabled, assistant_user_quota.disabled),"
        + " update_time = CURRENT_TIMESTAMP")
    int upsert(@Param("q") AssistantUserQuota quota);

    /**
     * 单人上限清空（恢复跟随租户默认）：round/websearch 二列置 NULL（禁用态不动）。
     * 与 upsert 的 COALESCE 语义互补——upsert 无法把列写回 NULL。
     */
    @Insert("UPDATE assistant_user_quota SET round_limit_override = NULL,"
        + " websearch_limit_override = NULL, update_time = CURRENT_TIMESTAMP"
        + " WHERE tenant_id = #{tenantId} AND user_id = #{userId}")
    int clearLimits(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /**
     * 批④单列定向写：回合列设值/联网列清空（业务批3-④ 限额弹窗双列独立「跟随默认」语义——
     * 联网清空≠回合清空，COALESCE upsert 无法表达"一列设值一列清空"）。行不存在=首插。
     */
    @Insert("INSERT INTO assistant_user_quota (id, tenant_id, user_id, round_limit_override, create_time)"
        + " VALUES (#{q.id}, #{q.tenantId}, #{q.userId}, #{q.roundLimitOverride}, CURRENT_TIMESTAMP)"
        + " ON CONFLICT (tenant_id, user_id) DO UPDATE SET"
        + " round_limit_override = #{q.roundLimitOverride},"
        + " websearch_limit_override = NULL, update_time = CURRENT_TIMESTAMP")
    int upsertRoundClearWeb(@Param("q") AssistantUserQuota quota);

    /** 批④单列定向写：联网列设值/回合列清空（同上镜像） */
    @Insert("INSERT INTO assistant_user_quota (id, tenant_id, user_id, websearch_limit_override, create_time)"
        + " VALUES (#{q.id}, #{q.tenantId}, #{q.userId}, #{q.websearchLimitOverride}, CURRENT_TIMESTAMP)"
        + " ON CONFLICT (tenant_id, user_id) DO UPDATE SET"
        + " round_limit_override = NULL,"
        + " websearch_limit_override = #{q.websearchLimitOverride}, update_time = CURRENT_TIMESTAMP")
    int upsertWebClearRound(@Param("q") AssistantUserQuota quota);

    /**
     * 额度三级管控批（2026-11-26）：只写积分列——填了数就写进去，传 -2 写 NULL（清回跟随企业默认），
     * 不碰回合/联网两列（那两列继续走上面四条）。
     * <b>不扩成 8 种组合</b>：积分列是后加的一维，与另两列的组合由 service 分两条语句表达。
     * 首插：另两列留 NULL（=跟随企业默认）、disabled 落 DDL 默认 '0'。
     */
    @Insert("INSERT INTO assistant_user_quota (id, tenant_id, user_id, credit_limit_override, create_time)"
        + " VALUES (#{q.id}, #{q.tenantId}, #{q.userId},"
        + " NULLIF(CAST(#{q.creditLimitOverride} AS integer), -2), CURRENT_TIMESTAMP)"
        + " ON CONFLICT (tenant_id, user_id) DO UPDATE SET"
        + " credit_limit_override = EXCLUDED.credit_limit_override,"
        + " update_time = CURRENT_TIMESTAMP")
    int upsertCreditOnly(@Param("q") AssistantUserQuota quota);

    /**
     * 用户额度页（联表分页数据行）：sys_user 昵称/部门 + 覆盖行原文。
     * keyword 模糊匹配昵称或账号；按 user_id 稳定序。
     * 页数据（今日回合/本月费用）由 Service 层补——Redis 与账本不进本 SQL。
     * 可空参数显式 jdbcType=VARCHAR：PG 驱动对 "? IS NULL" 无类型字面量无法推断（实测 500）。
     */
    @Select("SELECT u.user_id AS userid, u.nick_name AS nickname, u.user_name AS username,"
        + " COALESCE(d.dept_name, '未分部门') AS deptname,"
        + " q.round_limit_override AS roundoverride,"
        + " q.websearch_limit_override AS websearchoverride,"
        + " q.credit_limit_override AS creditoverride,"
        + " COALESCE(q.disabled, '0') AS disabled"
        + " FROM sys_user u"
        + " LEFT JOIN sys_dept d ON d.dept_id = u.dept_id"
        + " LEFT JOIN assistant_user_quota q ON q.tenant_id = #{tenantId} AND q.user_id = u.user_id"
        + " WHERE u.del_flag = '0' AND u.tenant_id = #{tenantId}"
        + " AND (#{keyword,jdbcType=VARCHAR} IS NULL OR u.nick_name ILIKE '%' || #{keyword,jdbcType=VARCHAR} || '%' OR u.user_name ILIKE '%' || #{keyword,jdbcType=VARCHAR} || '%')"
        + " AND (#{disabledOnly,jdbcType=VARCHAR} IS NULL OR COALESCE(q.disabled, '0') = #{disabledOnly,jdbcType=VARCHAR})"
        + " ORDER BY u.user_id LIMIT #{limit} OFFSET #{offset}")
    List<Map<String, Object>> selectQuotaPage(@Param("tenantId") String tenantId,
                                              @Param("keyword") String keyword,
                                              @Param("disabledOnly") String disabledOnly,
                                              @Param("limit") int limit, @Param("offset") int offset);

    /** 上一查询的同行数口径计数（分页 total；可空参数同款 jdbcType） */
    @Select("SELECT COUNT(*) FROM sys_user u"
        + " LEFT JOIN assistant_user_quota q ON q.tenant_id = #{tenantId} AND q.user_id = u.user_id"
        + " WHERE u.del_flag = '0' AND u.tenant_id = #{tenantId}"
        + " AND (#{keyword,jdbcType=VARCHAR} IS NULL OR u.nick_name ILIKE '%' || #{keyword,jdbcType=VARCHAR} || '%' OR u.user_name ILIKE '%' || #{keyword,jdbcType=VARCHAR} || '%')"
        + " AND (#{disabledOnly,jdbcType=VARCHAR} IS NULL OR COALESCE(q.disabled, '0') = #{disabledOnly,jdbcType=VARCHAR})")
    long countQuotaPage(@Param("tenantId") String tenantId,
                        @Param("keyword") String keyword, @Param("disabledOnly") String disabledOnly);

    /**
     * 本月 AI 费用按操作人聚合（caller=ASSISTANT_CHAT 口径与管理页统计头一致；
     * operator_id 索引 idx_ai_usage_operator_time 支撑）。foreach 展开 IN 列表。
     */
    @Select("<script>SELECT operator_id AS userid, COALESCE(SUM(cost_amount), 0) AS monthcost"
        + " FROM ai_usage WHERE tenant_id = #{tenantId} AND caller = 'ASSISTANT_CHAT'"
        + " AND create_time &gt;= date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')"
        + " AND operator_id IN <foreach collection='userIds' item='uid' open='(' separator=',' close=')'>#{uid}</foreach>"
        + " GROUP BY operator_id</script>")
    List<Map<String, Object>> selectMonthCostByUsers(@Param("tenantId") String tenantId,
                                                     @Param("userIds") List<Long> userIds);

    /**
     * 额度三级管控批（2026-11-26）：本页各人「今天已花积分」按人分组聚合。
     * 与 {@link #selectMonthCostByUsers} 同一个写法，但换账本（积分账本）、换窗口（今天）、
     * 且<b>谓词必须带 {@code biz_type='DEDUCT'}</b>——订阅 / 加油包 / 调整行不是个人花费，
     * 算进来会让「今日用量」虚高，管理员照着调额度就调错。
     * 日界用 Asia/Shanghai 半开区间（上界次日 00:00，不用 23:59:59）。
     * 显式 {@code tenant_id = ?}：本 Mapper 类级 @InterceptorIgnore，租户谓词不会自动加。
     */
    @Select("<script>SELECT create_by AS userid, COALESCE(SUM(credits), 0) AS creditsused"
        + " FROM ai_credit_ledger WHERE tenant_id = #{tenantId} AND biz_type = 'DEDUCT'"
        + " AND create_time &gt;= date_trunc('day', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')"
        + " AND create_time &lt; date_trunc('day', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai') + interval '1 day'"
        + " AND create_by IN <foreach collection='userIds' item='uid' open='(' separator=',' close=')'>#{uid}</foreach>"
        + " GROUP BY create_by</script>")
    List<Map<String, Object>> selectTodayCreditsByUsers(@Param("tenantId") String tenantId,
                                                        @Param("userIds") List<Long> userIds);
}
