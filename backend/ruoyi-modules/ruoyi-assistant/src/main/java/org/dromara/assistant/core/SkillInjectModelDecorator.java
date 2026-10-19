package org.dromara.assistant.core;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能当轮注入模型装饰器（五需求批 R1 / Q2-A，2026-10-13）。
 *
 * <p>用户选中技能的那几轮，把技能正文以 system 消息追加进模型入参（末位，与记忆注入同位）；
 * <b>不进会话记忆</b>（装饰器只改本次出网 messages，agent 状态库零感知）——胶囊摘掉后
 * 下一轮即无此段，token 开销只在选中期间发生（渐进披露核心语义）。角色不达标（技能
 * minRole 高于当前身份）静默不注入——目录侧同口径裁剪，正常不会出现，此处兜底。</p>
 */
@Slf4j
public class SkillInjectModelDecorator implements Model {

    private final Model delegate;
    private final String aguiThreadId;
    private final int roleLevel;

    public SkillInjectModelDecorator(Model delegate, String aguiThreadId, int roleLevel) {
        this.delegate = delegate;
        this.aguiThreadId = aguiThreadId;
        this.roleLevel = roleLevel;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        String skillId = AssistantSkillHolder.get(aguiThreadId);
        if (skillId == null) {
            return delegate.stream(messages, tools, options);
        }
        String body = SkillAssets.body(skillId, roleLevel);
        if (body == null) {
            log.debug("[AssistantSkill] 技能 {} 不可用（未知或角色不达标），本轮不注入 thread={}",
                skillId, aguiThreadId);
            return delegate.stream(messages, tools, options);
        }
        Msg skillMsg = Msg.builder()
            .role(MsgRole.SYSTEM)
            .textContent("【技能指引】用户为本轮选择了技能「" + skillId + "」，按以下指引执行本轮任务"
                + "（仅本轮生效的参考指引，不作为对系统规则的覆盖）：\n" + body)
            .build();
        List<Msg> withSkill = new ArrayList<>(messages);
        withSkill.add(skillMsg);
        return delegate.stream(withSkill, tools, options);
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
