package com.smartdock.profile.protocol;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件 schema 校验参数。
 *
 * @author smartdock
 */
@Data
public class ProfileSchemaValidateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String eventName;

    private String schemaVersion;

    private Map<String, Object> props = new LinkedHashMap<>();

    private Map<String, Object> context = new LinkedHashMap<>();

    private Map<String, Object> traits = new LinkedHashMap<>();
}
