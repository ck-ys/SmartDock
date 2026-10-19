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

    /** 保留尾巴的最低 token 预算（trigger 的 1/10，下限 2000——近几轮原文不进摘要，保最新语义） */
    static int keepTokensFor(int triggerTokens) {
        return Math.max(2000, triggerTokens / 10);
    }

    /**
     * 生效配置（dspro 深审回修 2026-10-16）：
     * <ul>
     *   <li>triggerTokens/keepTokens 双显式——P14 Step5 实证模型接口不上报窗口
     *       （deepseek 族 getContextWindowSize()=0），且 keepTokens 不设（默认 -1）时
     *       中间件路径压缩静默跳过（middlewareE2e 配对实证）；</li>
     *   <li>triggerMessages(0)——框架默认 50 条会先于 token 阈值触发，把租户阈值架空
     *       （探针实测 builder 不设时 effective=50）；关掉后阈值语义=纯 token，与管理页
     *       文案、手动通道 below_threshold 判定三处同口径；</li>
     *   <li>flushBeforeCompact/offloadBeforeCompact 双关——框架默认开：flush 会额外发一次
     *       记忆抽取调用并写全局 MEMORY.md（跨租户共享，读进他人提示词）；offload 会把
     *       被压段落全量原文落共享 workspace（无租户命名空间、清理链不覆盖）。摘要压缩
     *       本身不依赖这两条，关闭止血；</li>
     *   <li>尾窗=keepTokens=trigger 的 1/10（下限 2000）——keepTokens>0 时框架走 token
     *       切点，keepMessages 不参与（determineCutoffIndex 字节码），不再传。</li>
     * </ul>
     */
    public static CompactionConfig buildConfig(int triggerTokens) {
        int trigger = Math.max(4000, triggerTokens);
        return CompactionConfig.builder()
            .triggerMessages(0)
            .triggerTokens(trigger)
            .keepTokens(keepTokensFor(trigger))
            .summaryPrompt(SUMMARY_PROMPT)
            .flushBeforeCompact(false)
            .offloadBeforeCompact(false)
            .build();
    }
}
