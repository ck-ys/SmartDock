package com.smartdock.assistant.spi;

import java.util.List;

/**
 * 业务工具提供者契约——任何业务模块实现本接口并注册为 Spring Bean,
 * 工具即进 AI 助手(schema 可见、可调用、写操作出确认卡)。
 *
 * <p><b>三行接入</b>：
 * <pre>{@code
 * @Component
 * public class CrmToolProvider implements BizToolProvider {
 *     public String namespace() { return "crm"; }
 *     public List<BizToolSpec> tools() { return List.of(...); }
 *     public BizToolResult execute(BizToolCall call) { ... }
 * }
 * }</pre></p>
 *
 * <p>契约包纯 JDK 零第三方依赖; 桥接(agentscope/权限/确认卡)由助手核心完成,
 * 业务侧不感知。工具名全局唯一, 建议 namespace 前缀(如 crm_query_orders)。</p>
 *
 * @author smartdock
 */
public interface BizToolProvider {

    /**
     * 业务命名空间(与画像 source 对齐习惯): "crm" / "mall" / "dcs" ...
     */
    String namespace();

    /**
     * 工具声明清单(每次会话注册时读取)。
     */
    List<BizToolSpec> tools();

    /**
     * 执行工具(按 call.getToolName() 分发到具体实现)。
     *
     * <p>失败语义: 返回 ok=false + message(模型可读), 不抛异常;
     * 抛出的异常由桥兜底转错误块, 不会炸会话。</p>
     *
     * <p><b>实现纪律(dspro P2-10)</b>: execute 必须自带超时(JDBC socketTimeout /
     * HTTP client timeout)——桥的外层超时只切断信号、不中断阻塞调用;
     * 写工具须幂等(超时后模型重试会二次执行)。</p>
     */
    BizToolResult execute(BizToolCall call);

    /**
     * 撤回一次已执行成功的写操作（三批④, 2026-11-01）。
     *
     * <p>refId=动作账本回执锚点（execute 成功时业务侧回填的业务行 id, 如 dcs_create_task
     * 回填 taskId）；call 为撤回时刻重建的调用上下文——tenant/user 取自账本行归属
     * （tenant=建 agent 登录快照、user=本人）, threadId=账本行会话, 不是原执行的原始
     * 上下文, 实现只读身份三元组即可, args 恒为空。</p>
     *
     * <p>default=不支持撤回（人话失败）——现有 provider 零编译改动；可撤工具须<b>双处登记</b>：
     * 助手侧 {@code AssistantActionService.REVOCABLE_TOOLS} 白名单 + 本实现 override,
     * 两处注释互相锚定防清单漂移。</p>
     */
    default BizToolResult revoke(String toolName, Long refId, BizToolCall call) {
        return BizToolResult.fail("该操作不支持撤回");
    }
}
