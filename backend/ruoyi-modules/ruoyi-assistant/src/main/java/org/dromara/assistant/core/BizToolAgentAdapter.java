package org.dromara.assistant.core;

import com.smartdock.assistant.spi.BizToolCall;
import com.smartdock.assistant.spi.BizToolProvider;
import com.smartdock.assistant.spi.BizToolResult;
import com.smartdock.assistant.spi.BizToolSpec;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.assistant.tools.ToolIdentity;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * BizToolProvider → agentscope AgentTool 桥(批2 SPI 化)。
 *
 * <p>桥持有会话身份快照(每会话 new, 与内置工具同纪律); 契约包业务侧完全不感知
 * agentscope。结果统一 JSON 文本喂模型; 异常兜底转错误块, 不炸会话。</p>
 *
 * @author smartdock
 */
public class BizToolAgentAdapter implements AgentTool {

    /** JSON 序列化出口(默认框架 JsonUtils; 单测可替换——JsonUtils 静态依赖 Spring 上下文)。
     *  volatile+public 是刻意保留的测试钩子(dspro P2-9): 生产代码禁写本字段;
     *  替换须发生在任何会话创建之前, 测试 @AfterAll 还原防污染同 JVM 其它用例。 */
    public static volatile java.util.function.Function<Object, String> serializer = JsonUtils::toJsonString;

    private final BizToolProvider provider;
    private final BizToolSpec spec;
    private final ToolIdentity identity;
    private final String threadId;

    public BizToolAgentAdapter(BizToolProvider provider, BizToolSpec spec, ToolIdentity identity, String threadId) {
        this.provider = provider;
        this.spec = spec;
        this.identity = identity;
        this.threadId = threadId;
    }

    @Override
    public String getName() {
        return spec.getName();
    }

    @Override
    public String getDescription() {
        return spec.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return JsonSchemaBuilder.build(spec);
    }

    @Override
    public boolean isReadOnly() {
        return spec.isReadOnly();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> doExecute(param.getInput()));
    }

    /**
     * 同步执行核心(单测直入; callAsync 只是 Mono 包装)。
     */
    public ToolResultBlock doExecute(Map<String, Object> input) {
        try {
            // buildCall 一并收进 try(dspro P2-7): provider 配置缺陷(如 params=null)
            // 也走错误块, 不让 NPE 逃进 Reactor 错误通道炸会话
            BizToolCall call = buildCall(input);
            BizToolResult result = provider.execute(call);
            if (result == null) {
                return ToolResultBlock.error(getName(), "provider returned null");
            }
            if (result.isOk()) {
                Object data = result.getData() == null ? Map.of() : result.getData();
                return ToolResultBlock.text(serializer.apply(data));
            }
            return ToolResultBlock.error(getName(),
                result.getMessage() == null ? "tool failed" : result.getMessage());
        } catch (Exception e) {
            return ToolResultBlock.error(getName(), "tool error: " + e.getMessage());
        }
    }

    private BizToolCall buildCall(Map<String, Object> input) {
        BizToolCall call = new BizToolCall();
        call.setToolName(spec.getName());
        call.setArgs(filterDeclaredArgs(input));
        call.setTenantId(identity.getTenantId());
        call.setUserId(identity.getUserId());
        call.setRoleLevel(identity.getRoleLevel());
        call.setThreadId(threadId);
        Set<String> menuPermissions = identity.getLoginUser() == null ? null
            : identity.getLoginUser().getMenuPermission();
        call.setMenuPermissions(menuPermissions);
        call.setUsername(identity.getLoginUser() == null ? null : identity.getLoginUser().getUsername());
        return call;
    }

    /**
     * 只放行 schema 声明过的参数——模型幻觉塞多余键直接剔除, 业务侧拿到的永远是干净入参。
     */
    private Map<String, Object> filterDeclaredArgs(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (input == null || spec.getParams() == null) {
            return out;
        }
        for (com.smartdock.assistant.spi.BizParamSpec p : spec.getParams()) {
            if (input.containsKey(p.getName())) {
                out.put(p.getName(), input.get(p.getName()));
            }
        }
        return out;
    }
}
