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
 * 脱敏（手机号/身份证不进记忆）。个人偏好不属于业务动作，不占动作账本。</p>
 */
public class MemoryTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolIdentity identity;
    private final AssistantMemoryService memoryService;

    public MemoryTools(ToolIdentity identity, AssistantMemoryService memoryService) {
        this.identity = identity;
        this.memoryService = memoryService;
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
        String result = memoryService.save(identity.getTenantId(), identity.getUserId(), content);
        boolean saved = result.startsWith("已记住");
        out.put("status", saved ? "OK" : "SKIP");
        out.put("message", result + (saved ? "（已同步到我的记忆，用户可随时在记忆页删除）" : ""));
        return ToolSupport.secureJson(out, identity);
    }
}
