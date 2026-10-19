package com.smartdock.service.support;

import java.util.Map;

/**
 * 钱包流水服务码 → 中文标签的唯一权威映射（业务批 2026-10-15 Q2）。
 *
 * <p>此前中文对照散在前端常量里，后端每上一个新服务码（如批8 的 AI_CREDIT_SUBSCRIBE）
 * 前端就漏译一次、页面裸奔英文码。收敛到后端统一下发（ledger VO 带 serviceCodeLabel），
 * 前端常量降级为兜底；新服务码只改这一处。</p>
 */
public final class AiServiceCodeLabels {

    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("AI_ASSISTANT_CHAT", "AI 助手对话"),
        Map.entry("AI_PROFILE_SCORE", "画像评分"),
        Map.entry("AI_PROFILE_DIAGNOSE", "画像诊断"),
        Map.entry("AI_FIELD_GENERATE", "字段生成"),
        Map.entry("AI_SCORE_ENHANCE", "评分润色"),
        Map.entry("AI_SCORE_RULE_RECOMMEND", "评分规则推荐"),
        Map.entry("AI_PROFILE_EMBEDDING", "向量化"),
        Map.entry("ADMIN_GIFT", "开户赠送"),
        Map.entry("RECHARGE", "充值"),
        Map.entry("AI_CREDIT_SUBSCRIBE", "积分套餐订阅扣款"),
        Map.entry("AI_CREDIT_BOOST", "加油包购买")
    );

    private AiServiceCodeLabels() {
    }

    /** 未收录码原样返回（前端兜底映射再试一次），null 返回 null */
    public static String label(String serviceCode) {
        return serviceCode == null ? null : LABELS.getOrDefault(serviceCode, serviceCode);
    }
}
