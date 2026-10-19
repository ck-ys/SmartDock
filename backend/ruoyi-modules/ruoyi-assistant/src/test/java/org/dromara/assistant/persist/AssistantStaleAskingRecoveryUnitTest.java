package org.dromara.assistant.persist;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务批7 Q16-①（2026-10-14）单元测试：陈旧 ASKING 挂起启动自愈。
 *
 * <p>覆盖口径：①库中无 asking 行=零动作（幂等快路径不写库）；②检出即按
 * SQL_RECOVER 翻转 asking→allowed（LIKE 双守卫=幂等）；③库不可达只告警不拦启动
 * （SmartInitializingSingleton 抛异常会炸整个 context，必须吞）。纯 Mockito 不起
 * Spring、不连库。SQL 文本断言锚定 JSON 结构级安全前提（字面量带引号、LIKE 守卫
 * 与 replace 同串），改 SQL 必须连带改测试防误漂移。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AssistantStaleAskingRecoveryUnitTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AssistantStaleAskingRecovery recovery;

    @Test
    void 无asking行_零动作不写库() {
        when(jdbcTemplate.queryForList(AssistantStaleAskingRecovery.SQL_DETECT, String.class))
            .thenReturn(List.of());

        recovery.afterSingletonsInstantiated();

        verify(jdbcTemplate, never()).update(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 检出陈旧悬挂_按守卫SQL翻转() {
        when(jdbcTemplate.queryForList(AssistantStaleAskingRecovery.SQL_DETECT, String.class))
            .thenReturn(List.of("t:000000:1:8d1209d0-3caf-4ceb-9c40-67035a72f476"));

        recovery.afterSingletonsInstantiated();

        verify(jdbcTemplate).update(AssistantStaleAskingRecovery.SQL_RECOVER);
    }

    @Test
    void 库不可达_只告警不拦启动() {
        when(jdbcTemplate.queryForList(AssistantStaleAskingRecovery.SQL_DETECT, String.class))
            .thenThrow(new DataAccessResourceFailureException("dev 库闪断"));

        assertThatCode(() -> recovery.afterSingletonsInstantiated())
            .doesNotThrowAnyException();
        verify(jdbcTemplate, never()).update(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void SQL锚点_字面量与LIKE守卫同串防漂移() {
        // JSON 结构级安全前提：字面量含裸引号（字符串值内引号必转义，不可能撞用户文本）
        assertThat(AssistantStaleAskingRecovery.SQL_RECOVER)
            .contains("\"state\":\"asking\"")
            .contains("\"state\":\"allowed\"")
            .doesNotContain("\"state\":\"denied\"");
        // LIKE 守卫必须锚同一个 asking 字面量（幂等=第二遍零行命中）
        assertThat(AssistantStaleAskingRecovery.SQL_RECOVER)
            .endsWith("AND state_data LIKE '%\"state\":\"asking\"%'");
        assertThat(AssistantStaleAskingRecovery.SQL_DETECT)
            .endsWith("AND state_data LIKE '%\"state\":\"asking\"%'");
    }
}
