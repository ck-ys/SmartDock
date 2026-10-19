package org.dromara.test.assistant;

import org.dromara.assistant.domain.DcsAiModelCapability;
import org.dromara.assistant.service.CapabilityService;
import org.dromara.assistant.service.CapabilityService.CapabilityVo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力目录匹配/兜底纯逻辑单测（批1，2026-10-09）。
 * Review Focus：②未匹配必须回兜底 SWITCH 不可 null；③MANUAL 优先 SEED；
 * 坏正则跳行不炸；rule_order specific 先行；语义落点 JSON 解析。
 */
@Tag("dev")
class CapabilityMatchUnitTest {

    private static DcsAiModelCapability row(String provider, String pattern, String kind,
                                            String source, Integer order) {
        DcsAiModelCapability r = new DcsAiModelCapability();
        r.setProvider(provider);
        r.setModelPattern(pattern);
        r.setTierKind(kind);
        r.setTierValues("[\"low\",\"high\",\"max\"]");
        r.setSemanticMap("{\"FAST\":\"low\",\"BALANCED\":\"high\",\"DEEP\":\"max\"}");
        r.setSource(source);
        r.setRuleOrder(order);
        return r;
    }

    /** RF②：无任何命中 → FALLBACK（SWITCH 仅开关，永不 null / 永不抛） */
    @Test
    void unmatchedFallsBackToSwitch() {
        CapabilityVo vo = CapabilityService.resolve(
            List.of(row("DEEPSEEK", "^deepseek-.*$", "TIERS", "SEED", 10)),
            "MINIMAX", "unknown-model-9");
        assertEquals("FALLBACK", vo.getSource());
        assertEquals("SWITCH", vo.getTierKind());
        assertEquals("disabled", vo.getSemanticMap().get("FAST"));
        assertNull(vo.getMatchedPattern());
    }

    /** RF③：MANUAL 覆盖行压过同名 SEED 行 */
    @Test
    void manualOverridesSeed() {
        DcsAiModelCapability seed = row("DEEPSEEK", "^deepseek-v4.*$", "TIERS", "SEED", 10);
        DcsAiModelCapability manual = row("DEEPSEEK", "^deepseek\\-v4\\-flash$", "SWITCH", "MANUAL", 1);
        manual.setSemanticMap("{\"FAST\":\"disabled\",\"BALANCED\":\"enabled\",\"DEEP\":\"enabled\"}");
        CapabilityVo vo = CapabilityService.resolve(List.of(seed, manual), "DEEPSEEK", "deepseek-v4-flash");
        assertEquals("MANUAL", vo.getSource());
        assertEquals("SWITCH", vo.getTierKind());
        assertEquals("disabled", vo.getSemanticMap().get("FAST"));
    }

    /** SEED 内部：rule_order 小者（specific）先命中 */
    @Test
    void seedSpecificOrderWins() {
        DcsAiModelCapability generic = row("MINIMAX", "^MiniMax-M3.*$", "SWITCH", "SEED", 10);
        DcsAiModelCapability specific = row("MINIMAX", "^MiniMax-M3\\.1-Flash.*$", "TIERS", "SEED", 5);
        specific.setTierValues("[\"low\",\"medium\",\"high\",\"xhigh\",\"max\"]");
        CapabilityVo vo = CapabilityService.resolve(List.of(generic, specific), "MINIMAX", "MiniMax-M3.1-Flash-Preview");
        assertEquals("SEED", vo.getSource());
        assertEquals(5, CapabilityService.selectBest(List.of(generic, specific), "MINIMAX", "MiniMax-M3.1-Flash-Preview").getRuleOrder());
        assertEquals("TIERS", vo.getTierKind());
    }

    /** 兜底行（provider='*'）在厂商未收录时生效——longcat 类二态模型落兜底=正确答案 */
    @Test
    void wildcardRowCoversUnknownProvider() {
        DcsAiModelCapability wildcard = row("*", ".*", "SWITCH", "SEED", 999);
        CapabilityVo vo = CapabilityService.resolve(List.of(wildcard), "SOMEONE", "brand-new-model");
        assertEquals("SEED", vo.getSource());
        assertEquals("SWITCH", vo.getTierKind());
        assertEquals(".*", vo.getMatchedPattern());
    }

    /** 坏正则行跳过不炸，落同组下一行 */
    @Test
    void badRegexRowSkipped() {
        DcsAiModelCapability bad = row("QWEN", "^qwen[.*$", "TIERS", "SEED", 5);
        DcsAiModelCapability good = row("QWEN", "^qwen.*$", "SWITCH", "SEED", 30);
        CapabilityVo vo = CapabilityService.resolve(List.of(bad, good), "QWEN", "qwen3.7-max");
        assertEquals("SWITCH", vo.getTierKind());
        assertEquals("^qwen.*$", vo.getMatchedPattern());
    }

    /** 正则大小写不敏感（现库 model_name 大小写混用防线） */
    @Test
    void matchIsCaseInsensitive() {
        assertTrue(CapabilityService.matches("^LongCat-.*$", "longcat-2.5-preview-free"));
        assertTrue(CapabilityService.matches("^minimax-m3.*$", "MiniMax-M3"));
        assertFalse(CapabilityService.matches("^deepseek-.*$", "glm-5.3"));
    }

    /** NONE（不支持思考）：档位与映射为空集合，不抛 NPE */
    @Test
    void noneKindParsesEmptyCollections() {
        DcsAiModelCapability none = row("MINIMAX", "^MiniMax-M2.*$", "NONE", "SEED", 20);
        none.setTierValues("[]");
        none.setSemanticMap("{}");
        CapabilityVo vo = CapabilityService.resolve(List.of(none), "MINIMAX", "MiniMax-M2.7");
        assertEquals("NONE", vo.getTierKind());
        assertTrue(vo.getTierValues().isEmpty());
        assertTrue(vo.getSemanticMap().isEmpty());
    }
}
