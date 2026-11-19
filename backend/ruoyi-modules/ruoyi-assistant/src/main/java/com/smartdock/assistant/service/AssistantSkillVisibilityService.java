package com.smartdock.assistant.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 技能可见性判定统一入口（批 4 题 2 案 1，2026-11-19）。
 *
 * <p>改之前，「谁能看见哪个技能」这件事在 5 处手写：1 处权威（{@code AssistantSkillGateMiddleware}
 * 的模型侧过滤）+ 4 处复写（技能目录、上下文统计、技能详情、开关矩阵展示）。它们各自把
 * 「算键 → 查矩阵覆盖档 → 与内置档取大 → 比角色档」重算一遍，改规则要改 5 个地方。</p>
 *
 * <p>本类只做<b>编排</b>：公式本体仍在引擎侧（{@code ToolRegistry.effectiveMinRoleSingle} 与
 * {@code AssistantSkillGateMiddleware.gateKey} 各自是单一真相），本类把它们组合一次，对外只回答
 * 「这一条当前角色能不能看」。判定路径<b>纯内存、不查库、不抛异常</b>——矩阵快照与停用集由调用方
 * 在入口处取好传进来（引擎侧同款护栏，见 {@code filterVisibleImpl} 注释）。</p>
 *
 * <p><b>为什么用内联全名而不是 import</b>：门禁第 8 条对「新根 service import legacy」零容忍
 * （见 {@code PackageHygieneTest}）。公式搬家属批 9 引擎收口的活，本批「只搬家不装修」，故按批 3
 * 额度服务的成熟先例写成内联全名 + 点名豁免；豁免按「文件|全名|次数」精确对账，多一处少一处都红。
 * 批 9 引擎收口后本类改 import 并删豁免行。</p>
 */
@Service
public class AssistantSkillVisibilityService {

    /**
     * 生效档位：{@code -1} = 本租户把该技能关掉了（off）；否则为「最低可见角色档」。
     *
     * <p>开关矩阵展示端点直接用它填 effective 列，与工具行共用同一套档位语义（只升不降）。</p>
     *
     * <p><b>非法 id 一律 fail-closed</b>：{@code null}/空白返回 {@code -1}（等同 off），并在算键前
     * {@code trim()}。理由与引擎侧 {@code AssistantSkillGateMiddleware.visibleTo} 对齐——那里
     * {@code null}/空白直接判不可见、id 也是 trim 后比较；两条判定路径若对同一个坏入参给出相反答案，
     * 「统一入口」就名不副实。当前 4 个调用点均传 {@code LoadedSkill.id()}（非空、无空白），
     * 本护栏不可达，纯防批 9 新调用点踩坑（多审并发席 P2-2）。</p>
     */
    public int effectiveMinRole(String skillId, int builtinMinRole, Map<String, String> toolGate) {
        if (skillId == null || skillId.isBlank()) {
            return -1;
        }
        String key = com.smartdock.assistant.legacy.core.AssistantSkillGateMiddleware.gateKey(skillId.trim());
        return com.smartdock.assistant.legacy.core.ToolRegistry.effectiveMinRoleSingle(
            key, builtinMinRole, toolGate == null ? null : toolGate.get(key));
    }

    /**
     * 当前角色能不能看见这一条：{@code effective >= 0 && roleLevel >= effective}。
     *
     * <p>目录、上下文统计、技能详情三处调用点此前各自重算这段，现在统一从这里取答案。</p>
     */
    public boolean visible(String skillId, int builtinMinRole, int roleLevel, Map<String, String> toolGate) {
        int effective = effectiveMinRole(skillId, builtinMinRole, toolGate);
        return effective >= 0 && roleLevel >= effective;
    }
}
