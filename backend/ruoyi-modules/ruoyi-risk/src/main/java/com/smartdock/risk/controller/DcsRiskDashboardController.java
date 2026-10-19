package com.smartdock.risk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.risk.config.DcsRolloutProperties;
import com.smartdock.risk.domain.DcsRiskEvent;
import com.smartdock.risk.mapper.DcsRiskEventMapper;
import com.smartdock.risk.service.ProfileRiskReviewService;
import com.smartdock.risk.service.RiskActionService;
import com.smartdock.risk.service.RiskScoreService;
import com.smartdock.risk.support.RiskUserEnricher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 风控大盘 Controller（从 DcsRiskController 拆出）。
 *
 * <p>职责：
 * <ul>
 *   <li>风控大盘汇总：事件次数 / 等级分布 / 最近事件 / 策略命中排行 TOP10 / 队列计数</li>
 *   <li>风险评估：当前用户自评（发消息前用）/ 指定用户评估（管理员查看）</li>
 * </ul>
 *
 * <p>路由前缀 {@code /dcs/risk} 与拆分前保持一致，前端零改动。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/dcs/risk")
@RequiredArgsConstructor
public class DcsRiskDashboardController extends BaseController {

    /** 风险事件表读写 Mapper（大盘统计/最近事件/排队计数数据源） */
    private final DcsRiskEventMapper eventMapper;
    /** 风险评分业务逻辑服务（计算用户风险等级） */
    private final RiskScoreService riskScoreService;
    /** 风险处置业务逻辑服务（自动处置创建/同步） */
    private final RiskActionService riskActionService;
    /** 画像风险复核业务服务（待复核队列计数） */
    private final ProfileRiskReviewService profileRiskReviewService;
    /** 风险数据用户名补全器（事件 VO 填充 userName/nickName） */
    private final RiskUserEnricher userEnricher;
    /** 灰度开关(风控2.0 批1, 2026-09-22): 防线状态直读, 大盘展示机审总开关/拦截开关当前档位 */
    private final DcsRolloutProperties rolloutProperties;
    /** 机检误报按词反馈 Mapper(U03: TOP 词表误报率分子; 批4 起兼管大盘误报反馈总数) */
    private final com.smartdock.risk.mapper.DcsWordFpFeedbackMapper wordFpFeedbackMapper;
    /** 权限校验服务（按权限返回大盘字段） */
    private final PermissionService permissionService;

