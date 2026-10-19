package com.smartdock.risk.mapper;

import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.risk.domain.DcsRiskEvent;
import com.smartdock.risk.domain.vo.RiskRuleScoreVo;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 风险事件 Mapper
 *
 * @author smartdock
 */
public interface DcsRiskEventMapper extends BaseMapperPlus<DcsRiskEvent, DcsRiskEvent> {

    /**
     * Resolve one user/IP score bucket per enabled EVENT rule using its own window.
     * tenantId: 显式租户隔离, 不依赖租户拦截器注入(S-1 加固——拦截器在无登录态时跳过注入,
     * 全租户规则/事件会混算; 与 sumUserRiskScore/sumIpRiskScore 的显式口径对齐)。
     */
    List<RiskRuleScoreVo> eventRuleScores(@Param("userId") Long userId,
                                          @Param("ip") String ip,
                                          @Param("tenantId") String tenantId);

    /**
     * 统计用户在指定时间窗口内的风险分总和。
     * tenantId: 显式租户隔离(登录态=当前租户, 无登录态=000000 与写入侧拦截器默认一致)。
     */
    int sumUserRiskScore(@Param("userId") Long userId,
                         @Param("startTime") Date startTime,
                         @Param("tenantId") String tenantId);

    /**
     * 统计 IP 在指定时间窗口内的风险分总和。
     * tenantId: 同上, 防御 TenantLineInnerInterceptor 失效时跨租户聚合。
     */
    int sumIpRiskScore(@Param("ip") String ip,
                       @Param("startTime") Date startTime,
                       @Param("tenantId") String tenantId);

    /**
     * 统计各事件类型在时间窗口内的发生次数(大盘用)。
     */
    List<Map<String, Object>> eventTypeStats(@Param("startTime") Date startTime);

    /**
     * 统计各风险等级(L0-L4)的用户数(大盘用)。
     */
    List<Map<String, Object>> userRiskLevelStats(@Param("startTime") Date startTime);

    /**
     * 最近 N 条事件(大盘滚动列表用)。
     */
    List<DcsRiskEvent> recentEvents(@Param("startTime") Date startTime,
                                    @Param("limit") Integer limit);

    /**
     * 查询用户最近 N 条事件(用于风险详情)。
     */
    List<DcsRiskEvent> userRecentEvents(@Param("userId") Long userId,
                                         @Param("limit") Integer limit);

    /**
     * 策略命中排行 TOP N（B 档新增）。
     *
     * <p>按事件类型聚合窗口内的命中次数，倒序取前 N。
     * 业务用途：业务人员据此判断哪些事件类型最频繁，对应规则是否需要调权重/阈值。
     *
     * @param startTime 起始时间（窗口下界）
     * @param limit     返回条数（如 10 表示 TOP10）
     * @return 每行含 eventType + cnt
     */
    List<Map<String, Object>> topRulesByCount(@Param("startTime") Date startTime,
                                               @Param("limit") Integer limit);

    /**
     * 内容机审当日统计(风控2.0 批1, 2026-09-22, 大盘用)。
     *
     * <p>按 detail 前缀区分 BLOCK/REVIEW 级命中(写入侧 ContentModerationService 的固定文案口径),
     * "今日"= 服务器本地日界 date_trunc('day', now())。
     *
     * @return 单行 Map: blocked=BLOCK 级命中数, review=REVIEW 级命中数
     */
    Map<String, Object> contentModerationTodayStats();

    /**
     * 事件来源分布(风控2.0 批1, 大盘用): 窗口内按 source(FORM/ONLINE/EXCEL/FILE/CHAT)计数。
     */
    List<Map<String, Object>> sourceStats(@Param("startTime") Date startTime);

    /**
     * TOP 命中词(风控2.0 批1, 大盘词库调优入口): 窗口内 hit_word 非空的事件按词聚合 TOP N。
     */
    List<Map<String, Object>> topHitWords(@Param("startTime") Date startTime,
                                          @Param("limit") Integer limit);

    /**
     * 全量按词命中数(收尾批 Q1, 2026-09-23): 与 topHitWords 同口径但不限条数,
     * 供词库管理页给每个词补"近 7 天误报 x/y"的误报率分母。
     */
    List<Map<String, Object>> hitCountByWord(@Param("startTime") Date startTime);
}
