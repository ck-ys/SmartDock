package com.smartdock.assistant.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 额度三级管控批（2026-11-26 / Q4=3 + Q20=1）四级取值链纯函数单测。
 *
 * <p>口径：个人覆盖 → 企业默认 → 套餐建议值 → 平台兜底；每级 null/-2 都算「没设」，
 * <b>-1 = 不限</b>是明值、就地终止链条（个人「不限」可越过套餐档）。</p>
 *
 * <p>纯函数不起 Spring，{@code @Tag("local")} 进常规门禁。</p>
 */
@Tag("local")
class CreditLimitChainUnitTest {

    @Test
    void 个人设了就用个人的_压过企业默认与套餐建议() {
        AssistantUserQuotaService.CreditLimit r =
            AssistantUserQuotaService.resolveCreditLimit(300, 100, 50);
        assertThat(r.value()).isEqualTo(300);
        assertThat(r.source()).isEqualTo("personal");
    }

    @Test
    void 个人没设落企业默认_压过套餐建议() {
        AssistantUserQuotaService.CreditLimit r =
            AssistantUserQuotaService.resolveCreditLimit(null, 100, 50);
        assertThat(r.value()).isEqualTo(100);
        assertThat(r.source()).isEqualTo("tenant");
    }

    @Test
    void 个人与企业都没设_用套餐建议值() {
        AssistantUserQuotaService.CreditLimit r =
            AssistantUserQuotaService.resolveCreditLimit(null, null, 225);
        assertThat(r.value()).isEqualTo(225);
        assertThat(r.source()).isEqualTo("plan");
    }

    @Test
    void 三级都没设_落平台兜底数() {
        AssistantUserQuotaService.CreditLimit r =
            AssistantUserQuotaService.resolveCreditLimit(null, null, null);
        assertThat(r.value()).isEqualTo(AssistantUserQuotaService.PLATFORM_DAILY_CREDIT);
        assertThat(r.source()).isEqualTo("platform");
    }

    @Test
    void 负二等于没设_三级都传负二_仍落平台兜底() {
        // -2 = 显式「跟随」的落库记号；读侧与 NULL 同义（写入已归一，这里兜历史脏值）
        AssistantUserQuotaService.CreditLimit r =
            AssistantUserQuotaService.resolveCreditLimit(-2, -2, null);
        assertThat(r.source()).isEqualTo("platform");
    }

    @Test
    void 个人负二_落到企业默认() {
        AssistantUserQuotaService.CreditLimit r =
            AssistantUserQuotaService.resolveCreditLimit(-2, 80, 225);
        assertThat(r.value()).isEqualTo(80);
        assertThat(r.source()).isEqualTo("tenant");
    }

    @Test
    void 个人不限_越过套餐建议值_就地终止链条() {
        // Q20=1：个人「不限」可越过套餐档——套餐是平台定的，个人是客户自己决定的
        AssistantUserQuotaService.CreditLimit r =
            AssistantUserQuotaService.resolveCreditLimit(-1, 100, 50);
        assertThat(r.value()).isEqualTo(-1);
        assertThat(r.source()).isEqualTo("personal");
        assertThat(r.unlimited()).isTrue();
    }

    @Test
    void 企业默认不限_同样终止链条() {
        AssistantUserQuotaService.CreditLimit r =
            AssistantUserQuotaService.resolveCreditLimit(null, -1, 50);
        assertThat(r.value()).isEqualTo(-1);
        assertThat(r.source()).isEqualTo("tenant");
        assertThat(r.unlimited()).isTrue();
    }

    @Test
    void 套餐建议值为零或负_当没给建议_落平台兜底() {
        // 套餐页填 0 = 没给建议（Q18=1）：0 在积分列上没有「急停」语义
        assertThat(AssistantUserQuotaService.resolveCreditLimit(null, null, 0).source())
            .isEqualTo("platform");
        assertThat(AssistantUserQuotaService.resolveCreditLimit(null, null, -5).source())
            .isEqualTo("platform");
    }

    @Test
    void 上限为一时_也是有效上限_不是不限() {
        // 边界：1 是合法正数（保存侧 1..10000），不能被当成 falsy 掉到下一级
        AssistantUserQuotaService.CreditLimit r =
            AssistantUserQuotaService.resolveCreditLimit(1, 100, 50);
        assertThat(r.value()).isEqualTo(1);
        assertThat(r.source()).isEqualTo("personal");
        assertThat(r.unlimited()).isFalse();
    }
}