    /**
     * 风控大盘汇总数据（Req 7.1）。
     *
     * <p>返回字段：
     * <ul>
     *   <li>{@code eventTypeStats} —— 各事件类型在窗口内的次数 + 风险分总和</li>
     *   <li>{@code levelStats} —— L0-L4 各等级用户数</li>
     *   <li>{@code recentEvents} —— 最近 20 条事件（带 userName/nickName）</li>
     *   <li>{@code topRules} —— 策略命中排行 TOP10（按事件类型聚合，业务调优参考）</li>
     *   <li>{@code totalEvents} —— 历史全量事件数（不受 timeRange 影响）</li>
     *   <li>{@code activeActions} —— 当前生效中的处置数（仅有处置查看权限时返回）</li>
     *   <li>{@code pendingProfileReviews} —— 待复核用户数(同人只算一个, 2026-08-23 口径改造; 仅有复核权限时返回)</li>
     *   <li>{@code timeRange} —— 当前查询的时间窗</li>
     * </ul>
     *
     * @param timeRange 时间窗：{@code 24h}(默认) / {@code 7d} / {@code 30d} / {@code all}
     * @return 大盘数据 Map
     */
    @SaCheckPermission(value = {"dcs:risk:overview", "dcs:risk:list"}, mode = SaMode.OR)
    @GetMapping("/dashboard")
    public R<Map<String, Object>> dashboard(
        @RequestParam(name = "timeRange", required = false, defaultValue = "24h") String timeRange
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        Date since = resolveSince(timeRange);
        // 1. 各事件类型统计
        data.put("eventTypeStats", eventMapper.eventTypeStats(since));
        // 2. 各风险等级用户数
        data.put("levelStats", eventMapper.userRiskLevelStats(since));
        // 3. 最近 20 条事件(带用户名)
        data.put("recentEvents", userEnricher.enrichEventVo(eventMapper.recentEvents(since, 20)));
        // 4. 策略命中排行 TOP10（按事件类型聚合，业务调优参考）
        data.put("topRules", eventMapper.topRulesByCount(since, 10));
        // 5. 总事件数 / 窗口内事件数
        data.put("totalEvents", eventMapper.selectCount(null));
        // 6. 队列计数（按权限返回）
        if (hasPermission("dcs:risk:action:list")) {
            data.put("activeActions", riskActionService.countActive(LoginHelper.getTenantId()));
        }
        if (hasPermission("dcs:profile:review")) {
            data.put("pendingProfileReviews", profileRiskReviewService.countPending(LoginHelper.getTenantId()));
        }
        // 7. 内容风控聚合(风控2.0 批1, 2026-09-22): 当日机检计数 + 7天来源分布/TOP命中词 + 文件驳回计数
        //    (窗口固定 7d/今日, 不随 timeRange 联动——口径在页面标签上明示)
        Date sevenDaysAgo = resolveSince("7d");
        data.put("contentTodayStats", eventMapper.contentModerationTodayStats());
        data.put("sourceStats7d", eventMapper.sourceStats(sevenDaysAgo));
        // TOP 命中词(U03, 2026-09-23): 命中数与同窗误报反馈数合并, 前端算误报率 fpCnt/cnt
        java.util.List<Map<String, Object>> topWords = eventMapper.topHitWords(sevenDaysAgo, 10);
        Map<String, Object> fpByWord = new java.util.HashMap<>();
        for (Map<String, Object> row : wordFpFeedbackMapper.fpCountByWord(sevenDaysAgo)) {
            fpByWord.put(String.valueOf(row.get("word")), row.get("fpCnt"));
        }
        for (Map<String, Object> row : topWords) {
            Object fp = fpByWord.get(String.valueOf(row.get("word")));
            row.put("fpCnt", fp == null ? 0 : ((Number) fp).longValue());
        }
        data.put("topHitWords7d", topWords);
        data.put("fileRejected7d", eventMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DcsRiskEvent>()
            .eq(DcsRiskEvent::getEventType, "FILE_REJECTED")
            .ge(DcsRiskEvent::getCreatedTime, sevenDaysAgo)));
        // 误报反馈计数(批4 口径迁移 2026-10-18): 旧口径聚合 dcs_mission_detail.machine_check='FP' 行数,
        // 旧明细表随微核心化退役(批6 新基线无此表)——改计 risk 自持 dcs_word_fp_feedback 全表行数。
        // 语义微调: 由"判误报的明细行数"变为"误报反馈记录数"; 原则不变——判误报是纠机审的账,
        // 不走事件采集、不给填报用户加风险分。风险模块自此零跨模块表依赖。
        data.put("falseFeedbackCount", wordFpFeedbackMapper.countAll());
        // 8. 防线状态(大盘常驻底卡): 开关直读 + 固定事实(fileguard/文件审核生效, 聊天入口 9/17 下线暂停)
        Map<String, Object> defense = new LinkedHashMap<>();
        defense.put("contentModerationEnabled", rolloutProperties.isContentModerationEnabled());
        defense.put("contentBlockEnabled", rolloutProperties.isContentBlockEnabled());
        defense.put("fileguardActive", true);
        defense.put("fileReviewActive", true);
        defense.put("chatPaused", true);
        data.put("defenseStatus", defense);
        data.put("timeRange", timeRange);
        return R.ok(data);
    }

    /**
     * 评估当前登录用户的风险等级。
     *
     * <p>典型场景：前端发消息前调用，决定是否弹验证码、是否触发临时冻结。
     * 不需要任何权限——登录态即可，每个用户都能查自己的当前风险。
     *
     * @return 包含 {@code level}(0-4) / {@code totalScore} / {@code needCaptcha}
     */
    @Log(title = "DCS风控-当前用户风险评估", businessType = BusinessType.OTHER)
    @GetMapping("/assess/me")
    public R<Map<String, Object>> assessMe() {
        Long userId = LoginHelper.getUserId();
        String ip = ServletUtils.getClientIP();
        RiskScoreService.RiskAssessment assessment = riskScoreService.assess(userId, ip);
        // 评估结果同步给处置系统：达到 L3/L4 会自动创建可逆处置
        riskActionService.syncAssessment(LoginHelper.getTenantId(), assessment, "管理员风险评估");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", assessment.level());
        data.put("totalScore", assessment.totalScore());
        data.put("needCaptcha", assessment.needCaptcha());
        return R.ok(data);
    }

    /**
     * 评估指定用户的风险等级（管理员视角）。
     *
     * @param userId 被评估的用户 ID
     * @return 包含 level / totalScore / userScore / 最近 10 条事件
     */
    @SaCheckPermission(value = {"dcs:risk:event:list", "dcs:risk:list"}, mode = SaMode.OR)
    @Log(title = "DCS风控-用户风险评估", businessType = BusinessType.OTHER)
    @GetMapping("/assess/user/{userId}")
    public R<Map<String, Object>> assessUser(@PathVariable Long userId) {
        RiskScoreService.RiskAssessment assessment = riskScoreService.assess(userId, null);
        riskActionService.syncAssessment(LoginHelper.getTenantId(), assessment, "管理员查看用户风险");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", assessment.level());
        data.put("totalScore", assessment.totalScore());
        data.put("userScore", assessment.totalScore());
        data.put("recentEvents", eventMapper.userRecentEvents(userId, 10));
        return R.ok(data);
    }

    // ============ 私有 helper ============

    /**
     * 判断当前登录用户是否拥有某权限（用于 dashboard 按权限返回字段）。
     * 超级管理员拥有 {@code *:*:*} 通配权限，直接返回 true。
     */
    private boolean hasPermission(String permission) {
        Set<String> permissions = permissionService.getMenuPermission(LoginHelper.getUserId());
        return permissions != null && (permissions.contains("*:*:*") || permissions.contains(permission));
    }

    /**
     * 把 {@code timeRange}(24h/7d/30d/all) 转成起始时间戳。
     * {@code all} 用 epoch(时间戳 0) 表示查全部历史。
     */
    private Date resolveSince(String timeRange) {
        if (timeRange == null) timeRange = "24h";
        return switch (timeRange) {
            case "7d" -> sinceMinutes(7 * 24 * 60);
            case "30d" -> sinceMinutes(30 * 24 * 60);
            case "all" -> new Date(0);
            default -> sinceMinutes(24 * 60); // 24h
        };
    }

    private Date sinceMinutes(int minutes) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, -minutes);
        return c.getTime();
    }
}
