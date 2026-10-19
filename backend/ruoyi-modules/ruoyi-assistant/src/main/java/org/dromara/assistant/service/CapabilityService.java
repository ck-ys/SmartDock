package org.dromara.assistant.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.domain.DcsAiModelCapability;
import org.dromara.assistant.mapper.AiModelCapabilityMapper;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * AI 模型能力目录服务（2026-10-09 批1，Q13-A 双层档位）。
 *
 * <p>读取优先级：本租户 MANUAL 覆盖行 &gt; 官方 SEED 行（provider 精确，rule_order 升序）
 * &gt; provider='*' 兜底行 &gt; 代码级兜底常量。<b>永不返回 null、永不抛未匹配异常</b>——
 * 未匹配模型按 SWITCH（仅开/关）最保守处理，绝不硬发档位参数（GLM5.3 类 400 防线）。</p>
 */
@Service
@RequiredArgsConstructor
public class CapabilityService {

    /** 代码级兜底（DB 兜底行也缺失时的最后防线；与 seed provider='*' 行同值） */
    public static final CapabilityVo FALLBACK = buildFallback();

    private final AiModelCapabilityMapper capabilityMapper;

    /** 能力视图（前端/映射层共用）：类型+档位+语义映射+窗口+来源 */
    @Data
    public static class CapabilityVo {
        /** TIERS / SWITCH / NONE */
        private String tierKind;
        /** 原生档位列表（NONE=空） */
        private List<String> tierValues = List.of();
        /** 语义档→原生档（FAST/BALANCED/DEEP；NONE=空） */
        private Map<String, String> semanticMap = Map.of();
        /** 上下文窗口（未核实=null，前端标"目录带出"） */
        private Long contextWindow;
        /** 单次回答最大输出 tokens（批C C2；未核实=null） */
        private Long maxOutputTokens;
        /** 支持图片输入（批C C2；未核实=false 保守） */
        private Boolean supportsImage = Boolean.FALSE;
        /** 支持视频输入（批C C2） */
        private Boolean supportsVideo = Boolean.FALSE;
        /** 支持音频输入（批C C2） */
        private Boolean supportsAudio = Boolean.FALSE;
        /** SEED / MANUAL / FALLBACK */
        private String source;
        /** 命中的规则（FALLBACK=null；调试与覆盖弹窗回显用） */
        private String matchedPattern;
    }

    /** 覆盖保存请求（管理端 ✎ 覆盖弹窗提交体） */
    @Data
    public static class OverrideBo {
        private String provider;
        private String modelName;
        /** TIERS / SWITCH / NONE */
        private String tierKind;
        private List<String> tierValues;
        /** 键限定 FAST/BALANCED/DEEP，值须在 tierValues 内（服务层校验） */
        private Map<String, String> semanticMap;
        private Long contextWindow;
        /** 单次回答最大输出 tokens（批C C2）。注意：null 落 MANUAL 行后会在 resolve 时
         *  遮蔽 SEED 目录值（MANUAL 整行替换语义，非逐列合并）——效果=不再限制/按模型默认 */
        private Long maxOutputTokens;
        /** 支持图片输入（业务批3-① Q2A 2026-10-12）：null=弹窗未采集=从 SEED 目录继承真实值；
         *  非 null=租户显式覆盖（如网关实际支持图片时纠正目录）。文本恒支持不设字段（无 supports_text 列，
         *  ZCode 高级设置同款口径：可选输入能力只有图片/视频/音频） */
        private Boolean supportsImage;
        /** 支持视频输入：语义同 {@link #supportsImage} */
        private Boolean supportsVideo;
        /** 支持音频输入：语义同 {@link #supportsImage} */
        private Boolean supportsAudio;
    }

    private static CapabilityVo buildFallback() {
        CapabilityVo vo = new CapabilityVo();
        vo.setTierKind("SWITCH");
        vo.setTierValues(List.of("enabled", "disabled"));
        Map<String, String> map = new LinkedHashMap<>();
        map.put("FAST", "disabled");
        map.put("BALANCED", "enabled");
        map.put("DEEP", "enabled");
        vo.setSemanticMap(map);
        vo.setContextWindow(200_000L);
        vo.setSource("FALLBACK");
        return vo;
    }

    /** 按模型名解析能力（管理端能力卡 / 助手输入条 / 映射装饰器共用入口） */
    public CapabilityVo matchCapability(String tenantId, String provider, String modelName) {
        return resolve(capabilityMapper.findCandidates(tenantId, provider), provider, modelName);
    }

