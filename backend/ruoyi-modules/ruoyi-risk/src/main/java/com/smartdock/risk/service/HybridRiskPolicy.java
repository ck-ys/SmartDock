package com.smartdock.risk.service;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Resolves the S3 hybrid risk level from independent event-rule and global tracks.
 */
@Component
public class HybridRiskPolicy {

    public HybridDecision resolve(List<RuleScore> ruleScores, GlobalScore globalScore) {
        int ruleLevel = ruleScores.stream()
            .mapToInt(RuleScore::level)
            .max()
            .orElse(RiskScoreService.LEVEL_L0);
        int globalLevel = globalScore.level();
        boolean globalWins = globalLevel > ruleLevel;
        return new HybridDecision(
            Math.max(ruleLevel, globalLevel),
            ruleLevel,
            globalLevel,
            globalWins ? "GLOBAL" : "RULE"
        );
    }

    public record RuleScore(String eventType, int score, int level) {
    }

    public record GlobalScore(int score, int level) {
    }

    public record HybridDecision(int level, int ruleLevel, int globalLevel, String sourceTrack) {
    }
}
