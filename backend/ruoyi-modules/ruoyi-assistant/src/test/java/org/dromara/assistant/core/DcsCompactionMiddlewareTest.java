package org.dromara.assistant.core;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微压缩单测（批A-1/A-2，2026-10-22 拍板 Q6-A）：
 * 到触发线 9 成才动手；只清最近 5 组之外的工具结果；读取类/媒体结果不动；
 * 上下文只换内容不删消息。
 */
@Tag("dev")
class DcsCompactionMiddlewareTest {

    private static final String BIG = "这是一段很长的查询结果，用来模拟真实的数据库返回内容。".repeat(200);

    private static DcsCompactionMiddleware middleware(int triggerTokens) {
        // 构造器只存字段（2.0.4 字节码实证）；微压缩路径不碰 workspace/model/service
        return new DcsCompactionMiddleware(null, null,
            AssistantCompactionAssets.buildConfig(triggerTokens), null);
    }

    /** assistant(tool_use) + tool(result) 一组，共 n 组（消息带显式 id——真实链路同款） */
    private static AgentState stateWithGroups(int n, String toolName, boolean media) {
        List<Msg> ctx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ctx.add(Msg.builder().id("a-" + i).role(MsgRole.ASSISTANT).name("assistant")
                .content(new ToolUseBlock("call-" + i, toolName, Map.of()))
                .build());
            List<io.agentscope.core.message.ContentBlock> out = new ArrayList<>();
            if (media) {
                out.add(ImageBlock.builder().source(new Source()).build());
            } else {
                out.add(TextBlock.builder().text(BIG).build());
            }
            ctx.add(Msg.builder().id("t-" + i).role(MsgRole.TOOL).name(toolName)
                .content(new ToolResultBlock("call-" + i, toolName, out))
                .build());
        }
        return AgentState.builder().sessionId("t1").userId("t:000000:1").context(ctx).build();
    }

    private static long clearedCount(AgentState state) {
        return state.getContext().stream()
            .filter(DcsCompactionMiddlewareTest::isCleared)
            .count();
    }

    private static boolean isCleared(Msg m) {
        if (m.getRole() != MsgRole.TOOL) {
            return false;
        }
        for (io.agentscope.core.message.ContentBlock b : m.getContent()) {
            if (b instanceof ToolResultBlock tr && tr.getOutput() != null && tr.getOutput().size() == 1
                && tr.getOutput().get(0) instanceof TextBlock tb
                && AssistantCompactionPolicy.MICROCOMPACT_CLEARED_TEXT.equals(tb.getText())) {
                return true;
            }
        }
        return false;
    }

    /** tool 消息里原样保留的大文本条数 */
    private static long intactBigCount(AgentState state) {
        return state.getContext().stream()
            .filter((m) -> m.getRole() == MsgRole.TOOL)
            .filter((m) -> m.getContent().stream().anyMatch((b) -> b instanceof ToolResultBlock tr
                && tr.getOutput() != null && tr.getOutput().size() == 1
                && tr.getOutput().get(0) instanceof TextBlock tb && BIG.equals(tb.getText())))
            .count();
    }

    private static long imageBlockCount(AgentState state) {
        return state.getContext().stream()
            .flatMap((m) -> m.getContent().stream())
            .filter((b) -> b instanceof ToolResultBlock tr)
            .flatMap((b) -> ((ToolResultBlock) b).getOutput().stream())
            .filter((b) -> b instanceof ImageBlock)
            .count();
    }

    @Test
    void clearsOldGroupsKeepsRecentFive() {
        DcsCompactionMiddleware mw = middleware(4_000);
        AgentState state = stateWithGroups(8, "query_mission", false);
        int beforeSize = state.getContext().size();

        DcsCompactionMiddleware.MicrocompactResult res = mw.microcompact(state);

        assertThat(res.savedTokens()).isPositive();
        // 替换件携带原 id——供本次调用输入快照做同款替换（三审 P1-2）
        assertThat(res.replacements()).hasSize(3);
        // 8 组留最近 5 组 → 清 3 组；消息只替换不删除
        assertThat(clearedCount(state)).isEqualTo(3);
        assertThat(state.getContext()).hasSize(beforeSize);
        // 最近 5 组原文还在
        assertThat(intactBigCount(state)).isEqualTo(5);
    }

    @Test
    void protectedToolsAndMediaAreNeverCleared() {
        DcsCompactionMiddleware mw = middleware(4_000);
        // read_attachment 是本仓 ToolRegistry 真实存在的读取类工具（二审 P2 后豁免清单只含真实工具）
        AgentState state = stateWithGroups(8, "read_attachment", false);
        mw.microcompact(state);
        // 读取类工具全组豁免：一条都不清
        assertThat(clearedCount(state)).isZero();
        assertThat(state.getContext()).hasSize(16);

        AgentState mediaState = stateWithGroups(8, "query_mission", true);
        mw.microcompact(mediaState);
        // 媒体结果（图片等）不清；整组媒体结果都保留
        assertThat(imageBlockCount(mediaState)).isEqualTo(8);
        assertThat(clearedCount(mediaState)).isZero();
    }

    /** 三审 P1-2 回修：清理结果须同步进本次调用输入（按 id 替换，其余消息不动） */
    @Test
    void clearedMessagesArePropagatedToInputSnapshotById() {
        DcsCompactionMiddleware mw = middleware(4_000);
        AgentState state = stateWithGroups(8, "query_mission", false);
        // 输入快照在微压缩【之前】由框架拍下（P1-2 的核心场景）——这里按同顺序复制
        java.util.List<Msg> snapshot = new java.util.ArrayList<>();
        snapshot.add(Msg.builder().id("sys").role(MsgRole.SYSTEM)
            .content(TextBlock.builder().text("system head").build()).build());
        snapshot.addAll(state.getContext());

        var res = mw.microcompact(state);
        assertThat(res.replacements()).isNotEmpty();

        var input = new io.agentscope.core.middleware.ReasoningInput(snapshot, java.util.List.of(), null);
        var effective = DcsCompactionMiddleware.withClearedMessages(input, res.replacements());

        assertThat(effective).isNotSameAs(input);
        assertThat(effective.messages()).hasSize(snapshot.size());
        // system 头原样保留
        assertThat(effective.messages().get(0).getId()).isEqualTo("sys");
        // 被清理的消息在输入里也变成了占位符
        long clearedInInput = effective.messages().stream().filter(DcsCompactionMiddlewareTest::isCleared).count();
        assertThat(clearedInInput).isEqualTo(res.replacements().size());
        // 无替换件时输入原样返回（零开销路径）
        assertThat(DcsCompactionMiddleware.withClearedMessages(input, java.util.List.of())).isSameAs(input);
    }

    /** 三审 P1-1 回修：2.0.4 两类失败形态都套着模板前缀——startsWith 判不中，必须 contains */
    @Test
    void summaryFailureFormsAreDetectedBehindTemplateWrapper() {
        String wrapper = "Here is a summary of the conversation to date:\n\n";
        assertThat(DcsCompactionMiddleware.summaryFailed(summaryRow(wrapper + "(Summarization failed: 500)"))).isTrue();
        assertThat(DcsCompactionMiddleware.summaryFailed(summaryRow(wrapper + "(Summary unavailable)"))).isTrue();
        assertThat(DcsCompactionMiddleware.summaryFailed(summaryRow(wrapper + "用户诉求：…"))).isFalse();
        assertThat(DcsCompactionMiddleware.summaryFailed(java.util.List.of())).isFalse();
        // 失败占位不落审计表；成功摘要剥掉模板前缀只存正文
        assertThat(DcsCompactionMiddleware.extractSummary(summaryRow(wrapper + "(Summary unavailable)"))).isNull();
        assertThat(DcsCompactionMiddleware.extractSummary(summaryRow(wrapper + "用户诉求：查任务")))
            .isEqualTo("用户诉求：查任务");
    }

    private static java.util.List<Msg> summaryRow(String text) {
        return java.util.List.of(Msg.builder()
            .name(io.agentscope.harness.agent.memory.compaction.ConversationCompactor.SUMMARY_MSG_NAME)
            .role(MsgRole.USER)
            .content(TextBlock.builder().text(text).build())
            .build());
    }

    /** 省不够 256 token 必须整体回滚（不留半清理状态） */
    @Test
    void rollsBackWhenSavingsBelowFloor() {
        DcsCompactionMiddleware mw = middleware(4_000);
        // 最近 5 组撑过阈值，最老 3 组只有小结果 → 清完省不了 256 → 回滚
        java.util.List<Msg> ctx = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ctx.addAll(toolGroup("tiny-" + i, "short result " + i));
        }
        for (int i = 3; i < 8; i++) {
            ctx.addAll(toolGroup("big-" + i, BIG));
        }
        AgentState state = AgentState.builder().userId("t:000000:1").context(ctx).build();

        var res = mw.microcompact(state);

        assertThat(res.savedTokens()).isZero();
        assertThat(res.replacements()).isEmpty();
        assertThat(clearedCount(state)).isZero();
        assertThat(intactBigCount(state)).isEqualTo(5);
    }

    private static java.util.List<Msg> toolGroup(String key, String text) {
        return java.util.List.of(
            Msg.builder().role(MsgRole.ASSISTANT).name("assistant")
                .content(new ToolUseBlock("call-" + key, "query_mission", java.util.Map.of())).build(),
            Msg.builder().role(MsgRole.TOOL).name("query_mission")
                .content(new ToolResultBlock("call-" + key, "query_mission",
                    java.util.List.of(TextBlock.builder().text(text).build()))).build()
        );
    }

    @Test
    void belowThresholdLeavesContextUntouched() {
        DcsCompactionMiddleware mw = middleware(4_000);
        // 一条短结果：远低于 9 成触发线（2000 token）
        AgentState state = AgentState.builder().userId("t:000000:1").context(List.of(
            Msg.builder().role(MsgRole.ASSISTANT).name("assistant")
                .content(new ToolUseBlock("c1", "query_mission", Map.of())).build(),
            Msg.builder().role(MsgRole.TOOL).name("query_mission")
                .content(new ToolResultBlock("c1", "query_mission",
                    List.of(TextBlock.builder().text("只有一条短结果").build()))).build()
        )).build();

        assertThat(mw.microcompact(state).savedTokens()).isZero();
        assertThat(clearedCount(state)).isZero();
    }

    @Test
    void savesNothingWhenOnlyFiveGroupsExist() {
        DcsCompactionMiddleware mw = middleware(4_000);
        // 大文本确保到阈值，但只有 5 组 → clearGroups=0，不清
        AgentState state = stateWithGroups(5, "query_mission", false);
        assertThat(mw.microcompact(state).savedTokens()).isZero();
        assertThat(clearedCount(state)).isZero();
    }
}
