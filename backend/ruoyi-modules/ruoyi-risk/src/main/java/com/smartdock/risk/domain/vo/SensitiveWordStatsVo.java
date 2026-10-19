package com.smartdock.risk.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 敏感词库全量统计 VO(2026-08-23 词库管理页统计卡数据源)。
 *
 * <p>口径=当前租户未删除词条<b>全量</b>计数(旧前端实现只统计当前页, 有误导性)。</p>
 *
 * @author smartdock
 */
@Data
public class SensitiveWordStatsVo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** 词条总数 */
    private long total;
    /** 启用中的词条数 */
    private long enabled;
    /** 动作=MASK(替换) 的词条数 */
    private long actionMask;
    /** 动作=BLOCK(阻断) 的词条数 */
    private long actionBlock;
    /** 动作=REVIEW(转人工复核) 的词条数 */
    private long actionReview;
}
