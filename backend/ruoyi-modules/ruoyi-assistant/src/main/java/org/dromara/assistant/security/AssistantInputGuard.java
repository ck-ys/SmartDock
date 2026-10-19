package org.dromara.assistant.security;

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

/**
 * 助手输入体积闸（批G G4，S-03）：单轮用户输入总量上限 16000 字符
 * （P0⑮ 校准区间 8K~16K 取上限），超限拒绝并提示拆分。
 *
 * <p>三层意义：①prompt 成本护栏（16K 字≈模型侧数万 token 前置拦截）；
 * ②上下文窗口保护（配合压缩三件套）；③滥用面收窄。system/assistant 角色
 * 历史消息不计（前端 AGUI 传输的是全量历史，只对当轮新增 user 消累计）。</p>
 */
@Component
public class AssistantInputGuard {

    /** 单轮用户输入字符上限（S-03，P0⑮ 校准 8K~16K 取 16K） */
    public static final int MAX_INPUT_CHARS = 16000;

    /**
     * 校验当轮用户输入（=messages 里最后一条 user 消息），超限抛人话异常。
     *
     * <p>只看最后一条（glm53 P1-2/dsflash 同源修）：AGUI 协议每轮重传全量历史，
     * 若累计全部 user 消息，长会话历史超 16K 后每轮都被拒且无法自愈（只能弃会话）；
     * 历史体积护栏由压缩三件套/模型窗口兜底，本闸只管本轮新增。</p>
     */
    public void validate(RunAgentInput input) {
        AguiMessage lastUser = lastUserMessage(input);
        if (lastUser == null || lastUser.getTextContent() == null) {
            return;
        }
        if (lastUser.getTextContent().length() > MAX_INPUT_CHARS) {
            throw new ServiceException("输入内容过长（上限 " + MAX_INPUT_CHARS
                + " 字符），请拆分后分次提问", 400);
        }
    }

    /** 当轮新增 user 消息（全量历史的最后一条 user；resolver 持久化同口径） */
    public static AguiMessage lastUserMessage(RunAgentInput input) {
        if (input == null || input.getMessages() == null) {
            return null;
        }
        AguiMessage last = null;
        for (AguiMessage m : input.getMessages()) {
            if (m != null && m.isUserMessage()) {
                last = m;
            }
        }
        return last;
    }
}
