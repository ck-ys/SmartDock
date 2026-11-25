package com.smartdock.service.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流水摘要解析单测（2026-11-25 钱包流水批，活体验收读图发现）。
 *
 * <p>缺陷现场：钱包流水主列显示 "--"。手动充值/扣减写流水时 {@code service_code} 为空，
 * 操作人填的原因只落在 {@code extra_json.reason}，前端主列取 {@code serviceCodeLabel} 必然为空。</p>
 *
 * <p>这里钉三件事：① 有 reason 时摘要取 reason（人话优先）；
 * ② 无 reason 时由调用方回退服务码标签（本类只负责返回 null，不自己编话术）；
 * ③ 坏 JSON / 空值一律返回 null，不能让整页 500。</p>
 */
@Tag("local")
class WalletLedgerSummaryReasonUnitTest {

    /** reasonOf 是私有实现细节，用反射直取——本单测只钉「解析行为」，不钉公开 API 形状 */
    private static String reasonOf(String extraJson) throws Exception {
        Method m = TenantWalletLedgerService.class.getDeclaredMethod("reasonOf", String.class);
        m.setAccessible(true);
        return (String) m.invoke(new TenantWalletLedgerService(null), extraJson);
    }

    @Test
    void reasonIsPreferredOverServiceLabel() throws Exception {
        assertThat(reasonOf("{\"reason\":\"AI 助手对话（演示）\"}")).isEqualTo("AI 助手对话（演示）");
    }

    @Test
    void missingReasonReturnsNullSoCallerCanFallBack() throws Exception {
        assertThat(reasonOf("{\"other\":\"x\"}")).isNull();
        assertThat(reasonOf("{}")).isNull();
    }

    @Test
    void blankAndNullExtraJsonReturnNull() throws Exception {
        assertThat(reasonOf(null)).isNull();
        assertThat(reasonOf("")).isNull();
        assertThat(reasonOf("   ")).isNull();
    }

    @Test
    void brokenJsonDoesNotThrow() throws Exception {
        // 老数据/手工写入的异常 JSON 不能让列表整页 500
        assertThat(reasonOf("{not json")).isNull();
        assertThat(reasonOf("plain text")).isNull();
    }

    @Test
    void reasonIsTrimmed() throws Exception {
        assertThat(reasonOf("{\"reason\":\"  补款  \"}")).isEqualTo("补款");
    }
}
