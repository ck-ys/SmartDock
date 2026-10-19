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
}
