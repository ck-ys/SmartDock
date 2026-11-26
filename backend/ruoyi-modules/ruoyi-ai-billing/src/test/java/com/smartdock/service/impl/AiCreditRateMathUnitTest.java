package com.smartdock.service.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 额度三级管控批（2026-11-26 / Q13=1 + Q22=1）倍率换算纯函数单测（板 07「② 倍率换算」）。
 *
 * <p>板上钉死的两个数是<b>两个不同的数</b>，这组测试就是防止它们又被混成一个：</p>
 * <ul>
 *   <li><b>相对价</b>（给人看）= 本模型一轮成本 ÷ 基准模型一轮成本，不参与扣分；</li>
 *   <li><b>扣分</b>（真扣）= 一轮成本（元）× 100 × 平台系数 × 手工系数。</li>
 * </ul>
 *
 * <p>「一轮」的形状：输入 2 万 token + 输出 800 token，缓存折扣不计
 * （与 {@code BillingModelDecorator#priceOf} 同口径）。</p>
 */
@Tag("local")
class AiCreditRateMathUnitTest {

    /* ===== ① 一轮成本：形状固定，缺项当 0 ===== */

    @Test
    void 一轮成本按两万输入加八百输出算_缓存折扣不计() {
        // 入价 2 元/百万 + 出价 8 元/百万 → 20000×2/1e6 + 800×8/1e6 = 0.04 + 0.0064
        BigDecimal cost = AiCreditServiceImpl.roundCostOfPrices(
            new BigDecimal("2.0"), new BigDecimal("8.0"));
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0464"));
    }

    @Test
    void 价目缺一项当零_缺输出价只算输入() {
        BigDecimal onlyIn = AiCreditServiceImpl.roundCostOfPrices(new BigDecimal("3.0"), null);
        assertThat(onlyIn).isEqualByComparingTo(new BigDecimal("0.06"));
        BigDecimal onlyOut = AiCreditServiceImpl.roundCostOfPrices(null, new BigDecimal("10.0"));
        assertThat(onlyOut).isEqualByComparingTo(new BigDecimal("0.008"));
    }

    @Test
    void 两项都缺是零成本_不是空() {
        // 0 成本与 null 在界面上说法不同：null = 连价目行都没有（显示「—」），0 = 真免费
        assertThat(AiCreditServiceImpl.roundCostOfPrices(null, null))
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /* ===== ② 相对价：基准自己 1.00，算不出就 null（不许编 0） ===== */

    @Test
    void 基准模型自己的相对价是一点零() {
        BigDecimal base = new BigDecimal("0.2016");
        assertThat(AiCreditServiceImpl.relativePriceOf(base, base)).isEqualTo(1.00d);
    }

    @Test
    void 相对价等于成本比_两位小数() {
        // 板上的算例：0.2016 基准，另一模型 0.0464 → 0.23
        assertThat(AiCreditServiceImpl.relativePriceOf(
            new BigDecimal("0.0464"), new BigDecimal("0.2016"))).isEqualTo(0.23d);
        // 贵的那个方向：1.7520 / 0.2016 = 8.69
        assertThat(AiCreditServiceImpl.relativePriceOf(
            new BigDecimal("1.7520"), new BigDecimal("0.2016"))).isEqualTo(8.69d);
    }

    @Test
    void 成本或基准算不出来_相对价是空_不是零() {
        assertThat(AiCreditServiceImpl.relativePriceOf(null, new BigDecimal("0.2"))).isNull();
        assertThat(AiCreditServiceImpl.relativePriceOf(new BigDecimal("0.2"), null)).isNull();
        // 基准为 0 会让除零——同样返回空
        assertThat(AiCreditServiceImpl.relativePriceOf(new BigDecimal("0.2"), BigDecimal.ZERO)).isNull();
    }

    /* ===== ③ 手工系数归一：null / 0 / 负数 / 恰好 1 都是「自动」 ===== */

    @Test
    void 手工系数为空或一都算自动() {
        assertThat(AiCreditServiceImpl.resolveManualFactor(null)).isNull();
        assertThat(AiCreditServiceImpl.resolveManualFactor(BigDecimal.ONE)).isNull();
    }

    @Test
    void 手工系数零或负也算自动_不是免费() {
        // 0/负数在库里是脏值；当自动（×1）而不是当 0 倍——0 倍会让这个模型永远不扣分
        assertThat(AiCreditServiceImpl.resolveManualFactor(BigDecimal.ZERO)).isNull();
        assertThat(AiCreditServiceImpl.resolveManualFactor(new BigDecimal("-2"))).isNull();
    }

    @Test
    void 手工系数非一的正数照收() {
        assertThat(AiCreditServiceImpl.resolveManualFactor(new BigDecimal("2.5")))
            .isEqualByComparingTo(new BigDecimal("2.5"));
    }

    /* ===== ⑥ 写入口径（2026-11-26 活体实测缺陷回归） ===== */

    @Test
    void 恢复自动落一不落空_因为该列不允许为空() {
        // 缺陷实证：ai_config.credit_multiplier 是 NOT NULL DEFAULT 1，
        // 原实现「恢复自动」写 NULL → PSQLException → 接口 500（按钮点了报未知异常）。
        // 现在落 1：读侧 resolveManualFactor(1) = null = 自动，语义等价且不撞约束。
        assertThat(AiCreditServiceImpl.normalizeMultiplierForWrite(null))
            .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(AiCreditServiceImpl.normalizeMultiplierForWrite(BigDecimal.ONE))
            .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void 写入口径_非一的数原样落库() {
        assertThat(AiCreditServiceImpl.normalizeMultiplierForWrite(new BigDecimal("2")))
            .isEqualByComparingTo(new BigDecimal("2"));
    }

    @Test
    void 写入口径与读口径闭环_落一读回来是自动() {
        // 写 1 → 读侧 resolveManualFactor 必须判成「自动」；否则界面会一直显示「手工 ×1」
        BigDecimal written = AiCreditServiceImpl.normalizeMultiplierForWrite(null);
        assertThat(AiCreditServiceImpl.resolveManualFactor(written)).isNull();
    }

    /* ===== ④ 扣分 = 成本 × 100 × 平台系数 × 手工系数 ===== */

    @Test
    void 扣分是成本乘一百乘两个系数_四舍五入到整数() {
        // 0.0464 元 × 100 = 4.64 → 5 积分（HALF_UP）
        assertThat(AiCreditServiceImpl.perRoundOf(
            new BigDecimal("0.0464"), BigDecimal.ONE, null, false)).isEqualTo(5L);
    }

    @Test
    void 平台系数与手工系数相乘_顺序不影响结果() {
        BigDecimal cost = new BigDecimal("0.1");
        long a = AiCreditServiceImpl.perRoundOf(cost, new BigDecimal("1.5"), new BigDecimal("2"), false);
        // 0.1 × 100 × 1.5 × 2 = 30
        assertThat(a).isEqualTo(30L);
    }

    @Test
    void 免费档扣分是零_不看成本() {
        assertThat(AiCreditServiceImpl.perRoundOf(
            new BigDecimal("9.99"), BigDecimal.ONE, null, true)).isZero();
    }

    @Test
    void 成本算不出来时扣分是零_不抛异常() {
        // 倍率表要能整表渲染——单行缺价目不能把整张表搞崩（该行同时带 costKnown=false 显示「—」）
        assertThat(AiCreditServiceImpl.perRoundOf(null, BigDecimal.ONE, null, false)).isZero();
    }

    @Test
    void 平台系数非法当一_不会把扣分抹成零() {
        assertThat(AiCreditServiceImpl.perRoundOf(
            new BigDecimal("0.1"), BigDecimal.ZERO, null, false)).isEqualTo(10L);
        assertThat(AiCreditServiceImpl.perRoundOf(
            new BigDecimal("0.1"), new BigDecimal("-3"), null, false)).isEqualTo(10L);
    }

    /* ===== ⑤ 平台系数解析：写错的配置不能影响全平台扣分 ===== */

    @Test
    void 平台系数没配时按一_等于老口径不改变扣分() {
        assertThat(AiCreditServiceImpl.parsePlatformFactor(null)).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(AiCreditServiceImpl.parsePlatformFactor("  ")).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void 平台系数写错回落一_不是零也不是负数() {
        assertThat(AiCreditServiceImpl.parsePlatformFactor("abc")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(AiCreditServiceImpl.parsePlatformFactor("0")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(AiCreditServiceImpl.parsePlatformFactor("-1.5")).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void 平台系数合法值照收_含小数() {
        assertThat(AiCreditServiceImpl.parsePlatformFactor("1.2"))
            .isEqualByComparingTo(new BigDecimal("1.2"));
        assertThat(AiCreditServiceImpl.parsePlatformFactor(" 3 "))
            .isEqualByComparingTo(new BigDecimal("3"));
    }
}
