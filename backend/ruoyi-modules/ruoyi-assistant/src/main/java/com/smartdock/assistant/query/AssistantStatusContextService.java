package com.smartdock.assistant.query;

import com.smartdock.assistant.domain.gateway.ConversationReadGateway;
import com.smartdock.assistant.domain.gateway.PromptCatalogGateway;
import com.smartdock.assistant.domain.model.DigestResult;
import com.smartdock.assistant.domain.vo.ContextBreakdownVo;
import com.smartdock.assistant.infrastructure.AssistantModelFactory;
import com.smartdock.assistant.service.AssistantMemoryService;
import com.smartdock.assistant.service.AssistantSkillVisibilityService;
import com.smartdock.assistant.support.ContextBreakdownEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 本会话上下文构成（{@code GET /assistant/status/context} 的主体，批 7 · 7A，2026-11-20）。
 *
 * <p><b>纯搬移</b>：口径与 {@code AssistantStatusController#statusContext} 逐行一致——
 * 七段字符占比（0 段不下发）、memory 单列、discipline-urge 剔除、约 0% 剔除、低置信标注；
 * 只是把取数收进两个 gateway、把归属降级收进本类。legacy core 静态资产与 legacy 会话服务
 * 都不在本类出现（规则 8：新根 service 不许 import legacy）。</p>
 *
 * <p>7B/7C 边界：本类只读会话状态与目录，不碰 run 记录、SSE、会话写路径。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantStatusContextService {

    /** 消息尾部上限（防全表；工具结果不落库，见实体注释） */
    private static final int MESSAGES_TAIL_LIMIT = 500;

    private final PromptCatalogGateway promptCatalog;
    private final ConversationReadGateway conversationRead;
    private final AssistantSkillVisibilityService skillVisibility;
    private final AssistantMemoryService memoryService;
    private final AssistantModelFactory modelFactory;

    /**
     * 组装上下文构成。会话不存在/不属于本人 → 返回 200 空构成（0 桶不出现），
     * 与 /status 同款降级（也不泄露会话是否存在）——调用方无需区分两种情况。
     */
    public ContextBreakdownVo build(String tenantId, Long userId, String threadId) {
        ConversationReadGateway.ConversationRef conv = conversationRead.conversationByThread(tenantId, threadId);
        if (conv == null || !userId.equals(conv.userId())) {
            // 2026-11-15 三审席1/席3 P1：新会话的 threadId 是前端先给的 uuid，会话行要等首次发送才落库；
            // 读接口若抛 403，响应拦截器会对每次悬停弹红条「会话不存在或无权访问」。
            return new ContextBreakdownVo(null, null, ContextBreakdownEstimator.METHOD, List.of());
        }
        boolean memoryOn = promptCatalog.memoryEnabled(tenantId);

        // ①系统提示词（除 cheatsheet 外各段，带逐段细分） ②工具提示词（cheatsheet 段）
        // 2026-11-15 Q6 框架板 Q3=2：口径改为「只数字符」——字符是客观量，界面折算并标估算
        long sysPromptChars = 0;
        long toolsPromptChars = 0;
        record SubRaw(String name, long chars) {
        }
        List<SubRaw> subs = new ArrayList<>();
        for (PromptCatalogGateway.PromptSegment seg : promptCatalog.segments(memoryOn)) {
            // 2026-11-15 三审席1 P2-1：催办纪律段运行口径恒不装配（平台工具无催办类，
            // 见 AssistantAgentFactory 的 sysPrompt(memoryOn,false)）——展示口径会把它算进去，
            // 这里按真实装配口径剔除，否则系统提示词固定偏高。
            if ("discipline-urge".equals(seg.key())) {
                continue;
            }
            long c = ContextBreakdownEstimator.charsOf(seg.content());
            if ("cheatsheet".equals(seg.key())) {
                toolsPromptChars += c;
            } else {
                sysPromptChars += c;
                subs.add(new SubRaw(seg.title(), c));
            }
        }
        // ③系统工具：只有工具名（真实 schema 要序列化且需活体 toolkit，状态接口拿不到——低置信度）
        long systemToolsChars = ContextBreakdownEstimator.charsOf(promptCatalog.systemToolNames());
        // ④技能（2026-11-15 三审席1 P1-2 修）：与「谁能看见哪个技能」同源——按本角色可见技能的
        // 名字+说明求和（就是提示词目录里每行技能条目的内容），再叠加本会话选中技能的正文。
        // 不再扫 sharedSkillWorkDir：那是框架物化缓存（内置技能不落盘、DB 技能反复复制且永不删除），
        // 量出来既可能恒 0 也可能随进程单调增长，还会混进别的租户的技能。
        long skillsChars = 0;
        try {
            int roleLevel = promptCatalog.currentRoleLevel();
            Map<String, String> skillGate = promptCatalog.toolGate(tenantId);
            for (PromptCatalogGateway.SkillEntry sk : promptCatalog.skills(tenantId)) {
                // 批 4 题 2 案 1：与目录/注入同一道闸（判定统一走 AssistantSkillVisibilityService）
                if (!skillVisibility.visible(sk.id(), sk.minRole(), roleLevel, skillGate)) {
                    continue; // 看不见的技能不进构成
                }
                skillsChars += ContextBreakdownEstimator.charsOf(sk.name() + "\n" + sk.description());
            }
            String selectedSkill = promptCatalog.selectedSkillId(threadId);
            if (selectedSkill != null) {
                skillsChars += ContextBreakdownEstimator.charsOf(
                    promptCatalog.skillBody(selectedSkill, roleLevel, tenantId));
            }
        } catch (Exception e) {
            // 技能视图读失败按 0（桶不出现）；留 warn 防「静默少一段」没人查
            log.warn("[status/context] 技能字符统计失败: {}", e.getMessage());
        }
        // ⑤消息：本会话正文（最近 500 条防全表；含工具步骤——工具结果不落库，见实体注释）。
        // 先取一次，后面的「记忆」按最后一条用户提问打分也要用同一份尾部。
        List<ConversationReadGateway.MessageRef> tail = List.of();
        try {
            tail = conversationRead.listMessagesTail(tenantId, userId, conv.id(), MESSAGES_TAIL_LIMIT);
        } catch (Exception e) {
            log.warn("[status/context] 消息读取失败: {}", e.getMessage()); // 桶不出现，但留痕
        }
        long messagesChars = ContextBreakdownEstimator.charsOf(
            tail.stream().map(m -> (m.content() == null ? "" : m.content())
                + (m.toolSteps() == null ? "" : m.toolSteps())).toList());
        // ⑥记忆（Q4=1 单列）：不是「记忆库总量」，而是本轮按最后一条用户提问打分后**真正会注入**的那份
        // （2026-11-15 三审席1/席3 P1）：问题式打分全不命中时注入 0 字节，面板也必须显示 0 桶不出现。
        long memoryChars = 0;
        if (memoryOn) {
            try {
                String question = null;
                for (int i = tail.size() - 1; i >= 0; i--) {
                    ConversationReadGateway.MessageRef m = tail.get(i);
                    if (m != null && "user".equals(m.role()) && m.content() != null
                        && !m.content().isBlank()) {
                        question = m.content();
                        break;
                    }
                }
                // 只读版：不回写 last_used_at（不污染 90 天归档判据）；带上注入前缀同一份文本
                DigestResult mem = memoryService.digestReadOnly(tenantId, userId, question);
                if (mem != null) {
                    memoryChars = ContextBreakdownEstimator.charsOf(
                        promptCatalog.memoryPromptPrefix() + mem.text());
                }
            } catch (Exception e) {
                log.warn("[status/context] 记忆读取失败: {}", e.getMessage()); // 桶不出现，但留痕
            }
        }
        // ⑦其他 = 日期注入段（记忆已单列，不再混在「其他」里）
        long otherChars = ContextBreakdownEstimator.charsOf(promptCatalog.dateSegmentText());

        // 汇总：降序 + pct（=该桶字符数 ÷ 全部桶字符数之和，0 桶剔除；系统提示词的 sub 按 sysPromptChars 内占比换算）
        record Raw(String key, String label, long chars, boolean lowConfidence, List<SubRaw> subs) {
        }
        final long sysPromptTotal = sysPromptChars;
        List<Raw> raws = new ArrayList<>();
        if (sysPromptChars > 0) {
            raws.add(new Raw("system_prompt", "系统提示词", sysPromptChars, false, subs));
        }
        if (memoryChars > 0) {
            raws.add(new Raw("memory", "记忆", memoryChars, false, null));
        }
        if (toolsPromptChars > 0) {
            raws.add(new Raw("tools_prompt", "工具提示词", toolsPromptChars, false, null));
        }
        if (systemToolsChars > 0) {
            raws.add(new Raw("system_tools", "系统工具（仅名称）", systemToolsChars, true, null));
        }
        if (skillsChars > 0) {
            raws.add(new Raw("skills", "技能", skillsChars, false, null));
        }
        if (otherChars > 0) {
            raws.add(new Raw("other", "其他", otherChars, false, null));
        }
        if (messagesChars > 0) {
            raws.add(new Raw("messages", "消息（近 500 条）", messagesChars, true, null));
        }
        long sum = raws.stream().mapToLong(Raw::chars).sum();
        raws.sort(Comparator.comparingLong(Raw::chars).reversed());
        List<ContextBreakdownVo.Bucket> buckets = new ArrayList<>();
        for (Raw r : raws) {
            List<ContextBreakdownVo.Sub> subOut = null;
            if (r.subs() != null && sysPromptTotal > 0) {
                subOut = r.subs().stream()
                    .map(x -> new ContextBreakdownVo.Sub(x.name(),
                        ContextBreakdownEstimator.pctOf(x.chars(), sysPromptTotal)))
                    .toList();
            }
            double pct = ContextBreakdownEstimator.pctOf(r.chars(), sum);
            if (pct <= 0) {
                continue; // 2026-11-15 三审席3 P2-8：占比舍入到 0.0 的桶不下发，避免「约 0%」被当成坏了
            }
            buckets.add(new ContextBreakdownVo.Bucket(
                r.key(), r.label(), r.chars(), pct, r.lowConfidence(), subOut));
        }
        // 真值两字段：先解析生效窗口、再查会话末次 tokens——顺序与旧 controller 逐行一致
        // （批 7 · 7A 多审 dspro 席 P2-1：写成 new VO(contextTokensOf(...), windowTokensOf(...)) 会把求值顺序反过来；
        //  当前两侧各自吞异常、输出零差异，但批 9 若给窗口解析加请求级副作用就会变成可观测差异，故显式落到局部变量。）
        Long windowTokens = windowTokensOf(tenantId, threadId);
        // 批 10（2026-11-22）Q5：把上面已查到的 conv 传下去，不再让 contextTokensOf 二次打库
        Long totalTokens = contextTokensOf(tenantId, userId, conv);
        return new ContextBreakdownVo(
            totalTokens, windowTokens,
            ContextBreakdownEstimator.METHOD, buckets);
    }

    /**
     * 批B B2：本会话最新 assistant 消息末次调用 tokens（无值返回 null）。
     * B1 修：取「最新 tokens 非空行」——truncated/中断行恒空，取最新行会被空值遮蔽。
     *
     * <p>批 10（2026-11-22）Q5：入参由 threadId 改为已查好的 {@code conv}——{@link #build}
     * 开头已按 (tenant, thread) 取过会话行，这里再查一次是白白多一次往返，且两处各自判空
     * 会在将来任一处改谓词时分叉。调用方保证 conv 非空且已验归属。</p>
     */
    private Long contextTokensOf(String tenantId, Long userId, ConversationReadGateway.ConversationRef conv) {
        try {
            return conversationRead.latestContextTokens(tenantId, userId, conv.id());
        } catch (Exception e) {
            return null;
        }
    }

    /** 生效模型窗口（模型未配置：窗口不下发。注：当前前端占用环分母走模型配置行，不读本字段——见 VO 注释） */
    private Long windowTokensOf(String tenantId, String threadId) {
        try {
            return (long) modelFactory.resolveConfigWithUserChoice(tenantId, threadId).getContextWindow();
        } catch (Exception ignored) {
            return null;
        }
    }
}
