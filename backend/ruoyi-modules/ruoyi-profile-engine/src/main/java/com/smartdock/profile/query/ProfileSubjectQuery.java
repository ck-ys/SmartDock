package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 画像主体列表查询条件。
 *
 * @author smartdock
 */
@Data
public class ProfileSubjectQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String source;

    private String subjectType;

    /** 主体 ID / 显示名模糊搜索 */
    private String keyword;

    private BigDecimal minScore;

    private BigDecimal maxScore;

    /** lastEvent(缺省) | score | events | name */
    private String sortBy = "lastEvent";

    /** desc(缺省) | asc */
    private String sortOrder = "desc";
}
