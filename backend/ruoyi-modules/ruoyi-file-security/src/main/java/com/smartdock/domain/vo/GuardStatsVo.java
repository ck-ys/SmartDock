package com.smartdock.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 安全检测近 7 天命中概览（设置弹窗顶部概览条，Q4=A）
 *
 * @author smartdock
 */
@Data
public class GuardStatsVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 近 7 天命中总数 */
    private Long total;

    /** 其中已拒传（ENFORCE DENY） */
    private Long denied;

    /** 其中强制进审（ENFORCE REVIEW） */
    private Long reviewed;

    /** 其中观察放行（OBSERVE 命中留痕） */
    private Long observed;
}
