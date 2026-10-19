package com.smartdock.service.support;

import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.DcsAiModelPrice;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AiBillingCalculatorUnitTest {

    @Test
    @Tag("dev")
    void officialTokenBillingIncludesCachedInputOutputAndServiceFee() {
        DcsAiConfig config = new DcsAiConfig();
        config.setBillingMode(AiBillingCalculator.BILLING_MODE_OFFICIAL_TOKEN);
        config.setUnitPricePerCall(new BigDecimal("0.0100"));
        config.setServiceFeeRate(new BigDecimal("10.00"));

        DcsAiModelPrice price = new DcsAiModelPrice();
        price.setProvider("OPENAI");
        price.setModelName("gpt-test");
        price.setCurrency("USD");
        price.setInputPricePerMillion(new BigDecimal("2.00"));
        price.setCachedInputPricePerMillion(new BigDecimal("0.50"));
        price.setOutputPricePerMillion(new BigDecimal("8.00"));

        AiTokenUsageSnapshot usage = new AiTokenUsageSnapshot(1000, 200, 500, 1500);

        AiBillingCalculator.Result result = new AiBillingCalculator().calculate(config, price, usage, true);

        assertThat(result.billingMode()).isEqualTo(AiBillingCalculator.BILLING_MODE_OFFICIAL_TOKEN);
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.baseCostAmount()).isEqualByComparingTo("0.00570000");
        assertThat(result.serviceFeeRate()).isEqualByComparingTo("10.00");
        assertThat(result.serviceFeeAmount()).isEqualByComparingTo("0.00057000");
        assertThat(result.costAmount()).isEqualByComparingTo("0.00627000");
        assertThat(result.pricingSnapshot()).contains("inputPricePerMillion", "cachedInputPricePerMillion", "outputPricePerMillion");
    }

    @Test
    @Tag("dev")
    void missingTokenUsageFallsBackToFixedPerCall() {
        DcsAiConfig config = new DcsAiConfig();
        config.setBillingMode(AiBillingCalculator.BILLING_MODE_OFFICIAL_TOKEN);
        config.setUnitPricePerCall(new BigDecimal("0.0123"));
        config.setServiceFeeRate(new BigDecimal("25.00"));

        AiBillingCalculator.Result result = new AiBillingCalculator().calculate(config, null, null, true);

        assertThat(result.billingMode()).isEqualTo(AiBillingCalculator.BILLING_MODE_FIXED_PER_CALL);
        assertThat(result.unitPrice()).isEqualByComparingTo("0.0123");
        assertThat(result.baseCostAmount()).isEqualByComparingTo("0.0123");
        assertThat(result.serviceFeeAmount()).isEqualByComparingTo("0");
        assertThat(result.costAmount()).isEqualByComparingTo("0.0123");
    }
}
