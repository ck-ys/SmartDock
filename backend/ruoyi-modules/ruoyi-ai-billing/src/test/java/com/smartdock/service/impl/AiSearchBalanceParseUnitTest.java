package com.smartdock.service.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 额度三级管控批（2026-11-26 / Q16=1）余额探测解析 + 资源包估算口径单测
 * （板 07 末尾：「Q16 的探测解析（remaining → CNY、UNSUPPORTED → --）单列一类」）。
 *
 * <p>核心口径：<b>查不到就说查不到</b>——不许拿 0 顶替（0 会被读成「没钱了」），
 * 也不许把旧值当新值。资源包次数厂商没有查询接口，只能是「总量 − 本地累计调用」的估算，
 * 界面必须标「估算」。</p>
 */
@Tag("local")
class AiSearchBalanceParseUnitTest {

    /* ===== ① 正常响应 ===== */

    @Test
    void 官方响应解析出余额_字符串数字也认() {
        AiSearchServiceImpl.BalanceParse r = AiSearchServiceImpl.parseBalanceBody(
            "{\"code\":200,\"data\":{\"remaining\":\"12.34\"}}");
        assertThat(r.status()).isEqualTo(AiSearchServiceImpl.BalanceStatus.OK);
        assertThat(r.amount()).isEqualByComparingTo(new BigDecimal("12.34"));
        assertThat(r.message()).isNull();
    }

    @Test
    void 数字型remaining也认() {
        AiSearchServiceImpl.BalanceParse r = AiSearchServiceImpl.parseBalanceBody(
            "{\"code\":200,\"data\":{\"remaining\":0.5}}");
        assertThat(r.status()).isEqualTo(AiSearchServiceImpl.BalanceStatus.OK);
        assertThat(r.amount()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void 余额为零是真值_不是查不到() {
        // 0.00 是「账户真没钱了」，与「查不到」必须分开——前者能解释为什么搜不动
        AiSearchServiceImpl.BalanceParse r = AiSearchServiceImpl.parseBalanceBody(
            "{\"code\":200,\"data\":{\"remaining\":\"0.00\"}}");
        assertThat(r.status()).isEqualTo(AiSearchServiceImpl.BalanceStatus.OK);
        assertThat(r.amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void 余额为负照收_欠费是真实状态() {
        AiSearchServiceImpl.BalanceParse r = AiSearchServiceImpl.parseBalanceBody(
            "{\"code\":200,\"data\":{\"remaining\":\"-3.20\"}}");
        assertThat(r.status()).isEqualTo(AiSearchServiceImpl.BalanceStatus.OK);
        assertThat(r.amount()).isEqualByComparingTo(new BigDecimal("-3.20"));
    }

    /* ===== ② 三条失败路径各说各的人话 ===== */

    @Test
    void 空响应算失败_不给金额() {
        AiSearchServiceImpl.BalanceParse r = AiSearchServiceImpl.parseBalanceBody("");
        assertThat(r.status()).isEqualTo(AiSearchServiceImpl.BalanceStatus.ERROR);
        assertThat(r.amount()).isNull();
        assertThat(r.message()).isNotBlank();
    }

    @Test
    void 不是JSON算失败_提示核对接口() {
        AiSearchServiceImpl.BalanceParse r = AiSearchServiceImpl.parseBalanceBody("<html>502</html>");
        assertThat(r.status()).isEqualTo(AiSearchServiceImpl.BalanceStatus.ERROR);
        assertThat(r.amount()).isNull();
    }

    @Test
    void 缺remaining字段算失败_不编零() {
        AiSearchServiceImpl.BalanceParse r = AiSearchServiceImpl.parseBalanceBody(
            "{\"code\":200,\"data\":{}}");
        assertThat(r.status()).isEqualTo(AiSearchServiceImpl.BalanceStatus.ERROR);
        assertThat(r.amount()).isNull();
        assertThat(r.message()).contains("remaining");
    }

    @Test
    void remaining不是数字算失败_不是零() {
        AiSearchServiceImpl.BalanceParse r = AiSearchServiceImpl.parseBalanceBody(
            "{\"code\":200,\"data\":{\"remaining\":\"n/a\"}}");
        assertThat(r.status()).isEqualTo(AiSearchServiceImpl.BalanceStatus.ERROR);
        assertThat(r.amount()).isNull();
    }

    /* ===== ③ 资源包估算：总量 − 本地累计调用，下限 0 ===== */

    @Test
    void 资源包剩余等于总量减已用() {
        assertThat(AiSearchServiceImpl.packRemaining(1000, 51)).isEqualTo(949);
    }

    @Test
    void 没买包时剩余为空_界面显示破折号不是零比零() {
        assertThat(AiSearchServiceImpl.packRemaining(null, 12)).isNull();
    }

    @Test
    void 新接入还没搜过_已用当零() {
        assertThat(AiSearchServiceImpl.packRemaining(500, null)).isEqualTo(500);
    }

    @Test
    void 用超了剩余为零_不出现负数() {
        // 跨系统调用会让本地估算偏小，出现「用超」是正常的——显示 0 而不是 -200
        assertThat(AiSearchServiceImpl.packRemaining(1000, 1200)).isZero();
    }

    @Test
    void 管理员把总量改小到已用以下_剩余仍为零() {
        assertThat(AiSearchServiceImpl.packRemaining(100, 100)).isZero();
    }
}
