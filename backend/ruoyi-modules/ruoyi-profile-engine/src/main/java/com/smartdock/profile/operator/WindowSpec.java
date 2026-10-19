package com.smartdock.profile.operator;

import lombok.Data;

/**
 * 时间窗规格(calc_config.window)。锚点 = 重算时刻。
 *
 * @author smartdock
 */
@Data
public class WindowSpec {

    /** all(缺省) | rolling */
    private String type = "all";

    /** rolling 时必填: 最近 N 天 */
    private Integer days;

    public boolean isRolling() {
        return "rolling".equalsIgnoreCase(type) && days != null && days > 0;
    }

    public String describe() {
        return isRolling() ? "rolling-" + days + "d" : "all-time";
    }
}
