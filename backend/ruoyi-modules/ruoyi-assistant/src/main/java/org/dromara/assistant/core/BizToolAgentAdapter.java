package org.dromara.assistant.core;

import com.smartdock.assistant.spi.BizToolCall;
import com.smartdock.assistant.spi.BizToolProvider;
import com.smartdock.assistant.spi.BizToolResult;
import com.smartdock.assistant.spi.BizToolSpec;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.assistant.tools.ToolIdentity;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * BizToolProvider → agentscope ToolBase 桥(批2 SPI 化; G1 E2E-2 改继承)。
 *
 * <p>桥持有会话身份快照(每会话 new, 与内置工具同纪律); 契约包业务侧完全不感知
 * agentscope。结果统一 JSON 文本喂模型; 异常兜底转错误块, 不炸会话。</p>
 *
 * <p>G1 E2E-2 实证(2026-10-21)：裸 implements AgentTool 的工具在框架权限闸
 * {@code ReActAgent$CallExecution.evaluateOne} 里走「非 ToolBase 实例直接 ALLOW」
 * 分支——写类业务工具(如 dcs_create_task)静默绕过确认卡, ProviderToolRegistry
 * 挂的 ASK 规则从未被消费。改继承 ToolBase 后读写语义由引擎消费:
 * 只读=checkPermissions 默认放行, 写类=落到 askRules/默认 ASK 走 HITL。</p>
 *
 * @author smartdock
 */
public class BizToolAgentAdapter extends ToolBase {

    /** JSON 序列化出口(默认框架 JsonUtils; 单测可替换——JsonUtils 静态依赖 Spring 上下文)。
     *  volatile+public 是刻意保留的测试钩子(dspro P2-9): 生产代码禁写本字段;
     *  替换须发生在任何会话创建之前, 测试 @AfterAll 还原防污染同 JVM 其它用例。 */
    public static volatile java.util.function.Function<Object, String> serializer = JsonUtils::toJsonString;

    private final BizToolProvider provider;
    private final BizToolSpec spec;
    private final ToolIdentity identity;
    private final String threadId;

    public BizToolAgentAdapter(BizToolProvider provider, BizToolSpec spec, ToolIdentity identity, String threadId) {
        // ToolBase 9 参序: (name, description, inputSchema, readOnly, concurrencySafe, mcp, mcpName, externalTool, stateInjected)
        // 三审回修：description 空兜底""（ToolBase 对三参 requireNonNull，provider 漏配会让工具静默消失）；
        // concurrencySafe=false=有意从框架裸 AgentTool 默认并行收紧为串行（写类+HITL 天然逐个）
        super(spec.getName(), spec.getDescription() == null ? "" : spec.getDescription(),
            JsonSchemaBuilder.build(spec), spec.isReadOnly(), false, false, null, false, false);
        this.provider = provider;
        this.spec = spec;
        this.identity = identity;
        this.threadId = threadId;
    }

    @Override
    public reactor.core.publisher.Mono<io.agentscope.core.permission.PermissionDecision> checkPermissions(
            java.util.Map<String, Object> input, io.agentscope.core.permission.PermissionContextState ctx) {
        // 三审回修（dspro P1-3）：ToolBase 默认 passthrough 完全依赖建侧写入的 allow 规则，
        // 而框架 loadOrCreateAgentStateForSlot 对已有会话直接复用持久化 state——重建 agent 后
        // 新规则进不了旧 state，只读工具会因缺 allow 规则落默认 ASK 误弹确认卡。改为声明式
        // 三态（与 MCP readOnlyHint 同哲学）：只读=显式放行；写类+空上下文=fail-safe 弹确认；
        // 其余 passthrough 交给 deny/ask/allow 规则裁决（规则仍可覆盖本判定）
        if (spec.isReadOnly()) {
            return Mono.just(io.agentscope.core.permission.PermissionDecision.allow(getName()));
        }
        if (ctx == null || ctx.isTrivial()) {
            return Mono.just(io.agentscope.core.permission.PermissionDecision.ask(getName()));
        }
        return Mono.just(io.agentscope.core.permission.PermissionDecision.passthrough(getName()));
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
