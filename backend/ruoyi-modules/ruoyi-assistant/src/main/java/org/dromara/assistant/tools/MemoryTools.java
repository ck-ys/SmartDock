package org.dromara.assistant.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.dromara.assistant.service.AssistantMemoryService;

/**
 * 长期记忆工具：save_memory（二站④ AU-53，2026-10-08）。
 *
 * <p>仅租户开启长期记忆时注册（ToolRegistry 按配置门控）。用户说「记住…」类
 * 偏好时模型调用本工具落库；内容过 {@link org.dromara.assistant.security.AssistantPiiFilter}
 * 脱敏（手机号/身份证不进记忆）。个人偏好按平台放行执行，落动作账本
 * （release_mode=allow），可撤回——三批③（2026-11-01）起与写操作同账本口径。</p>
 */
public class MemoryTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolIdentity identity;
    private final AssistantMemoryService memoryService;
    /** 三批③：写操作审计挂钩（可空——null=测试/旧路径不落账本） */
    private final org.dromara.assistant.core.AssistantActionLedgerHook actionLedger;
    /** 本轮会话 threadId（ckys3 解锁板题7 出处；可空=查不到会话，出处列留空） */
    private final String aguiThreadId;

    public MemoryTools(ToolIdentity identity, AssistantMemoryService memoryService) {
        this(identity, memoryService, null, null);
    }

    public MemoryTools(ToolIdentity identity, AssistantMemoryService memoryService,
                       org.dromara.assistant.core.AssistantActionLedgerHook actionLedger) {
        this(identity, memoryService, actionLedger, null);
    }

    public MemoryTools(ToolIdentity identity, AssistantMemoryService memoryService,
                       org.dromara.assistant.core.AssistantActionLedgerHook actionLedger,
                       String aguiThreadId) {
        this.identity = identity;
        this.memoryService = memoryService;
        this.actionLedger = actionLedger;
        this.aguiThreadId = aguiThreadId;
    }

    @Tool(name = "save_memory",
        description = "把用户告知的长期偏好/事实存入记忆（跨会话生效，用户可在「AI 助手记忆」页查看和删除）。"
            + "适用：用户说「记住…」「以后都…」「叫我…」类稳定偏好或称呼；"
            + "禁止存：一次性的任务参数、他人的信息、手机号/身份证等敏感信息（系统会自动脱敏）。",
        readOnly = false)
    public String saveMemory(
        @ToolParam(name = "content", required = true,
            description = "要记住的内容，一句话（如「称呼我为小林」「催办留言默认提醒今天截止」）") String content) {
        ObjectNode out = MAPPER.createObjectNode();
        Long auditRow = actionLedger == null ? null : actionLedger.open("save_memory", argsJson(content));
        try {
            AssistantMemoryService.SaveOutcome outcome = memoryService.saveReturningId(
                identity.getTenantId(), identity.getUserId(), content, aguiThreadId);
            boolean saved = outcome.message().startsWith("已记住");
            out.put("status", saved ? "OK" : "SKIP");
            String suffix = saved ? "（已同步到我的记忆，用户可随时在记忆页删除）" : "";
            if (saved && outcome.overwritten() != null && !outcome.overwritten().isEmpty()) {
                suffix = "（已更新同主题旧记忆：旧内容进历史，可在记忆页回滚）";
            }
            out.put("message", outcome.message() + suffix);
            // SKIP/空内容=DONE（非失败）；落库成功回填记忆行 id 作回执锚点（④撤回直读用）
            finishAudit(auditRow, true, outcome.message(), outcome.memoryId());
            return ToolSupport.secureJson(out, identity);
        } catch (RuntimeException e) {
            finishAudit(auditRow, false, "保存失败：" + e.getMessage(), null);
            throw e;
        }
    }

    private void finishAudit(Long auditRow, boolean ok, String summary, Long refId) {
        if (actionLedger != null) {
            actionLedger.finish(auditRow, ok, summary, refId);
        }
    }

    /** 审计用参数快照（与账本 args_json 同源） */
    private static String argsJson(String content) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("content", content);
        return n.toString();
    }
}
