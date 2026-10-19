package org.dromara.test.assistant;

import org.dromara.assistant.core.AssistantThinkingTierHolder;
import org.dromara.assistant.core.SemanticThinkingParams;
import org.dromara.assistant.service.CapabilityService.CapabilityVo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义档→原生参数映射单测（批2，2026-10-09）。四家按官方核实值断言；
 * Review Focus ①：GLM5.3 任何语义档都不得产出 disabled（官方传 disabled 直接 400）。
 */
@Tag("dev")
class SemanticThinkingParamsTest {

    private static CapabilityVo vo(String kind, List<String> values, Map<String, String> map) {
        CapabilityVo v = new CapabilityVo();
        v.setTierKind(kind);
        v.setTierValues(values);
        v.setSemanticMap(map);
        return v;
    }

    /** GLM5.3（seed 同值）：TIERS low/high/max，FAST→low——结构上无 disabled */
    private static CapabilityVo glm() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("FAST", "low");
        map.put("BALANCED", "high");
        map.put("DEEP", "max");
        return vo("TIERS", List.of("low", "high", "max"), map);
    }

    /** DeepSeek v4（seed 同值）：TIERS 含 disabled 原生档（语义表不映射它=逃生口留给直传） */
    private static CapabilityVo deepseek() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("FAST", "low");
        map.put("BALANCED", "high");
        map.put("DEEP", "max");
        return vo("TIERS", List.of("disabled", "low", "high", "max"), map);
    }

    /** MiniMax M3（seed 同值）：SWITCH adaptive/disabled */
    private static CapabilityVo m3() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("FAST", "disabled");
        map.put("BALANCED", "adaptive");
        map.put("DEEP", "adaptive");
        return vo("SWITCH", List.of("adaptive", "disabled"), map);
    }

    /** LongCat（seed 同值）：SWITCH enabled/disabled */
    private static CapabilityVo longcat() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("FAST", "disabled");
        map.put("BALANCED", "enabled");
        map.put("DEEP", "enabled");
        return vo("SWITCH", List.of("enabled", "disabled"), map);
    }

    /** RF①：GLM5.3 三档全断言——FAST→low（不是 disabled），DEEP→max */
    @Test
    void glmNeverEmitsDisabled() {
        assertEquals("low", SemanticThinkingParams.build(glm(), "fast").reasoningEffort());
        assertEquals("max", SemanticThinkingParams.build(glm(), "deep").reasoningEffort());
        assertNull(SemanticThinkingParams.build(glm(), "fast").thinkingType());
        for (String tier : new String[]{"fast", "balanced", "deep"}) {
            SemanticThinkingParams.Params p = SemanticThinkingParams.build(glm(), tier);
            assertNull(p.thinkingType());
            if (p.reasoningEffort() != null) {
                org.junit.jupiter.api.Assertions.assertNotEquals("disabled", p.reasoningEffort());
            }
        }
    }

    /** DS v4：TIERS 走 reasoning_effort 通道 */
    @Test
    void deepseekTiersChannel() {
        assertEquals("low", SemanticThinkingParams.build(deepseek(), "fast").reasoningEffort());
        assertEquals("max", SemanticThinkingParams.build(deepseek(), "deep").reasoningEffort());
        assertNull(SemanticThinkingParams.build(deepseek(), "fast").thinkingType());
    }

    /** M3：SWITCH 走 thinking.type 通道（FAST=关思考，DEEP=adaptive） */
    @Test
    void m3SwitchChannel() {
        assertEquals("disabled", SemanticThinkingParams.build(m3(), "fast").thinkingType());
        assertEquals("adaptive", SemanticThinkingParams.build(m3(), "deep").thinkingType());
        assertNull(SemanticThinkingParams.build(m3(), "deep").reasoningEffort());
    }

    /** LongCat：FAST=关、DEEP=enabled（官方 enabled/disabled 词表） */
    @Test
    void longcatSwitchVocabulary() {
        assertEquals("disabled", SemanticThinkingParams.build(longcat(), "fast").thinkingType());
        assertEquals("enabled", SemanticThinkingParams.build(longcat(), "deep").thinkingType());
    }

    /** BALANCED 恒不注入（服务端默认档；与既有 balanced=不追加口径一致） */
    @Test
    void balancedNeverInjects() {
        for (CapabilityVo c : new CapabilityVo[]{glm(), deepseek(), m3(), longcat()}) {
            assertTrue(SemanticThinkingParams.build(c, "balanced").isEmpty(), c.getTierKind());
        }
    }

    /** NONE（M2.x 强制开不可关）：任何档都不发参数 */
    @Test
    void noneNeverInjects() {
        CapabilityVo none = vo("NONE", List.of(), Map.of());
        for (String tier : new String[]{"fast", "balanced", "deep"}) {
            assertTrue(SemanticThinkingParams.build(none, tier).isEmpty());
        }
    }

    /** capability=null（未解析）与语义缺键：保守不注入 */
    @Test
    void nullCapabilityOrMissingKeyInjectsNothing() {
        assertTrue(SemanticThinkingParams.build(null, "fast").isEmpty());
        CapabilityVo missing = vo("TIERS", List.of("low", "high"), Map.of());
        assertTrue(SemanticThinkingParams.build(missing, "fast").isEmpty());
        assertTrue(SemanticThinkingParams.build(missing, "deep").isEmpty());
    }

    /** 语义键归一：holder fast/balanced/deep → FAST/BALANCED/DEEP；未知值按 BALANCED */
    @Test
    void semanticKeyNormalization() {
        assertEquals("FAST", SemanticThinkingParams.semanticKey(AssistantThinkingTierHolder.TIER_FAST));
        assertEquals("DEEP", SemanticThinkingParams.semanticKey(AssistantThinkingTierHolder.TIER_DEEP));
        assertEquals("BALANCED", SemanticThinkingParams.semanticKey(AssistantThinkingTierHolder.TIER_BALANCED));
        assertEquals("BALANCED", SemanticThinkingParams.semanticKey("whatever"));
        assertEquals("BALANCED", SemanticThinkingParams.semanticKey(null));
    }
}
