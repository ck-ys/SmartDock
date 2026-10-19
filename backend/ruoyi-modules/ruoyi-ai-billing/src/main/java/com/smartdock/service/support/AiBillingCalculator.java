package com.smartdock.service.support;

import cn.hutool.core.util.StrUtil;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.DcsAiModelPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * AI 计费纯计算器。
 *
 * @author smartdock
 */
public class AiBillingCalculator {

    public static final String BILLING_MODE_OFFICIAL_TOKEN = "OFFICIAL_TOKEN";
    public static final String BILLING_MODE_FIXED_PER_CALL = "FIXED_PER_CALL";
    /** 业务批4 Q4-A（用户 2026-10-13 拍板）：官方价＋每次固定服务费 */
    public static final String BILLING_MODE_OFFICIAL_PLUS_FEE = "OFFICIAL_PLUS_FEE";

    private static final BigDecimal DEFAULT_UNIT_PRICE = new BigDecimal("0.0100");
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final int MONEY_SCALE = 8;

    /**
     * 计算 AI 调用费用: 按官方 Token 计费(区分缓存/未缓存输入与输出)或按次计费, 失败返回 0。
     */
    public Result calculate(DcsAiConfig config, DcsAiModelPrice price, AiTokenUsageSnapshot usage, boolean success) {
        if (!success) {
            return zero(config, price);
        }
        if (shouldUseOfficial(config, price, usage)) {
            BigDecimal promptTokens = bd(nonNegative(usage.promptTokens()));
            BigDecimal cachedTokens = bd(Math.min(nonNegative(usage.cachedPromptTokens()), nonNegative(usage.promptTokens())));
            BigDecimal uncachedPromptTokens = promptTokens.subtract(cachedTokens).max(BigDecimal.ZERO);
            BigDecimal completionTokens = bd(nonNegative(usage.completionTokens()));
            BigDecimal inputCost = perMillion(uncachedPromptTokens, price.getInputPricePerMillion());
            BigDecimal cachedInputCost = perMillion(cachedTokens, cachedPrice(price));
            BigDecimal outputCost = perMillion(completionTokens, price.getOutputPricePerMillion());
            BigDecimal baseCost = money(inputCost.add(cachedInputCost).add(outputCost));
            BigDecimal serviceFeeRate = serviceFeeRate(config);
            BigDecimal serviceFee = money(baseCost.multiply(serviceFeeRate).divide(new BigDecimal("100"), MONEY_SCALE + 4, RoundingMode.HALF_UP));
            return new Result(
                BILLING_MODE_OFFICIAL_TOKEN,
                StrUtil.blankToDefault(price.getCurrency(), "USD"),
                null,
                baseCost,
                serviceFeeRate,
                serviceFee,
                money(baseCost.add(serviceFee)),
                pricingSnapshot(price)
            );
        }
        // 业务批4 Q4-A：官方价＋每次固定服务费模式（用户计费意图：官方价打底、每次调用加收一笔）
        if (isOfficialPlusFee(config)) {
            return officialPlusFee(config, price, usage);
        }
        return fixed(config);
    }

    /** 业务批4 Q4-A：OFFICIAL_PLUS_FEE 模式判定（billing_mode 精确匹配，空值不进此支） */
    private boolean isOfficialPlusFee(DcsAiConfig config) {
        return config != null && BILLING_MODE_OFFICIAL_PLUS_FEE.equals(config.getBillingMode());
    }

    /**
     * 官方价＋每次固定服务费（业务批4 Q4-A，2026-10-13 拍板）：
     * 实扣 = 官方 token 成本（与 OFFICIAL_TOKEN 同口径，含缓存输入分价）
     *       ＋ unitPricePerCall（本模式下语义=每次调用的固定服务费，缺省沿用 0.01 防免费用）。
     * 服务费率百分比在本模式不叠加（单一费用形状防双收）；价格行/usage 缺失时官方部分记 0、
     * 服务费照收——与 FIXED_PER_CALL 的兜底方向一致（宁多收一分不免费敞口）。
     * 币种（批4回修 dspro P1-2）：钱包仅 CNY，服务费恒为人民币——官方部分只在价格行= CNY 时
     * 计入；价格行非 CNY（探测/体检路径可绕过 CNY 闸）时官方部分记 0 只收服务费（与
     * recordTransactional 非 CNY 拒扣同向），snapshot 留 officialSkipped 痕供对账。
     */
    private Result officialPlusFee(DcsAiConfig config, DcsAiModelPrice price, AiTokenUsageSnapshot usage) {
        BigDecimal fee = resolveUnitPrice(config);
        BigDecimal base = BigDecimal.ZERO;
        String snapshot = "{\"mode\":\"OFFICIAL_PLUS_FEE\"}";
        if (price != null && usage != null && usage.hasTokenUsage()) {
            if ("CNY".equalsIgnoreCase(StrUtil.blankToDefault(price.getCurrency(), "CNY"))) {
                BigDecimal promptTokens = bd(nonNegative(usage.promptTokens()));
                BigDecimal cachedTokens = bd(Math.min(nonNegative(usage.cachedPromptTokens()), nonNegative(usage.promptTokens())));
                BigDecimal uncachedPromptTokens = promptTokens.subtract(cachedTokens).max(BigDecimal.ZERO);
                BigDecimal completionTokens = bd(nonNegative(usage.completionTokens()));
                base = money(perMillion(uncachedPromptTokens, price.getInputPricePerMillion())
                    .add(perMillion(cachedTokens, cachedPrice(price)))
                    .add(perMillion(completionTokens, price.getOutputPricePerMillion())));
                snapshot = pricingSnapshot(price);
            } else {
                snapshot = "{\"mode\":\"OFFICIAL_PLUS_FEE\",\"officialSkipped\":\"NON_CNY_PRICE\"}";
            }
        }
        return new Result(
            BILLING_MODE_OFFICIAL_PLUS_FEE,
            "CNY",
            fee,
            base,
            BigDecimal.ZERO,
            fee,
            money(base.add(fee)),
            snapshot
        );
    }

