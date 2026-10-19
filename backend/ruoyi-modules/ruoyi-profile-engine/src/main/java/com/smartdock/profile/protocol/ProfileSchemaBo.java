package com.smartdock.profile.protocol;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件 schema 注册参数。
 *
 * @author smartdock
 */
@Data
public class ProfileSchemaBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String eventName;

    private String schemaVersion = "1-0-0";

    /** {type:"object", required:[...], properties:{...}} */
    private Map<String, Object> schema = new LinkedHashMap<>();
}
