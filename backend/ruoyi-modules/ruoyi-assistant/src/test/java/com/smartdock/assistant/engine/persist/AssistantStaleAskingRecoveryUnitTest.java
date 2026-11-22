package com.smartdock.assistant.engine.persist;

import com.smartdock.assistant.domain.model.AssistantRun;
import com.smartdock.assistant.engine.core.AssistantInstanceContext;
import com.smartdock.assistant.mapper.AssistantRunMapper;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    /** 多实例批 3 起构造函数三参：这两个不 mock 时 @InjectMocks 注入 null，
     *  ownerIsGone 里 runMapper.selectByThread 直接 NPE，被 recoverGoneOwners 的
     *  catch(Exception) 吞成「只告警」——测试于是静默变成「update 永不调用」。
     *  批 10（2026-11-22）补全，让「检出即翻转」这条断言真的走到 UPDATE。 */
    @Mock
    private AssistantRunMapper runMapper;

    @Mock
    private AssistantInstanceContext instanceContext;

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
        // 最新 run = INTERRUPTED + 有归属但租约已死 → 允许自愈。
        // 注意不能写成 ownerInstance=null：按原契约「INTERRUPTED 且无归属=旧数据不碰」
        // （unknownIsGone=false，见 ownerIsGone(run,false)），那走的是拒绝分支。
        AssistantRun run = new AssistantRun();
        run.setStatus("INTERRUPTED");
        run.setOwnerInstance("dead-instance");
        when(runMapper.selectByThread(anyString(), anyString(), anyLong(), anyInt()))
            .thenReturn(List.of(run));
        when(instanceContext.isAlive("dead-instance")).thenReturn(false);

        recovery.afterSingletonsInstantiated();

        verify(jdbcTemplate).update(AssistantStaleAskingRecovery.SQL_RECOVER,
            "t:000000:1:8d1209d0-3caf-4ceb-9c40-67035a72f476");
    }

    @Test
    void 活主会话_不翻转() {
        // 反向护栏（批 10 补）：owner 还活着时绝不能碰——否则滚动重启会把另一台正在等
        // 用户确认的会话翻成 allowed，HITL 直接卡死。没有这条，上面的绿也可能是「碰巧都翻」
        when(jdbcTemplate.queryForList(AssistantStaleAskingRecovery.SQL_DETECT, String.class))
            .thenReturn(List.of("t:000000:1:8d1209d0-3caf-4ceb-9c40-67035a72f476"));
        AssistantRun run = new AssistantRun();
        run.setStatus("INTERRUPTED");
        run.setOwnerInstance("other-instance");
        when(runMapper.selectByThread(anyString(), anyString(), anyLong(), anyInt()))
            .thenReturn(List.of(run));
        when(instanceContext.isAlive("other-instance")).thenReturn(true);

        recovery.afterSingletonsInstantiated();

        verify(jdbcTemplate, never()).update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
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
