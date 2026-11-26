package com.smartdock.assistant.infrastructure;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 额度三级管控批（2026-11-26 / Q5=1 + Q9=2）：个人积分闸单测。
 *
 * <p>钉四件事：① 额度内放行；② 达到上限拒绝且文案带「明日 0 点恢复」；
 * ③ 不限（-1）与脏值（0/负）一律放行；④ <b>没有缓存</b>——连查两次打两次账本，
 * 第二次能立刻看到新用量（板子 Q9 案 2 的口径，加了缓存就退化成「最多超 30 秒的量」）。</p>
 *
 * <p>{@code @Tag("local")} 进常规门禁；纯 Mockito 不起 Spring。</p>
 */
@Tag("local")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersonalCreditGateUnitTest {

    private static final String TENANT = "000000";
    private static final long USER = 9100000001L;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AssistantDailyQuota quota() {
        return new AssistantDailyQuota(jdbcTemplate);
    }

    @Test
    void 额度内_放行不抛() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any()))
            .thenReturn(40L);
        assertThatCode(() -> quota().checkPersonalCredits(TENANT, USER, 100, "进门"))
            .doesNotThrowAnyException();
    }

    @Test
    void 已到上限_拒绝且带恢复时点() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any()))
            .thenReturn(100L);
        assertThatThrownBy(() -> quota().checkPersonalCredits(TENANT, USER, 100, "进门"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("今天的个人额度用完了（100 积分）")
            .hasMessageContaining("明日 0 点恢复")
            .extracting(e -> ((ServiceException) e).getCode())
            .isEqualTo(429);
    }

    @Test
    void 超了上限也拒绝_不只是等于() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any()))
            .thenReturn(130L);
        assertThatThrownBy(() -> quota().checkPersonalCredits(TENANT, USER, 100, "调模型前"))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void 不限负一_直接放行_不查账本() {
        assertThatCode(() -> quota().checkPersonalCredits(TENANT, USER, -1, "进门"))
            .doesNotThrowAnyException();
        verify(jdbcTemplate, times(0)).queryForObject(anyString(), eq(Long.class), any(), any(), any(), any());
    }

    @Test
    void 脏值零和负_也放行_不把人锁死() {
        // 0 在积分列上不合法（保存侧已拒）；真出现脏值按「没设上限」放行，比锁死用户安全
        assertThatCode(() -> quota().checkPersonalCredits(TENANT, USER, 0, "进门"))
            .doesNotThrowAnyException();
        assertThatCode(() -> quota().checkPersonalCredits(TENANT, USER, -7, "进门"))
            .doesNotThrowAnyException();
        verify(jdbcTemplate, times(0)).queryForObject(anyString(), eq(Long.class), any(), any(), any(), any());
    }

    @Test
    void 账本读失败_放行不拦() {
        // 与 checkBudget 的「读失败不拦」同口径：不让账本闪断把已过闸用户挡在门外
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any()))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        assertThatCode(() -> quota().checkPersonalCredits(TENANT, USER, 100, "进门"))
            .doesNotThrowAnyException();
    }

    @Test
    void 没有缓存_连查两次打两次账本_第二次立刻看到新用量() {
        // Q9 案 2 的口径：每次调模型前直查账本。第一次 60 放行，账本涨到 100 后第二次立刻被拦
        // ——中间不 sleep、不清任何缓存，证明真的没有缓存层
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any()))
            .thenReturn(60L, 100L);
        AssistantDailyQuota q = quota();
        assertThatCode(() -> q.checkPersonalCredits(TENANT, USER, 100, "进门"))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> q.checkPersonalCredits(TENANT, USER, 100, "调模型前"))
            .isInstanceOf(ServiceException.class);
        verify(jdbcTemplate, times(2)).queryForObject(anyString(), eq(Long.class), any(), any(), any(), any());
    }

    @Test
    void 查用量_空结果按零_异常按负一() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any()))
            .thenReturn(null);
        assertThat(quota().creditsUsedToday(TENANT, USER)).isZero();
    }

    @Test
    void 查用量_异常返回负一表示查不到() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any()))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        assertThat(quota().creditsUsedToday(TENANT, USER)).isEqualTo(-1L);
    }

    @Test
    void 查用量_租户或用户为空_直接负一不查库() {
        assertThat(quota().creditsUsedToday(null, USER)).isEqualTo(-1L);
        assertThat(quota().creditsUsedToday(TENANT, null)).isEqualTo(-1L);
        verify(jdbcTemplate, times(0)).queryForObject(anyString(), eq(Long.class), any(), any(), any(), any());
    }
}
