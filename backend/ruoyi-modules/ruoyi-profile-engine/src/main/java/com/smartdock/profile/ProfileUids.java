package com.smartdock.profile;

import com.smartdock.profile.event.ProfileEventBo;
import lombok.experimental.UtilityClass;

/**
 * event_uid 构建工具(业务侧拼幂等键的统一格式)。
 *
 * <p>格式: {entity}:{action}:{业务ID}[:{版本}]——引擎侧以 (tenant, source, event_uid) 唯一约束去重。
 * 不强制走本工具, 但同一业务源内必须保证同类事件的自然键稳定且不撞车。</p>
 *
 * @author smartdock
 */
@UtilityClass
public class ProfileUids {

    /**
     * 标准三段式: 如 ("submission", "submit", 12345) → "submission:submit:12345"。
     */
    public String uid(String entity, String action, Object bizId) {
        return entity + ":" + action + ":" + bizId;
    }

    /**
     * 带版本四段式: 重提/重审等会产生第二条同类事件时用, 如 ("submission", "submit", 12345, "v2")。
     */
    public String uid(String entity, String action, Object bizId, String version) {
        return entity + ":" + action + ":" + bizId + ":" + version;
    }
}