    private boolean shouldUseOfficial(DcsAiConfig config, DcsAiModelPrice price, AiTokenUsageSnapshot usage) {
        return config != null
            && BILLING_MODE_OFFICIAL_TOKEN.equals(StrUtil.blankToDefault(config.getBillingMode(), BILLING_MODE_OFFICIAL_TOKEN))
            && price != null
            && usage != null
            && usage.hasTokenUsage();
    }

    private Result fixed(DcsAiConfig config) {
        BigDecimal unitPrice = resolveUnitPrice(config);
        return new Result(
            BILLING_MODE_FIXED_PER_CALL,
            "CNY",
            unitPrice,
            unitPrice,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            unitPrice,
            "{\"mode\":\"FIXED_PER_CALL\"}"
        );
    }

    private Result zero(DcsAiConfig config, DcsAiModelPrice price) {
        String mode = config == null ? BILLING_MODE_FIXED_PER_CALL : StrUtil.blankToDefault(config.getBillingMode(), BILLING_MODE_FIXED_PER_CALL);
        String currency = price == null ? "CNY" : StrUtil.blankToDefault(price.getCurrency(), "USD");
        return new Result(mode, currency, null, BigDecimal.ZERO, serviceFeeRate(config), BigDecimal.ZERO, BigDecimal.ZERO, "{}");
    }

    private BigDecimal resolveUnitPrice(DcsAiConfig config) {
        if (config != null && config.getUnitPricePerCall() != null && config.getUnitPricePerCall().compareTo(BigDecimal.ZERO) >= 0) {
            return config.getUnitPricePerCall();
        }
        return DEFAULT_UNIT_PRICE;
    }

    private BigDecimal serviceFeeRate(DcsAiConfig config) {
        if (config == null || config.getServiceFeeRate() == null || config.getServiceFeeRate().compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return config.getServiceFeeRate();
    }

    private BigDecimal cachedPrice(DcsAiModelPrice price) {
        if (price.getCachedInputPricePerMillion() != null && price.getCachedInputPricePerMillion().compareTo(BigDecimal.ZERO) >= 0) {
            return price.getCachedInputPricePerMillion();
        }
        return price.getInputPricePerMillion();
    }

    private BigDecimal perMillion(BigDecimal tokens, BigDecimal pricePerMillion) {
        if (pricePerMillion == null || pricePerMillion.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return tokens.multiply(pricePerMillion).divide(ONE_MILLION, MONEY_SCALE + 4, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(value, 0);
    }

    private String pricingSnapshot(DcsAiModelPrice price) {
        return "{"
            + "\"provider\":\"" + safe(price.getProvider()) + "\","
            + "\"modelName\":\"" + safe(price.getModelName()) + "\","
            + "\"currency\":\"" + safe(price.getCurrency()) + "\","
            + "\"inputPricePerMillion\":\"" + safe(price.getInputPricePerMillion()) + "\","
            + "\"cachedInputPricePerMillion\":\"" + safe(price.getCachedInputPricePerMillion()) + "\","
            + "\"outputPricePerMillion\":\"" + safe(price.getOutputPricePerMillion()) + "\","
            + "\"sourceUrl\":\"" + safe(price.getSourceUrl()) + "\""
            + "}";
    }

    private String safe(Object value) {
        return value == null ? "" : value.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 计费结果: 模式/币种/单价/基费/服务费率/服务费/总价 与 价格快照 JSON。
     */
    public record Result(
        String billingMode,
        String currency,
        BigDecimal unitPrice,
        BigDecimal baseCostAmount,
        BigDecimal serviceFeeRate,
        BigDecimal serviceFeeAmount,
        BigDecimal costAmount,
        String pricingSnapshot
    ) {
    }
}