    /** 保存租户覆盖（pattern 固定为模型名全匹配；重复保存=覆盖更新） */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public CapabilityVo override(String tenantId, OverrideBo bo) {
        validate(bo);
        String pattern = "^" + Pattern.quote(bo.getModelName()) + "$";
        // 批C 回修（glm53 P2-2）：删/插大小写对称——insert 侧大写归一，delete 侧须同样归一，
        // 否则小写 provider 二存时删不到旧行撞 uk_dcs_ai_cap 主键冲突
        bo.setProvider(bo.getProvider().toUpperCase());
        capabilityMapper.deleteManual(tenantId, bo.getProvider(), pattern);
        // supports_* 继承基线：管理端覆盖弹窗(业务批3-①起)三勾选恒定提交显式值；
        // bo 三列为 null（旧客户端/直调 API 未带）时从 SEED 行继承真实值，
        // 防 MANUAL 整行替换语义下空缺落列默认 false 使能力卡谎报「仅文本」（批C dsflash P2-1 语义保留）
        DcsAiModelCapability seedBest = selectSeed(capabilityMapper.findCandidates(tenantId, bo.getProvider()), bo.getModelName());
        DcsAiModelCapability row = new DcsAiModelCapability();
        row.setTenantId(tenantId);
        row.setProvider(bo.getProvider());
        row.setModelPattern(pattern);
        row.setTierKind(bo.getTierKind());
        row.setTierValues(bo.getTierValues() == null ? "[]" : JSONUtil.toJsonStr(bo.getTierValues()));
        row.setSemanticMap(bo.getSemanticMap() == null ? "{}" : JSONUtil.toJsonStr(bo.getSemanticMap()));
        row.setContextWindow(bo.getContextWindow());
        row.setMaxOutputTokens(bo.getMaxOutputTokens());
        // 业务批3-①（Q2A 2026-10-12）：supports_* 由弹窗三勾选框显式覆盖；未采集（null）
        // 时仍从 SEED 最优行继承（批C dsflash P2-1 语义保留，防 MANUAL 行谎报「仅文本」）
        DcsAiModelCapability merged = mergeSupports(seedBest, bo);
        row.setSupportsImage(merged.getSupportsImage());
        row.setSupportsVideo(merged.getSupportsVideo());
        row.setSupportsAudio(merged.getSupportsAudio());
        row.setSource("MANUAL");
        row.setRuleOrder(1);
        capabilityMapper.insert(row);
        return matchCapability(tenantId, bo.getProvider(), bo.getModelName());
    }

    /** 取该模型名命中的最优非 MANUAL 行（SEED 精确 > '*' 兜底），
     *  供覆盖保存时继承 supports_* 输入类型（调用方未显式提交三列时） */
    private static DcsAiModelCapability selectSeed(List<DcsAiModelCapability> rows, String modelName) {
        DcsAiModelCapability seed = null;
        DcsAiModelCapability wildcard = null;
        if (rows != null) {
            for (DcsAiModelCapability r : rows) {
                if ("MANUAL".equals(r.getSource()) || !matches(r.getModelPattern(), modelName)) {
                    continue;
                }
                if ("*".equals(r.getProvider())) {
                    if (wildcard == null || r.getRuleOrder() < wildcard.getRuleOrder()) {
                        wildcard = r;
                    }
                } else if (seed == null || r.getRuleOrder() < seed.getRuleOrder()) {
                    seed = r;
                }
            }
        }
        return seed != null ? seed : wildcard;
    }

    /**
     * 支持输入三列合成（业务批3-① Q2A 2026-10-12，纯静态可单测）：
     * 请求字段非 null 用请求值（租户显式覆盖），否则继承 SEED 最优行；
     * SEED 也缺（无目录行模型）时维持 false 保守值。文本恒支持不参与合成。
     */
    static DcsAiModelCapability mergeSupports(DcsAiModelCapability seedBest, OverrideBo bo) {
        DcsAiModelCapability out = new DcsAiModelCapability();
        out.setSupportsImage(bo.getSupportsImage() != null ? bo.getSupportsImage()
            : seedBest != null && Boolean.TRUE.equals(seedBest.getSupportsImage()));
        out.setSupportsVideo(bo.getSupportsVideo() != null ? bo.getSupportsVideo()
            : seedBest != null && Boolean.TRUE.equals(seedBest.getSupportsVideo()));
        out.setSupportsAudio(bo.getSupportsAudio() != null ? bo.getSupportsAudio()
            : seedBest != null && Boolean.TRUE.equals(seedBest.getSupportsAudio()));
        return out;
    }

    /** 恢复官方（删 MANUAL 行，下次读取回退 SEED） */
    public void resetToSeed(String tenantId, String provider, String modelName) {
        String pattern = "^" + Pattern.quote(modelName) + "$";
        capabilityMapper.deleteManual(tenantId, provider.toUpperCase(), pattern);
    }

