package org.dromara.assistant.core;

import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩策略纯函数单测（批A-1，2026-10-22 拍板 Q1/Q5）：
 * 触发线=窗口−输出预留−缓冲，不看模型自报；尾窗按轮保留 1 轮。
 */
@Tag("dev")
class AssistantCompactionPolicyTest {

    @Test
    void deriveTriggerTokens_usesZcodeReserveChain() {
        // 128K 窗口、最大输出≥21K：128000−21000−13000=94000
        assertThat(AssistantCompactionPolicy.deriveTriggerTokens(128_000, 32_000)).isEqualTo(94_000);
        // 200K 窗口、未配最大输出（按 3.2 万兜底，仍被 21K 上限截断）：200000−21000−13000=166000
        assertThat(AssistantCompactionPolicy.deriveTriggerTokens(200_000, null)).isEqualTo(166_000);
        // 最大输出小于 21K：按实际值预留（128000−4096−13000=110904）
        assertThat(AssistantCompactionPolicy.deriveTriggerTokens(128_000, 4_096)).isEqualTo(110_904);
        // 小窗口不足预留：落到 4000 地板（不能返回 0——0 会被框架当成“按窗口动态算”）
        assertThat(AssistantCompactionPolicy.deriveTriggerTokens(8_000, 32_000)).isEqualTo(4_000);
        // 32K 档（三审补）：32000−21000−13000=−2000 → 同样落 4000 地板
        assertThat(AssistantCompactionPolicy.deriveTriggerTokens(32_000, null)).isEqualTo(4_000);
        // 窗口缺失：20 万兜底
        assertThat(AssistantCompactionPolicy.deriveTriggerTokens(null, null)).isEqualTo(166_000);
    }

    @Test
    void effectiveContextWindow_fallsBackTo200k() {
        assertThat(AssistantCompactionPolicy.effectiveContextWindow(null)).isEqualTo(200_000);
        assertThat(AssistantCompactionPolicy.effectiveContextWindow(0)).isEqualTo(200_000);
        assertThat(AssistantCompactionPolicy.effectiveContextWindow(-1)).isEqualTo(200_000);
        assertThat(AssistantCompactionPolicy.effectiveContextWindow(64_000)).isEqualTo(64_000);
    }

    @Test
    void outputReserve_capsAt21k() {
        assertThat(AssistantCompactionPolicy.outputReserve(null)).isEqualTo(21_000);
        assertThat(AssistantCompactionPolicy.outputReserve(8_000)).isEqualTo(8_000);
        assertThat(AssistantCompactionPolicy.outputReserve(100_000)).isEqualTo(21_000);
    }

    @Test
    void microcompactThreshold_is90PercentOrMinus2000() {
        // 94000 × 0.9 = 84600，比 94000−2000=92000 小 → 84600
        assertThat(AssistantCompactionPolicy.microcompactThreshold(94_000)).isEqualTo(84_600);
        // 地板触发线 4000：min(3600, 2000) = 2000
        assertThat(AssistantCompactionPolicy.microcompactThreshold(4_000)).isEqualTo(2_000);
    }

    @Test
    void buildConfig_keepsLastRoundByMessageCutoff() {
        CompactionConfig cfg = AssistantCompactionAssets.buildConfig(94_000);
        assertThat(cfg.getTriggerMessages()).isZero();
        assertThat(cfg.getTriggerTokens()).isEqualTo(94_000);
        // keepTokens=0 → 框架走 keepMessages 切点；keepMessages=2 = 最后 1 轮（user+assistant）
        assertThat(cfg.getKeepTokens()).isZero();
        assertThat(cfg.getKeepMessages()).isEqualTo(AssistantCompactionPolicy.KEEP_MESSAGES);
        assertThat(cfg.getSummaryPrompt()).isEqualTo(AssistantCompactionAssets.SUMMARY_PROMPT);
        assertThat(cfg.isFlushBeforeCompact()).isFalse();
        assertThat(cfg.isOffloadBeforeCompact()).isFalse();
    }

    @Test
    void buildConfig_floorsTriggerAt4000() {
        assertThat(AssistantCompactionAssets.buildConfig(0).getTriggerTokens())
            .isEqualTo(AssistantCompactionPolicy.MIN_TRIGGER_TOKENS);
        assertThat(AssistantCompactionAssets.buildConfig(-5).getTriggerTokens())
            .isEqualTo(AssistantCompactionPolicy.MIN_TRIGGER_TOKENS);
    }
}
