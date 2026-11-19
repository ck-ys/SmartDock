package com.smartdock.assistant.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 行参数的形状校验与解析（批 5 题 2 案 1，2026-11-19：从 legacy 的 McpPilotService
 * 抽出的纯函数）。
 *
 * <p>为什么单独成件：管理面（保存服务行）与挂载面（建会话连外部服务）都要解析
 * args / headers，两侧共用同一套口径；抽到 support 后新根 service 用它们不必回头
 * import 旧根（规则 8 零容忍），旧根也继续用同一份实现——不存在两份校验。</p>
 *
 * <p>本类无 Spring 依赖、无状态，单测可直测（原 McpPilotServiceTest 的断言口径不变）。</p>
 */
public final class McpToolFacts {

    /** 独立 mapper（JsonUtils 静态块依赖 Spring 容器，纯函数单测不能走它） */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private McpToolFacts() {
    }

    /**
     * 请求头形状校验（保存面入口）：与 {@link #parseHeaders(String)} 同口径。
     *
     * <p>G4-#1（2026-10-22）：放开请求头接收（原试点一律拒收）。三面封死明文风险后
     * 黑名单不再必要——值显式 AES 落库 + 回显只出键名 + save 端点不落请求体日志；
     * 键名黑名单方案被否：鉴权头（Authorization/X-Api-Key 等）正是本功能要接的目标。
     * 只做形状与尺寸校验。</p>
     */
    public static void validateHeaders(String headersJson) {
        parseHeaders(headersJson);
    }

    /**
     * headers JSON → 不可变 Map（HTTP 行挂载时喂 McpClientBuilder.headers）。
     *
     * <p>校验：整体 ≤4KB、JSON 对象、无重复键、≤10 键、键 1-64 位字母数字-_、
     * 值非空字符串 ≤1024 字符（G4 三审回修：补整体上限防 Jackson 全量解析面、
     * 重复键显式拒绝防"后值静默覆盖"歧义）。</p>
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank() || headersJson.trim().equals("{}")) {
            return Map.of();
        }
        if (headersJson.length() > 4096) {
            throw new ServiceException("headers JSON 整体过长（≤4KB）");
        }
        Map<String, Object> raw;
        try {
            // 重复键显式拒绝：默认 Jackson 后值静默覆盖——管理员改配置看不出哪个键被吞
            JSON_MAPPER.reader()
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .readTree(headersJson);
            raw = JSON_MAPPER.readValue(headersJson, Map.class);
        } catch (JsonProcessingException e) {
            throw new ServiceException("headers 须是无重复键的 {\"键\":\"值\"} JSON 对象");
        }
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (raw.size() > 10) {
            throw new ServiceException("headers 最多 10 个键");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String k = e.getKey();
            if (k == null || k.isBlank() || k.length() > 64 || !k.matches("[A-Za-z0-9_-]+")) {
                throw new ServiceException("header 键名须为 1-64 位字母数字-_（收到：" + k + "）");
            }
            Object v = e.getValue();
            if (!(v instanceof String s) || s.isBlank()) {
                throw new ServiceException("header 值须为非空字符串（键：" + k + "）");
            }
            if (s.length() > 1024) {
                throw new ServiceException("header 值过长（≤1024 字符，键：" + k + "）");
            }
            out.put(k, s);
        }
        return Collections.unmodifiableMap(out);
    }

    /** args JSON 数组 → 字符串列表；空/空白 = 空列表，元素空串一律拒绝 */
    public static List<String> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return List.of();
        }
        String[] arr;
        try {
            arr = JSON_MAPPER.readValue(argsJson, String[].class);
        } catch (JsonProcessingException e) {
            throw new ServiceException("args 须是字符串数组 JSON");
        }
        if (arr == null || Arrays.stream(arr).anyMatch(x -> x == null || x.isBlank())) {
            throw new ServiceException("args 须是非空字符串数组");
        }
        return Arrays.asList(arr);
    }

    /** 逗号分隔串 → 去空白、去空项后的列表（配置白名单解析用） */
    public static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
