package com.smartdock.dcs.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 更新日志条目（存 dcs_changelog.items JSON）。
 *
 * @author smartdock
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChangelogItem {

    public static final String TYPE_NEW = "new";
    public static final String TYPE_OPTIMIZE = "optimize";
    public static final String TYPE_FIX = "fix";

    /** new=新增 optimize=优化 fix=修复 */
    private String type;

    private String text;
}
