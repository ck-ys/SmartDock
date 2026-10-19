package com.smartdock.risk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.risk.domain.DcsRiskRule;
import com.smartdock.risk.domain.vo.RiskRuleScoreVo;
import com.smartdock.risk.mapper.DcsRiskEventMapper;
import com.smartdock.risk.mapper.DcsRiskRuleMapper;
import org.springframework.stereotype.Service;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * 风险评分 + 分级服务。
 *
 * <p><b>评分模型</b>: 滑动窗口内事件累加。
 * 用户维度分 = Σ(近 windowMinutes 分钟内该用户所有事件的 risk_score)。
 * IP 维度分同理。两者取较大值作为最终风险分。</p>
 *
 * <p><b>分级</b>(参考 hCaptcha Adaptive Security):
 * <ul>
 *   <li>L0 正常(&lt;L1 阈值): 无干预</li>
 *   <li>L1 低风险: 简单图形验证码(4 位)</li>
 *   <li>L2 中风险: 点选文字验证码</li>
 *   <li>L3 高风险: 扭曲码 + 手机 OTP</li>
 *   <li>L4 极高危: 邮箱 + OTP + 临时封禁</li>
 * </ul>
 * 阈值来自 dcs_risk_rule(可配置)。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskScoreService {

    public static final int LEVEL_L0 = 0;
    public static final int LEVEL_L1 = 1;
    public static final int LEVEL_L2 = 2;
    public static final int LEVEL_L3 = 3;
    public static final int LEVEL_L4 = 4;

    /** 风险事件 Mapper(滑动窗口内累计评分) */
    private final DcsRiskEventMapper eventMapper;
    /** 风险规则 Mapper(取全局阈值/窗口) */
    private final DcsRiskRuleMapper ruleMapper;
    /** 混合风控策略(按类型/全局取严决策) */
    private final HybridRiskPolicy hybridRiskPolicy;

    /**
     * 计算用户当前的风险等级(综合 user + ip 两个维度取较大值)。
     *
     * @param userId 用户 ID(可空)
     * @param ip     客户端 IP(可空)
     * @return 0-4 (L0-L4)
     */
    public RiskAssessment assess(Long userId, String ip) {
        // 步骤1: IP 归一化(空串→null, 让 SQL 跳过 IP 维度查询)
        String normalizedIp = ip == null || ip.isBlank() ? null : ip;
        // 租户上下文: 登录态取当前租户; 无登录态(验证码风控)兜底 000000,
        // 与风险事件写入侧拦截器默认租户一致, 保证读写口径不漂移。
        String tenantId = LoginHelper.getTenantId();
        if (StrUtil.isBlank(tenantId)) {
            tenantId = "000000";
        }

        // 步骤2: 按事件类型分组查询近窗口内的事件评分(eventRuleScores 是聚合 SQL)
        // 返回每个 eventType 的 score + level, 用于 HybridRiskPolicy 按类型维度决策
        List<RiskRuleScoreVo> ruleScores = eventMapper.eventRuleScores(userId, normalizedIp, tenantId);
        List<HybridRiskPolicy.RuleScore> policyRuleScores = ruleScores.stream()
            .map(item -> new HybridRiskPolicy.RuleScore(
                item.getEventType(),
                item.getScore() == null ? 0 : item.getScore(),
                item.getLevel() == null ? LEVEL_L0 : item.getLevel()
            ))
            .toList();

        // 步骤3: 加载 GLOBAL 规则(全局阈值, 只有一条 enabled 的)
        // 显式租户条件与 eventRuleScores(S-1)同款: 拦截器之外的第二层防线, 口径统一
        DcsRiskRule globalRule = ruleMapper.selectOne(new LambdaQueryWrapper<DcsRiskRule>()
            .eq(DcsRiskRule::getRuleScope, "GLOBAL")
            .eq(DcsRiskRule::getEnabled, "1")
            .eq(DcsRiskRule::getTenantId, tenantId)
            .last("LIMIT 1"));
        int globalScore = 0;
        int globalLevel = LEVEL_L0;
        if (globalRule != null) {
            // 步骤3a: 按全局规则的滑动窗口算用户维度和 IP 维度的累计风险分
            int windowMin = globalRule.getWindowMinutes() == null ? 60 : globalRule.getWindowMinutes();
            Date since = sinceMinutes(windowMin);
            int userScore = userId == null ? 0 : eventMapper.sumUserRiskScore(userId, since, tenantId);
            int ipScore = normalizedIp == null ? 0 : eventMapper.sumIpRiskScore(normalizedIp, since, tenantId);
            // 两者取较大值(同一用户换 IP 或同一 IP 换用户都能被抓)
            globalScore = Math.max(userScore, ipScore);
            globalLevel = resolveLevel(globalScore, globalRule);
        }

        // 步骤4: 混合策略最终决策 —— 比较"按事件类型维度的最高级" vs "全局累计分维度的级别", 取更严
        HybridRiskPolicy.HybridDecision decision = hybridRiskPolicy.resolve(
            policyRuleScores,
            new HybridRiskPolicy.GlobalScore(globalScore, globalLevel)
        );

        // 步骤5: 根据 decision.sourceTrack 反推要展示的 totalScore
        // GLOBAL 轨道 → 用 globalScore; RULE 轨道 → 用该级别对应事件类型的最高 score
        int maxRuleScore = policyRuleScores.stream()
            .filter(item -> item.level() == decision.ruleLevel())
            .mapToInt(HybridRiskPolicy.RuleScore::score)
            .max()
            .orElse(0);
        int totalScore = "GLOBAL".equals(decision.sourceTrack()) ? globalScore : maxRuleScore;

        // 步骤6: L3+ 高风险打 info 日志(供安全审计回溯, 不打 warn 避免噪声)
        if (decision.level() >= LEVEL_L3) {
            log.info("[RiskScore] 风险阈值突破 userId={}, ip={}, score={}, level={}, track={}",
                userId, normalizedIp, totalScore, decision.level(), decision.sourceTrack());
        }
        return new RiskAssessment(
            userId,
            normalizedIp,
            totalScore,
            decision.level(),
            decision.ruleLevel(),
            decision.globalLevel(),
            decision.sourceTrack(),
            List.copyOf(ruleScores)
        );
    }

    /**
     * 按阈值解析风险等级。
     */
    public int resolveLevel(int score, DcsRiskRule rule) {
        if (rule == null) {
            // 默认阈值(与 DDL seed 一致)
            if (score >= 80) return LEVEL_L4;
            if (score >= 60) return LEVEL_L3;
            if (score >= 40) return LEVEL_L2;
            if (score >= 20) return LEVEL_L1;
            return LEVEL_L0;
        }
        if (score >= rule.getThresholdL4()) return LEVEL_L4;
        if (score >= rule.getThresholdL3()) return LEVEL_L3;
        if (score >= rule.getThresholdL2()) return LEVEL_L2;
        if (score >= rule.getThresholdL1()) return LEVEL_L1;
        return LEVEL_L0;
    }

    private Date sinceMinutes(int minutes) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, -minutes);
        return c.getTime();
    }

    /**
     * 风险评估结果(level 综合用户/IP 两维度; needCaptcha=level&gt;=L1 触发验证码)。
     *
     * @param userId      被评估的用户ID(可空)
     * @param ip          被评估的IP(归一化后, 可空)
     * @param totalScore  最终展示用风险分(GLOBAL 轨道=累计分, RULE 轨道=该级别最高事件分)
     * @param level       最终风险等级 L0-L4(综合 ruleLevel + globalLevel 取更严)
     * @param ruleLevel   按事件类型维度算出的等级
     * @param globalLevel 按全局累计分维度算出的等级
     * @param sourceTrack 最终等级来源轨道: "GLOBAL" 或 "RULE"(决定 totalScore 取哪个)
     * @param ruleScores  各事件类型维度的评分明细(透传给前端展示)
     */
    public record RiskAssessment(
        Long userId,
        String ip,
        int totalScore,
        int level,
        int ruleLevel,
        int globalLevel,
        String sourceTrack,
        List<RiskRuleScoreVo> ruleScores
    ) {
        public boolean needCaptcha() {
            return level >= LEVEL_L1;
        }
    }
}
