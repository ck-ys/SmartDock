package org.dromara.assistant.security;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 助手 PII 出网装饰器（批G G4 入口①）：Model 边界把出网消息的文本内容确定性脱敏
 * （X-08：模型出网一律脱敏值；本人会话原文落库走明文例外通道不受影响）。
 *
 * <p>顺序：Billing(Pii(原生模型))——计费装饰器包外层，按脱敏后文本估费（脱敏对
 * token 量影响可忽略）；响应方向不逐帧掩码（流式跨帧掩码=P2 登记），落库侧
 * 由 RunRecorder 对完整文本脱敏，双保险。</p>
 */
@Slf4j
public class AssistantPiiModelDecorator implements Model {

    private final Model delegate;
    private final AssistantPiiFilter filter;
    private final String tenantId;

    public AssistantPiiModelDecorator(Model delegate, AssistantPiiFilter filter, String tenantId) {
        this.delegate = delegate;
        this.filter = filter;
        this.tenantId = tenantId;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return delegate.stream(messages.stream().map(this::maskMsg).toList(), tools, options);
    }

    /** 无 PII 原 Msg 直通（零拷贝快路径）；含 PII 重建等价 Msg——content 列表逐块
     * 保留（dsflash P2-10 修：textContent(masked) 会丢 tool-call/result 等非文本块，
     * 含 PII 文本+工具调用的历史消息会被改写坏），只替换 TextBlock 的文本 */
    private Msg maskMsg(Msg msg) {
        if (msg == null) {
            return msg;
        }
        String text = msg.getTextContent();
        if (text == null || text.isEmpty()) {
            return msg;
        }
        String masked = filter.mask(text, tenantId);
        if (masked.equals(text)) {
            return msg;
        }
        log.debug("[AssistantPii] 出网消息脱敏 role={} len {}→{}", msg.getRole(), text.length(), masked.length());
        java.util.List<io.agentscope.core.message.ContentBlock> content = msg.getContent();
        java.util.List<io.agentscope.core.message.ContentBlock> newContent;
        if (content == null || content.isEmpty()) {
            newContent = List.of(io.agentscope.core.message.TextBlock.builder().text(masked).build());
        } else {
            newContent = content.stream()
                .map(b -> {
                    if (b instanceof io.agentscope.core.message.TextBlock tb) {
                        String tbMasked = filter.mask(tb.getText(), tenantId);
                        if (!tbMasked.equals(tb.getText())) {
                            return io.agentscope.core.message.TextBlock.builder().text(tbMasked).build();
                        }
                    }
                    return b;
                })
                .toList();
        }
        return Msg.builder()
            .id(msg.getId())
            .name(msg.getName())
            .role(msg.getRole())
            .content(newContent)
            .metadata(msg.getMetadata())
            .timestamp(msg.getTimestamp())
            .usage(msg.getUsage())
            .generateReason(msg.getGenerateReason())
            .build();
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
