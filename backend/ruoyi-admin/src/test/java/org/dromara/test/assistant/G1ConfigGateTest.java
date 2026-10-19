package org.dromara.test.assistant;

import org.dromara.assistant.domain.DcsAssistantConfig;
import org.dromara.assistant.service.AssistantConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批G G1 租户开关三态+配置表单测（2026-10-06）。
 * 判据：无行=平台默认(关)；灰球=开但未配模型；assertOpen 关闭 403 人话/upsert 覆盖生效。
 */
@Tag("p0probe")
@SpringBootTest
class G1ConfigGateTest {

    /** 测试专属租户（不与联调租户混用；@AfterAll 清理由 upsert 幂等与隔离键兜底） */
    private static final String TENANT_OPEN = "996311";
    private static final String TENANT_CLOSED = "996312";

    @Autowired
    AssistantConfigService configService;

    @Test
    void noRowMeansPlatformDefaultClosed() {
        AssistantConfigService.EffectiveConfig cfg = configService.effective(TENANT_CLOSED);
        assertFalse(cfg.rowPresent(), "无行=未覆盖");
        assertFalse(cfg.enabled(), "平台默认=关闭");
        assertEquals(50, cfg.dailyRoundLimitPerUser(), "Q5 平台默认 50 回合/人/日");
        assertEquals(0, cfg.dailyBudgetAmount().compareTo(new BigDecimal("50.0000")), "Q16 平台默认日预算 50 元");
        assertEquals("0", cfg.memoryEnabled(), "拍板⑥：P1 记忆恒关");
    }

    @Test
    void assertOpenRejectsClosedTenantWithHumanMessage() {
        Exception ex = assertThrows(Exception.class, () -> configService.assertOpen(TENANT_CLOSED),
            "关闭租户必须 403 拒绝");
        assertTrue(ex.getMessage().contains("未开通"), "话术须人话: " + ex.getMessage());
    }

    @Test
    void upsertOverridesTakeEffect() {
        DcsAssistantConfig row = new DcsAssistantConfig();
        row.setTenantId(TENANT_OPEN);
        row.setEnabled("1");
        row.setDailyRoundLimitPerUser(30);
        row.setDailyBudgetAmount(new BigDecimal("20.0000"));
        configService.update(row);
        AssistantConfigService.EffectiveConfig cfg = configService.effective(TENANT_OPEN);
        assertTrue(cfg.enabled());
        assertEquals(30, cfg.dailyRoundLimitPerUser(), "租户覆盖值生效");
        assertEquals(0, cfg.dailyBudgetAmount().compareTo(new BigDecimal("20.0000")));
        assertDoesNotThrow(() -> configService.assertOpen(TENANT_OPEN), "开启租户放行");

        // 二次 upsert 覆盖（幂等路径）
        row.setDailyRoundLimitPerUser(40);
        configService.update(row);
        assertEquals(40, configService.effective(TENANT_OPEN).dailyRoundLimitPerUser(), "upsert 二次覆盖生效");
    }

    @Test
    void grayBallWhenEnabledButNoModel() {
        // 996312 无模型配置行 → modelConfigured=false（灰球派生态不落库）
        assertFalse(configService.modelConfigured(TENANT_CLOSED), "未配模型=灰球");
        // 978311 有启用 CHAT 模型行（联调种子）→ true
        assertTrue(configService.modelConfigured("978311"), "已配模型=开启态");
    }
}
