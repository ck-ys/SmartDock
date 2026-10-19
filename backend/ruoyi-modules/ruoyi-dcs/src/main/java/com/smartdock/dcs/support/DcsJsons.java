package com.smartdock.dcs.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微核心 jsonb 字段的 String ⇄ 对象 转换助手(坏 JSON 一律容错为空值, 不炸业务)。
 *
 * <p>自带静态 Jackson mapper 而不走 common-json 的 JsonUtils——后者静态初始化
 * 依赖 Spring 容器取 ObjectMapper，纯单测(无容器)会 ExceptionInInitializerError；
 * 微核心实体助手要能在任何上下文可用（教科书铁律：域对象零容器依赖）。
 * 序列化目标只有 List/Map/标量，默认 mapper 语义与容器版一致。</p>
 *
 * @author smartdock
 */
public final class DcsJsons {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DcsJsons() {
    }

    /** "[1,2,3]" → [1L,2L,3L]; null/坏 JSON → 空表 */
    public static List<Long> longList(String json) {
        List<Long> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            List<?> raw = MAPPER.readValue(json, List.class);
            if (raw != null) {
                for (Object o : raw) {
                    if (o instanceof Number n) {
                        out.add(n.longValue());
                    } else if (o != null) {
                        try {
                            out.add(Long.parseLong(String.valueOf(o)));
                        } catch (NumberFormatException badElement) {
                            // 坏元素跳过(三审 dspro P2-11): 半截列表比"中断返回前缀"可预期
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 容错: 数据面问题不阻断读链
        }
        return out;
    }

    /** ["A","B"] 字符串候选; null/坏 JSON → 空表 */
    public static List<String> stringList(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            List<?> raw = MAPPER.readValue(json, List.class);
            if (raw != null) {
                for (Object o : raw) {
                    if (o != null) {
                        out.add(String.valueOf(o));
                    }
                }
            }
        } catch (Exception ignored) {
            // 容错同上
        }
        return out;
    }

    /** {k:v} → Map; null/坏 JSON → 空表 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m = MAPPER.readValue(json, Map.class);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 对象 → JSON 文本(失败回 "null"，调用方按空值容错) */
    public static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "null";
        }
    }
}
