package com.smartdock.risk.service;

import java.util.List;

/**
 * 内容机审结果(风控2.0 批1, 2026-09-22)。
 *
 * <p>一次提交(单行)或一行数据的机审结论: 是否命中 BLOCK/REVIEW 级词、代表词、逐字段命中明细。
 * 调用方按 {@link ContentModerationService#shouldReject} 的灰度开关判定是否拒绝提交——
 * 观察模式(拦截开关关)下本结果仅用于记事件与标记, 不影响任何提交。</p>
 *
 * @param blockHit  任一字段命中 BLOCK 级词
 * @param reviewHit 任一字段命中 REVIEW 级词
 * @param hitWord   代表命中词: BLOCK 级优先, 无则首个 REVIEW 级词; 无命中为 null(落 dcs_risk_event.hit_word 供大盘聚合)
 * @param hits      逐字段命中明细(字段路径/词/级别), 无命中为空列表
 */
public record ContentModerationResult(
    boolean blockHit,
    boolean reviewHit,
    String hitWord,
    List<FieldHit> hits
) {

    /** 无命中的干净结果(总开关关闭/空内容/未命中时返回) */
    public static ContentModerationResult clean() {
        return new ContentModerationResult(false, false, null, List.of());
    }

    /** 逐字段命中明细: fieldKey=字段路径(嵌套用.连接), word=命中原词, action=BLOCK/REVIEW */
    public record FieldHit(String fieldKey, String word, String action) {
    }
}
