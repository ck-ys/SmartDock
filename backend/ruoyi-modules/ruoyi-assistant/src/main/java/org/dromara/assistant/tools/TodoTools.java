package org.dromara.assistant.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.dromara.assistant.core.AssistantTodoSignal;
import org.dromara.assistant.service.AssistantConversationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * TODO 清单工具：todo_write（ZCode 对齐 Q2，批A 2026-10-10）。
 *
 * <p><b>机制</b>（ZCode TodoWrite 同款）：整表替换——每次调用传完整清单，
 * 整体替换上一版。工具教导（何时建清单/怎么维护状态）全在本 description
 * 与系统提示词 §13 一句话，不依赖额外 reminder（ZCode 的 10 轮 reminder
 * 面向超长会话，DCS 助手会话普遍短，省略）。</p>
 *
 * <p>双写：①持久化真源=会话行 todo_items 列（刷新/重开恢复）；
 * ②{@link AssistantTodoSignal} 信号仓（enricher 同线程下一批事件前转 todo
 * CUSTOM 帧，回合进行中 StatPanel 实时更新）。落库走显式租户谓词的
 * mapper（InterceptorIgnore），工具线程无请求上下文也安全。</p>
 */
public class TodoTools {

    /** 条数上限（>20 条的清单对人是噪音；ZCode UI 端 >6 条已折叠） */
    private static final int MAX_ITEMS = 20;
    /** 单条内容截断（清单是速览不是正文） */
    private static final int MAX_CONTENT_CHARS = 40;
    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed");
    private static final Set<String> PRIORITIES = Set.of("high", "medium", "low");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolIdentity identity;
    private final AssistantTodoSignal todoSignal;
    private final AssistantConversationService conversationService;
    private final String tenantId;
    private final String aguiThreadId;

    public TodoTools(ToolIdentity identity, AssistantTodoSignal todoSignal,
                     AssistantConversationService conversationService, String aguiThreadId) {
        this.identity = identity;
        this.todoSignal = todoSignal;
        this.conversationService = conversationService;
        this.tenantId = identity.getTenantId();
        this.aguiThreadId = aguiThreadId;
    }

    /** glm53 P2-1 回修：readOnly 必须如实=false——框架 PermissionEngine 在 EXPLORE 模式下
     * 对 readOnly=true 的工具直接放行（字节码实证），本工具写库不得享受只读豁免 */
    @Tool(name = "todo_write",
        description = "创建或更新本次会话的任务清单，用户会在界面上看到这份工作计划。\n"
            + "- 每条任务包含 content（简短描述）、status（pending=未开始/in_progress=进行中/completed=已完成）、"
            + "priority（high/medium/low）。\n"
            + "- 每次调用必须传完整清单，它会整体替换上一版。\n"
            + "- 做多步任务（如先查名单、再汇总、最后催办）时第一步就建清单；"
            + "完成一步就把该条标成 completed、把下一步标成 in_progress，让用户看到进度。\n"
            + "- 一次就能答完的简单问答不要建清单。",
        readOnly = false)
    public String todoWrite(
        @ToolParam(name = "todos", required = true,
            description = "完整任务清单，JSON 数组字符串，如："
                + "[{\"content\":\"查未交名单\",\"status\":\"in_progress\",\"priority\":\"high\"},"
                + "{\"content\":\"发送催办\",\"status\":\"pending\",\"priority\":\"medium\"}]") String todos) {
        ObjectNode out = MAPPER.createObjectNode();
        List<AssistantTodoSignal.TodoItem> items = new ArrayList<>();
        JsonNode arr;
        try {
            JsonNode parsed = MAPPER.readTree(todos == null ? "" : todos);
            arr = parsed.isArray() ? parsed : null;
        } catch (Exception e) {
            arr = null;
        }
        if (arr == null || arr.isEmpty()) {
            out.put("status", "ERROR");
            out.put("message", "清单格式不对：需要一个 JSON 数组，每条形如 "
                + "{\"content\":\"步骤描述\",\"status\":\"pending\",\"priority\":\"medium\"}");
            return ToolSupport.secureJson(out, identity);
        }
        for (int i = 0; i < arr.size() && items.size() < MAX_ITEMS; i++) {
            JsonNode n = arr.get(i);
            String content = n.path("content").asText("").trim();
            if (content.isEmpty()) {
                out.put("status", "ERROR");
                out.put("message", "第 " + (i + 1) + " 条缺 content（步骤描述），每条都必须有");
                return ToolSupport.secureJson(out, identity);
            }
            String status = n.path("status").asText("pending").trim();
            if (!STATUSES.contains(status)) {
                out.put("status", "ERROR");
                out.put("message", "第 " + (i + 1) + " 条 status 只能是 pending/in_progress/completed");
                return ToolSupport.secureJson(out, identity);
            }
            String priority = n.path("priority").asText("medium").trim();
            if (!PRIORITIES.contains(priority)) {
                out.put("status", "ERROR");
                out.put("message", "第 " + (i + 1) + " 条 priority 只能是 high/medium/low");
                return ToolSupport.secureJson(out, identity);
            }
            items.add(new AssistantTodoSignal.TodoItem(
                truncateCodePoints(content, MAX_CONTENT_CHARS),
                status, priority));
        }
        // dspro P2-1 回修（2026-10-11）：截断提示曾被下方成功 message 覆盖（模型拿不到
        // 自纠信息）——拼成一条 message 一次写入
        String message = "任务清单已展示给用户（" + items.size() + " 条）。继续执行任务，"
            + "每完成一步就调用本工具更新状态，直到全部 completed。";
        if (arr.size() > MAX_ITEMS) {
            out.put("truncated", true);
            message = "清单超过 " + MAX_ITEMS + " 条，只保留前 " + MAX_ITEMS + " 条。" + message;
        }
        conversationService.saveTodo(tenantId, aguiThreadId, items);
        todoSignal.submit(aguiThreadId, items);
        out.put("status", "OK");
        out.put("count", items.size());
        out.put("message", message);
        return ToolSupport.secureJson(out, identity);
    }

    /** glm53 P2-3/dspro P2-6 回修：按码点截断——substring 按 UTF-16 切会把骑在边界上的
     * emoji/扩展区汉字劈成孤立代理项（落库变 ?），长度判定用 codePointCount 截断也须同口径 */
    static String truncateCodePoints(String s, int maxChars) {
        if (s == null || s.codePointCount(0, s.length()) <= maxChars) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxChars));
    }
}
