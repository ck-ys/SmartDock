package com.smartdock.assistant.service;

import com.smartdock.assistant.mapper.AssistantActionMapper;
import com.smartdock.assistant.mapper.AssistantAttachmentMapper;
import com.smartdock.assistant.mapper.AssistantCompactionMapper;
import com.smartdock.assistant.mapper.AssistantConversationMapper;
import com.smartdock.assistant.mapper.AssistantMessageMapper;
import com.smartdock.assistant.mapper.AssistantRunIssueMapper;
import com.smartdock.assistant.mapper.AssistantRunMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清人（GDPR 式）与保留期清理的纯单测（批 7 · 7C-2）。
 *
 * <p>7C-2 把 {@code purgeUser} 七表事务、探表防御、agentscope 补偿队列、{@code retentionSweep}
 * 逐行搬进 {@link PurgeService}。搬移类改动的验证重点是「口径一字未变」，
 * 所以这里钉的都是**原实现里能出错的地方**，不是新行为：</p>
 *
 * <ol>
 *   <li><b>七表全删且返回合计</b>——漏一张就是 GDPR 半清除态，返回值又是前端唯一的观测口。</li>
 *   <li><b>探表防御</b>——{@code compaction} / {@code run_issue} 两张表在「先部署后迁移」的窗口里
 *       不存在；原实现在事务内先探表再删，否则 BadSqlGrammar 会把整个清人事务打回滚
 *       （五表一条不删、接口 500）。</li>
 *   <li><b>补偿队列</b>——agentscope 框架表清失败必须入队，且重试成功才出队；
 *       否则「七表已删 + 框架表残留」的半清除态会复活旧历史。</li>
 *   <li><b>保留期分批</b>——每张表都按 batch 循环到不足一批为止；只删一轮会让大表永远清不完。</li>
 * </ol>
 */
@Tag("local")
class PurgeServiceUnitTest {

    private static final String TENANT = "000000";
    private static final Long USER = 7L;

