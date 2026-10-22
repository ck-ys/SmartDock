package org.dromara.assistant.core;

import io.agentscope.harness.agent.memory.compaction.CompactionConfig;

/**
 * 压缩资产（批17 Q2，2026-10-16 拍板A 双通道）：
 * 摘要提示词模板（ZCode 上下文裁剪九段式的中文业务裁剪版）+ 生效配置构建。
 *
 * <p>模板硬约束（P14 探针实证 + grill 定案）：</p>
 * <ul>
 *   <li>保留框架 <code>{messages}</code> 占位符——ConversationCompactor 以它替换待压缩消息全文，
 *       换掉占位符摘要直接失效；</li>
 *   <li>业务标识符（任务编号/明细编号/口径结论）必须原值进摘要——压缩后模型仍能按编号
 *       查库续跑（P14 判据：missionId 原值存活）；</li>
 *   <li>安全红线段逐字保留指令——红线在系统提示层本就压缩免疫（P14 Step2），
 *       这里再压一层是给"摘要被单独取用"的场景兜底。</li>
 * </ul>
 */
public final class AssistantCompactionAssets {

    private AssistantCompactionAssets() {
    }

    /** 摘要模板：九段结构化中文（用户诉求/关键决定/涉及任务/问题与修复/反馈偏好/待办/当前话题/下一步/红线） */
    public static final String SUMMARY_PROMPT = """
        你是 DCS 数据采集系统的会话摘要器。请把下面这段对话压缩成结构化中文摘要，\
        供后续对话在上下文空间不足时继续使用。要求：
        1. 用户核心诉求：用户到底想办成什么事（1~2 句）。
        2. 关键决定与结论：已拍板的口径、方案、结论，逐条列出，原词保留。
        3. 涉及的任务与记录：出现的任务编号、明细编号、人名等标识符必须原值保留，不得改写或省略。
        4. 已发现的问题与修复：报过什么错、怎么修的、修没修好。
        5. 用户反馈与偏好：用户明确表达过的满意/不满意/格式偏好，逐条保留。
        6. 待办与未决事项：还没办完、还在等结果的事项。
        7. 当前正在讨论的话题：最后一轮在聊什么。
        8. 建议的下一步：结合上文，接下来最该做什么。
        9. 安全红线：如果对话中出现过权限/红线相关约定（如"成员身份禁止发起催办"），逐字保留。
        只输出摘要本身，不要寒暄、不要复述本指令。对话全文如下：
        {messages}""";

    /**
     * 生效配置（批A-1 2026-10-22 重写；旧口径见 git 历史）：
     * <ul>
     *   <li>triggerTokens 由 {@link AssistantCompactionPolicy#deriveTriggerTokens} 按模型窗口派生，
     *       不再读租户固定阈值（Q1-A 拍板：窗口 − 输出预留 − 1.3 万）；</li>
     *   <li>triggerMessages(0)——框架默认 50 条会先于 token 阈值触发，把触发线架空；</li>
     *   <li>尾窗=按轮保留最后 1 轮原文（Q5-A）：keepTokens=0 让框架走消息切点
     *       （determineCutoffIndex 字节码：keepTokens&gt;0 走 token 切点，否则 keepMessages），
     *       keepMessages=2 即 user+assistant 一轮；切点落在工具结果上时框架会前移避让；</li>
     *   <li>flushBeforeCompact/offloadBeforeCompact 双关——框架默认开：flush 会额外发一次
     *       记忆抽取调用并写全局 MEMORY.md（跨租户共享，读进他人提示词）；offload 会把
     *       被压段落全量原文落共享 workspace（无租户命名空间、清理链不覆盖）。</li>
     * </ul>
     */
    public static CompactionConfig buildConfig(int triggerTokens) {
        int trigger = Math.max(AssistantCompactionPolicy.MIN_TRIGGER_TOKENS, triggerTokens);
        return CompactionConfig.builder()
            .triggerMessages(0)
            .triggerTokens(trigger)
            .keepTokens(0)
            .keepMessages(AssistantCompactionPolicy.KEEP_MESSAGES)
            .summaryPrompt(SUMMARY_PROMPT)
            .flushBeforeCompact(false)
            .offloadBeforeCompact(false)
            .build();
    }
}
