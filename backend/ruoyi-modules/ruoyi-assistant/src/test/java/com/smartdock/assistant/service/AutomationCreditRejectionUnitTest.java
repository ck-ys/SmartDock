package com.smartdock.assistant.service;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 额度三级管控批（2026-11-26 / Q6 补条件）：自动化「额度拒绝不计连续失败」判定单测。
 *
 * <p>为什么要有这组测试：板上写得很清楚——只修「进门那几道闸」不够。模型阶段抛出来的
 * 额度异常走另一条 catch，那里按「有没有挂外部工具」判是否计失败，默认<b>计失败</b>，
 * 连续 3 次整条自动化自动停用。这正是「还剩 5 分、这次要 10 分」走的路。</p>
 *
 * <p>同时钉住边界：<b>不能把厂商 429 / 网络错认成额度拒绝</b>——那会把真正坏掉的自动化
 * 也永远停不下来（与 mountedMcp 判据同一类风险）。</p>
 */
@Tag("local")
class AutomationCreditRejectionUnitTest {

    @Test
    void 自家闸的拒绝_认出来() {
        ServiceException e = new ServiceException("今天的个人额度用完了（100 积分），明日 0 点恢复。"
            + "如需调整额度，请联系管理员", 429);
        assertThat(AutomationRunner.isPersonalCreditRejection(e)).isTrue();
    }

    @Test
    void 被框架包了一层_也能沿cause认出来() {
        // 框架/装饰器会把异常包一层再抛，只看最外层 getMessage() 会漏
        RuntimeException wrapped = new RuntimeException("stream failed",
            new ServiceException("今天的个人额度用完了（50 积分），明日 0 点恢复", 429));
        assertThat(AutomationRunner.isPersonalCreditRejection(wrapped)).isTrue();
    }

    @Test
    void 埋在suppressed里_也能认出来() {
        RuntimeException outer = new RuntimeException("react loop aborted");
        outer.addSuppressed(new ServiceException("今天的个人额度用完了（100 积分）", 429));
        assertThat(AutomationRunner.isPersonalCreditRejection(outer)).isTrue();
    }

    @Test
    void 厂商429限流_不算额度拒绝_照常计失败() {
        // 边界：厂商限流是外部因素，但那已经由 mountedMcp 判据覆盖；这里不越界扩大，
        // 否则真正坏掉的自动化会永远停不下来
        assertThat(AutomationRunner.isPersonalCreditRejection(
            new RuntimeException("429 Too Many Requests: rate limit exceeded"))).isFalse();
    }

    @Test
    void 普通失败_不算额度拒绝() {
        assertThat(AutomationRunner.isPersonalCreditRejection(
            new RuntimeException("Connection reset"))).isFalse();
        assertThat(AutomationRunner.isPersonalCreditRejection(new ServiceException("模型没有返回内容"))).isFalse();
    }

    @Test
    void 空异常_不炸() {
        assertThat(AutomationRunner.isPersonalCreditRejection(null)).isFalse();
        assertThat(AutomationRunner.isPersonalCreditRejection(new RuntimeException())).isFalse();
    }

    @Test
    void 自引用cause不死循环() {
        // 防御：cur.getCause()==cur 的自环（某些框架包装器会造）必须能退出
        RuntimeException self = new RuntimeException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(AutomationRunner.isPersonalCreditRejection(self)).isFalse();
    }
}
