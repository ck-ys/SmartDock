package org.dromara.assistant.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.dromara.assistant.core.AssistantPlanSignal;

/**
 * 计划卡工具：submit_plan（二站③ AU-51，2026-10-08）。
 *
 * <p><b>机制</b>：不挂起会话（与 HITL 确认卡不同——那是执行前最后一道闸）。
 * 模型判断触发规则后调本工具 → 计划写入 {@link AssistantPlanSignal}（键=threadId）→
 * enricher 同线程下一批事件前转 plan CUSTOM 帧 → 前端 TurnBlock/PlanCard 亮出
 * 三按钮卡（直接执行/改计划/取消），按钮动作=发新消息，模型按用户选择继续。</p>
 *
 * <p><b>触发规则</b>（系统提示词 §7 同源）：①用户显式要方案（「先出计划/别直接做」）
 * ②催办类影响面大（≥10 人或跨 ≥2 部门/科目）③预计连做 ≥3 步的多步流程。
 * 本工具只发信号不落库，不占动作账本。</p>
 */
public class PlanTools {

    /** 步骤数上限（前端 PlanCard 同口径截 8 步，超出无展示面） */
    private static final int MAX_STEPS = 8;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolIdentity identity;
    private final AssistantPlanSignal planSignal;
    private final String aguiThreadId;

    public PlanTools(ToolIdentity identity, AssistantPlanSignal planSignal, String aguiThreadId) {
        this.identity = identity;
        this.planSignal = planSignal;
        this.aguiThreadId = aguiThreadId;
    }

    @Tool(name = "submit_plan",
        description = "把多步操作计划做成计划卡展示给用户确认（不执行任何操作）。"
            + "触发时机见系统提示词：用户显式要方案/催办影响面大（≥10人或跨≥2部门科目）/预计连做≥3步。"
            + "调用后计划卡立即出现在用户界面，本轮到此收尾（一句话说明等用户选择即可），"
            + "禁止调用后再继续执行计划里的任何动作。",
        readOnly = true)
    public String submitPlan(
        @ToolParam(name = "title", required = false, description = "计划标题，一句话概括（可省略）") String title,
        @ToolParam(name = "steps", required = true,
            description = "步骤清单，分号或换行分隔的短句（每步 3~12 字），如：查未交名单;起草催办留言;发送催办") String steps) {
        ObjectNode out = MAPPER.createObjectNode();
        java.util.List<String> parsed = new java.util.ArrayList<>();
        for (String s : (steps == null ? "" : steps).split("[;；\\n\\r]+")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                parsed.add(t.length() > 24 ? t.substring(0, 24) : t);
            }
        }
        if (parsed.isEmpty()) {
            out.put("status", "ERROR");
            out.put("message", "步骤清单是空的：每一步用分号或换行分开（如：查未交名单;发送催办）");
            return ToolSupport.secureJson(out, identity);
        }
        if (parsed.size() > MAX_STEPS) {
            parsed = new java.util.ArrayList<>(parsed.subList(0, MAX_STEPS));
        }
        planSignal.submit(aguiThreadId, title, parsed);
        out.put("status", "OK");
        out.put("stepCount", parsed.size());
        out.put("message", "计划卡已展示给用户（" + parsed.size() + " 步）。本轮到此收尾：用一句话告诉用户"
            + "「计划已列出，可以直接执行、改一改或取消」，然后停止——不要先斩后奏执行任何一步。");
        return ToolSupport.secureJson(out, identity);
    }
}
