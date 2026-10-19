package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 静态分群成员导入参数。
 *
 * @author smartdock
 */
@Data
public class ProfileSegmentImportBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** REPLACE: 覆盖为本批成员; APPEND: 在当前成员上追加 */
    private String mode = "REPLACE";

    /** 主体 ID 列表(也支持直接粘贴换行/逗号分隔文本) */
    private List<String> subjectIds = new ArrayList<>();

    /** 只导入该主体类型; 空 = 匹配全部类型 */
    private String subjectType;
}
