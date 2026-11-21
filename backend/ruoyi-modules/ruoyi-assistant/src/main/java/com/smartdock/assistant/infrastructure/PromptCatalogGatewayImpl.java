package com.smartdock.assistant.infrastructure;

import com.smartdock.assistant.domain.gateway.PromptCatalogGateway;
import lombok.RequiredArgsConstructor;
import com.smartdock.assistant.engine.core.DateInjectModelDecorator;
import com.smartdock.assistant.engine.core.MemoryInjectModelDecorator;
import com.smartdock.assistant.engine.core.PromptAssets;
import com.smartdock.assistant.engine.core.AssistantSkillHolder;
import com.smartdock.assistant.engine.core.SkillAssets;
import com.smartdock.assistant.engine.core.ToolRegistry;
import com.smartdock.assistant.service.AssistantConfigService;
import com.smartdock.assistant.engine.tools.ToolIdentity;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link PromptCatalogGateway} 的唯一实现（批 7 · 7A，2026-11-20）。
 *
 * <p>本类是新根里允许 import legacy core 目录资产的收口点之一（另一处是
 * {@code ConversationReadGatewayImpl}）。全部方法都是原 controller 内联逻辑的逐行搬移，
 * 口径不改：{@code segments} 直传、技能不动正文、前缀/日期段取同一份常量。</p>
 */
@Component
@RequiredArgsConstructor
public class PromptCatalogGatewayImpl implements PromptCatalogGateway {

    private final AssistantConfigService configService;

    @Override
    public List<PromptSegment> segments(boolean memoryOn) {
        List<PromptSegment> out = new ArrayList<>();
        for (PromptAssets.PromptSegment seg : PromptAssets.segments(memoryOn)) {
            out.add(new PromptSegment(seg.key(), seg.title(), seg.content()));
        }
        return out;
    }

    @Override
    public List<String> systemToolNames() {
        List<String> out = new ArrayList<>();
        for (ToolRegistry.ToolSpec spec : ToolRegistry.SPECS) {
            out.addAll(spec.toolNames());
        }
        return out;
    }

    @Override
    public List<SkillEntry> skills(String tenantId) {
        List<SkillEntry> out = new ArrayList<>();
        for (SkillAssets.LoadedSkill sk : SkillAssets.skills(tenantId)) {
            out.add(new SkillEntry(sk.id(), sk.name(), sk.description(), sk.minRole()));
        }
        return out;
    }

    @Override
    public String skillBody(String skillId, int roleLevel, String tenantId) {
        return SkillAssets.body(skillId, roleLevel, tenantId);
    }

    @Override
    public String selectedSkillId(String threadId) {
        return AssistantSkillHolder.get(threadId);
    }

    @Override
    public String memoryPromptPrefix() {
        return MemoryInjectModelDecorator.MEMORY_PROMPT_PREFIX;
    }

    @Override
    public String dateSegmentText() {
        return DateInjectModelDecorator.dateSegmentText();
    }

    @Override
    public boolean memoryEnabled(String tenantId) {
        return "1".equals(configService.effective(tenantId).memoryEnabled());
    }

    @Override
    public Map<String, String> toolGate(String tenantId) {
        return configService.effective(tenantId).toolGate();
    }

    @Override
    public int currentRoleLevel() {
        return ToolIdentity.from(LoginHelper.getLoginUser()).getRoleLevel();
    }
}
