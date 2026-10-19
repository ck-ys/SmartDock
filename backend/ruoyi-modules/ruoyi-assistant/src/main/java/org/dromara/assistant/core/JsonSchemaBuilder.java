package org.dromara.assistant.core;

import com.smartdock.assistant.spi.BizParamSpec;
import com.smartdock.assistant.spi.BizToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BizParamSpec 列表 → OpenAI 风格 JSON Schema map(纯函数, 单测主战场)。
 *
 * <p>type=enum 展开成 type=string + enum 清单; number 用 JSON Schema 的 number;
 * required 汇总到顶层。未声明类型兜底 string(宽松容错, 模型侧少踩怪类型)。</p>
 *
 * @author smartdock
 */
public final class JsonSchemaBuilder {

    private JsonSchemaBuilder() {
    }

    public static Map<String, Object> build(BizToolSpec spec) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (spec.getParams() != null) {
            for (BizParamSpec p : spec.getParams()) {
                Map<String, Object> prop = new LinkedHashMap<>();
                String type = p.getType() == null ? "string" : p.getType();
                if ("enum".equals(type)) {
                    prop.put("type", "string");
                    prop.put("enum", p.getEnumValues() == null ? List.of() : p.getEnumValues());
                } else if ("number".equals(type) || "boolean".equals(type) || "string".equals(type)) {
                    prop.put("type", type);
                } else {
                    prop.put("type", "string");
                }
                if (p.getDescription() != null) {
                    prop.put("description", p.getDescription());
                }
                properties.put(p.getName(), prop);
                if (p.isRequired()) {
                    required.add(p.getName());
                }
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }
}
