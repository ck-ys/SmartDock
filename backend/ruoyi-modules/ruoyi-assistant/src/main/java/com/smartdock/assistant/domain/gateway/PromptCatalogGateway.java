package com.smartdock.assistant.domain.gateway;

import java.util.List;
import java.util.Map;

/**
 * 「提示词/工具/技能目录」取数口（批 7 · 7A，2026-11-20）。
 *
 * <p>为什么建 gateway：这些目录全是 legacy core 的静态资产（{@code PromptAssets} /
 * {@code ToolRegistry} / {@code SkillAssets} / {@code AssistantSkillHolder} / 两个注入装饰器）
 * 加 legacy 配置服务（{@code AssistantConfigService}）。7A 把上下文构成（/status/context）
 * 主体搬进新根 {@code query} 包，而门禁规则 8 零容忍「新根 service import legacy」——
 * 所以按批 1 {@link RunFeedGateway} 同款做法：接口在新根，legacy 类型只在实现里出现。
 *
 * <p>这批资产按批 9（引擎邻界）还会再动，届时换实现即可，接口与口径不动。</p>
 */
public interface PromptCatalogGateway {

    /** 系统提示词的一段（key 用于口径剔除/归类，title 给人看，content 算字符） */
    record PromptSegment(String key, String title, String content) {
    }

    /** 技能目录条目（可见性判定要 minRole；正文另取） */
    record SkillEntry(String id, String name, String description, int minRole) {
    }

    /** 系统提示词分段（memoryOn 影响是否含记忆相关段） */
    List<PromptSegment> segments(boolean memoryOn);

    /** 系统工具名清单（低置信：只有名称，真实 schema 需活体 toolkit） */
    List<String> systemToolNames();

    /** 本租户可见技能目录（尚未按角色过滤，过滤由调用方用同一道闸做） */
    List<SkillEntry> skills(String tenantId);

    /** 技能正文（本会话选中技能时叠加进「技能」桶） */
    String skillBody(String skillId, int roleLevel, String tenantId);

    /**
     * 该会话当前选中的技能 id（无则 null）。
     *
     * <p><b>已知边界</b>（批 7 · 7A 多审 dsflash 席 P2-1，属存量设计）：holder 只按 threadId 做键，
     * 而会话唯一键是 {@code (tenant_id, thread_id)}——两个租户恰好用了同一个 threadId 时，
     * 这里会读到对方的选择。影响面仅「技能」桶的字符统计（正文由 {@link #skillBody} 按本租户重取），
     * 不泄正文、不越权取数；改成带 tenant 需要动引擎侧 holder 的键（批 9 引擎收口时一并处理）。</p>
     */
    String selectedSkillId(String threadId);

    /** 记忆注入前缀（与真正注入时的文本同一份，保证口径一致） */
    String memoryPromptPrefix();

    /** 日期注入段文本（「其他」桶的唯一来源） */
    String dateSegmentText();

    /** 租户是否开启长期记忆（'1' = 开） */
    boolean memoryEnabled(String tenantId);

    /** 租户工具闸（技能可见性判定的入参之一） */
    Map<String, String> toolGate(String tenantId);

    /**
     * 当前登录人的角色档位（技能可见性判定用）。
     *
     * <p>取值同旧代码：{@code ToolIdentity.from(LoginHelper.getLoginUser())}，<b>没有</b>「读不到给 0」的兜底——
     * 批 7 · 7A 多审 dspro 席 P2-2 指出原注释把承诺写大了。现状为什么安全：唯一调用点在 skills 统计的
     * try/catch 内（异常→技能桶 0，与旧 controller 同款），且该口径只在请求线程内使用。</p>
     */
    int currentRoleLevel();
}
