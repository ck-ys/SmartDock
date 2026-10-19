package com.smartdock.assistant.spi;

import lombok.Data;

import java.util.List;

/**
 * 工具参数声明(一期标量与枚举)。
 *
 * @author smartdock
 */
@Data
public class BizParamSpec {

    /** string | number | boolean | enum */
    private String type = "string";

    private String name;

    private String description;

    private boolean required;

    /** type=enum 时必填: 合法值清单 */
    private List<String> enumValues;

    public static BizParamSpec string(String name, String description, boolean required) {
        BizParamSpec p = new BizParamSpec();
        p.setType("string");
        p.setName(name);
        p.setDescription(description);
        p.setRequired(required);
        return p;
    }

    public static BizParamSpec number(String name, String description, boolean required) {
        BizParamSpec p = string(name, description, required);
        p.setType("number");
        return p;
    }

    public static BizParamSpec bool(String name, String description, boolean required) {
        BizParamSpec p = string(name, description, required);
        p.setType("boolean");
        return p;
    }

    public static BizParamSpec enumeration(String name, String description, boolean required, List<String> values) {
        BizParamSpec p = string(name, description, required);
        p.setType("enum");
        p.setEnumValues(values);
        return p;
    }
}