    private void validate(OverrideBo bo) {
        if (bo.getProvider() == null || bo.getProvider().isBlank()
            || bo.getModelName() == null || bo.getModelName().isBlank()) {
            throw new ServiceException("厂商与模型名不能为空");
        }
        if (!"TIERS".equals(bo.getTierKind()) && !"SWITCH".equals(bo.getTierKind())
            && !"NONE".equals(bo.getTierKind())) {
            throw new ServiceException("能力类型须为 TIERS/SWITCH/NONE");
        }
        if ("TIERS".equals(bo.getTierKind()) && (bo.getTierValues() == null || bo.getTierValues().isEmpty())) {
            throw new ServiceException("可调档模型必须填档位列表");
        }
        if (bo.getSemanticMap() != null) {
            for (Map.Entry<String, String> e : bo.getSemanticMap().entrySet()) {
                if (!List.of("FAST", "BALANCED", "DEEP").contains(e.getKey())) {
                    throw new ServiceException("语义档键只允许 FAST/BALANCED/DEEP");
                }
                if (bo.getTierValues() != null && !bo.getTierValues().contains(e.getValue())) {
                    throw new ServiceException("语义档落点必须在档位列表内：" + e.getKey() + "→" + e.getValue());
                }
            }
        }
    }

    /** 纯解析逻辑（静态可单测）：候选行 → 最优命中 → VO；无命中回 FALLBACK */
    public static CapabilityVo resolve(List<DcsAiModelCapability> rows, String provider, String modelName) {
        DcsAiModelCapability best = selectBest(rows, provider, modelName);
        if (best == null) {
            return FALLBACK;
        }
        CapabilityVo vo = new CapabilityVo();
        vo.setTierKind(best.getTierKind());
        vo.setTierValues(parseArray(best.getTierValues()));
        vo.setSemanticMap(parseMap(best.getSemanticMap()));
        vo.setContextWindow(best.getContextWindow());
        vo.setMaxOutputTokens(best.getMaxOutputTokens());
        vo.setSupportsImage(Boolean.TRUE.equals(best.getSupportsImage()));
        vo.setSupportsVideo(Boolean.TRUE.equals(best.getSupportsVideo()));
        vo.setSupportsAudio(Boolean.TRUE.equals(best.getSupportsAudio()));
        vo.setSource(best.getSource());
        vo.setMatchedPattern(best.getModelPattern());
        return vo;
    }

    /** 优先级：MANUAL 命中 > SEED 精确 provider 命中 > provider='*' 兜底行；同级 rule_order 小者先 */
    public static DcsAiModelCapability selectBest(List<DcsAiModelCapability> rows, String provider, String modelName) {
        DcsAiModelCapability manual = null;
        DcsAiModelCapability seed = null;
        DcsAiModelCapability wildcard = null;
        if (rows != null) {
            for (DcsAiModelCapability row : rows) {
                if (!matches(row.getModelPattern(), modelName)) {
                    continue;
                }
                if ("MANUAL".equals(row.getSource())) {
                    manual = better(manual, row);
                } else if ("*".equals(row.getProvider())) {
                    wildcard = better(wildcard, row);
                } else {
                    seed = better(seed, row);
                }
            }
        }
        if (manual != null) {
            return manual;
        }
        if (seed != null) {
            return seed;
        }
        return wildcard;
    }

    private static DcsAiModelCapability better(DcsAiModelCapability cur, DcsAiModelCapability next) {
        if (cur == null) {
            return next;
        }
        int curOrder = cur.getRuleOrder() == null ? Integer.MAX_VALUE : cur.getRuleOrder();
        int nextOrder = next.getRuleOrder() == null ? Integer.MAX_VALUE : next.getRuleOrder();
        return nextOrder < curOrder ? next : cur;
    }

    /** 全匹配正则（大小写不敏感）；坏正则跳过该行不炸（SEED 行手误防线） */
    public static boolean matches(String pattern, String modelName) {
        if (pattern == null || modelName == null) {
            return false;
        }
        try {
            return Pattern.compile("^(?:" + pattern + ")$", Pattern.CASE_INSENSITIVE)
                .matcher(modelName).matches();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    private static List<String> parseArray(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        return new ArrayList<>(JSONUtil.parseArray(json).toList(String.class));
    }

    private static Map<String, String> parseMap(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return Map.of();
        }
        JSONObject obj = JSONUtil.parseObj(json);
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : obj.keySet()) {
            map.put(key, obj.getStr(key));
        }
        return map;
    }
}