    private final AssistantMessageMapper messageMapper = mock(AssistantMessageMapper.class);
    private final AssistantConversationMapper conversationMapper = mock(AssistantConversationMapper.class);
    private final AssistantRunMapper runMapper = mock(AssistantRunMapper.class);
    private final AssistantActionMapper actionMapper = mock(AssistantActionMapper.class);
    private final AssistantAttachmentMapper attachmentMapper = mock(AssistantAttachmentMapper.class);
    private final AssistantCompactionMapper compactionMapper = mock(AssistantCompactionMapper.class);
    private final AssistantRunIssueMapper issueMapper = mock(AssistantRunIssueMapper.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private PurgeService svc() {
        return new PurgeService(messageMapper, conversationMapper, runMapper, actionMapper,
            attachmentMapper, compactionMapper, issueMapper, jdbcTemplate);
    }

    /** 探表查询：两张表都存在（正常环境） */
    private void bothTablesExist() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class))).thenReturn(Boolean.TRUE);
    }

    /** 探表查询：两张表都不存在（先部署后迁移的窗口） */
    private void bothTablesMissing() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class))).thenReturn(Boolean.FALSE);
    }

    @Test
    void 清人七表全删且返回合计() {
        bothTablesExist();
        when(messageMapper.purgeUser(TENANT, USER)).thenReturn(10);
        when(conversationMapper.purgeUser(TENANT, USER)).thenReturn(3);
        when(runMapper.purgeUser(TENANT, USER)).thenReturn(20);
        when(actionMapper.purgeUser(TENANT, USER)).thenReturn(5);
        when(attachmentMapper.purgeUser(TENANT, USER)).thenReturn(2);
        when(compactionMapper.purgeUser(TENANT, USER)).thenReturn(4);
        when(issueMapper.purgeUser(TENANT, USER)).thenReturn(6);

        assertEquals(50, svc().purgeUser(TENANT, USER),
            "返回值=七表合计（10+3+20+5+2+4+6）——前端唯一观测口，少算一表就是静默半清除");

        // 七表一个都不能漏（漏表=GDPR 半清除态）
        verify(messageMapper).purgeUser(TENANT, USER);
        verify(conversationMapper).purgeUser(TENANT, USER);
        verify(runMapper).purgeUser(TENANT, USER);
        verify(actionMapper).purgeUser(TENANT, USER);
        verify(attachmentMapper).purgeUser(TENANT, USER);
        verify(compactionMapper).purgeUser(TENANT, USER);
        verify(issueMapper).purgeUser(TENANT, USER);
    }

    @Test
    void 探表缺失时两张表跳过不炸事务() {
        bothTablesMissing();
        when(messageMapper.purgeUser(TENANT, USER)).thenReturn(1);
        when(conversationMapper.purgeUser(TENANT, USER)).thenReturn(1);
        when(runMapper.purgeUser(TENANT, USER)).thenReturn(1);
        when(actionMapper.purgeUser(TENANT, USER)).thenReturn(1);
        when(attachmentMapper.purgeUser(TENANT, USER)).thenReturn(1);

        assertEquals(5, svc().purgeUser(TENANT, USER), "表不存在时两张表按 0 计，其余五表照删");

        verify(compactionMapper, never()).purgeUser(anyString(), any());
        verify(issueMapper, never()).purgeUser(anyString(), any());
    }

    @Test
    void 探表本身抛异常时按不存在处理() {
        // 探表 SQL 在极老库上可能直接语法/权限错——原实现 catch 后按「不存在」处理
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class)))
            .thenThrow(new IllegalStateException("relation does not exist"));
        when(messageMapper.purgeUser(TENANT, USER)).thenReturn(1);
        when(conversationMapper.purgeUser(TENANT, USER)).thenReturn(1);
        when(runMapper.purgeUser(TENANT, USER)).thenReturn(1);
        when(actionMapper.purgeUser(TENANT, USER)).thenReturn(1);
        when(attachmentMapper.purgeUser(TENANT, USER)).thenReturn(1);

        assertEquals(5, svc().purgeUser(TENANT, USER),
            "探表异常必须降级为「表不存在」而不是上抛——上抛会让清人整体 500");

        verify(compactionMapper, never()).purgeUser(anyString(), any());
        verify(issueMapper, never()).purgeUser(anyString(), any());
    }

    @Test
    void agentscope清理成功时不入补偿队列() {
        svc().purgeAgentscopeSessions(TENANT, USER);
        // 前缀必须是 t:{tenant}:{userId}:%——口径错了会删到别人的框架表
        verify(jdbcTemplate).update(
            eq("DELETE FROM agentscope.agentscope_sessions WHERE session_id LIKE ?"),
            eq("t:" + TENANT + ":" + USER + ":%"));
    }

    /**
     * 注：「框架表清理失败 → 入 Redis 补偿队列」这条路径**无法在纯单测里验**，原因是
     * {@code RedisUtils} 的静态初始化要拿 Spring 容器，脱离容器会抛
     * {@code ExceptionInInitializerError}（{@link Error}，原实现的 {@code catch (Exception)}
     * 抓不到，搬家前后一致）。该路径改由 7C-2 的活体清人验证覆盖：
     * 用专用账号建数据 → 清人 → 断言七表归零 + 框架表无残留 + 补偿队列为空。
     * 这里只钉它前面一步——SQL 前缀口径（见上一条用例），那是唯一能纯函数验的部分。
     */
    @Test
    void 补偿重试成功返回true_失败返回false() {
        PurgeService s = svc();
        assertTrue(s.retryPurgeAgentscopeSessions(TENANT, USER), "表在=清理成功，返回 true 供 job 出队");

        org.mockito.Mockito.doThrow(new IllegalStateException("still missing"))
            .when(jdbcTemplate).update(anyString(), any(Object[].class));
        assertEquals(false, s.retryPurgeAgentscopeSessions(TENANT, USER),
            "仍失败=false，job 留队下轮再试（表缺失环境=永久留队无害）");
    }

    @Test
    void 保留期清理每张表都按批循环到不足一批() {
        bothTablesExist();
        // 第一轮 5（=batch，继续），第二轮 2（<batch，停）
        when(messageMapper.deleteExpired(5)).thenReturn(5, 2);
        when(conversationMapper.deleteExpired(5)).thenReturn(5, 0);
        when(runMapper.deleteExpired(5)).thenReturn(1);
        when(actionMapper.deleteExpired(5)).thenReturn(0);
        when(attachmentMapper.deleteExpired(5)).thenReturn(3);
        when(compactionMapper.deleteExpired(5)).thenReturn(0);
        when(issueMapper.deleteExpired(5)).thenReturn(0);

        svc().retentionSweep(5);

        // message 走两轮（5 + 2），不是只删一轮
        verify(messageMapper, org.mockito.Mockito.times(2)).deleteExpired(5);
        // conversation 也走两轮（5 + 0）
        verify(conversationMapper, org.mockito.Mockito.times(2)).deleteExpired(5);
        // run 第一轮 1 < 5，只一轮
        verify(runMapper, org.mockito.Mockito.times(1)).deleteExpired(5);
        // 七张表都参与（漏表=该类数据无界增长）
        verify(attachmentMapper, org.mockito.Mockito.atLeastOnce()).deleteExpired(5);
        verify(compactionMapper, org.mockito.Mockito.atLeastOnce()).deleteExpired(5);
        verify(issueMapper, org.mockito.Mockito.atLeastOnce()).deleteExpired(5);
    }

    @Test
    void 保留期清理在表缺失时跳过两张表() {
        bothTablesMissing();
        when(messageMapper.deleteExpired(any(Integer.class).intValue())).thenReturn(0);
        when(conversationMapper.deleteExpired(any(Integer.class).intValue())).thenReturn(0);
        when(runMapper.deleteExpired(any(Integer.class).intValue())).thenReturn(0);
        when(actionMapper.deleteExpired(any(Integer.class).intValue())).thenReturn(0);
        when(attachmentMapper.deleteExpired(any(Integer.class).intValue())).thenReturn(0);

        svc().retentionSweep(10);

        verify(compactionMapper, never()).deleteExpired(any(Integer.class).intValue());
        verify(issueMapper, never()).deleteExpired(any(Integer.class).intValue());
    }

    @Test
    void 清人前取thread清单直通mapper() {
        when(conversationMapper.threadIdsOfUser(TENANT, USER)).thenReturn(List.of("t-1", "t-2"));
        assertEquals(List.of("t-1", "t-2"), svc().threadIdsOfUser(TENANT, USER),
            "清单必须来自删行之前——删完再查恒空，内存缓存就清不掉了");
    }
}
