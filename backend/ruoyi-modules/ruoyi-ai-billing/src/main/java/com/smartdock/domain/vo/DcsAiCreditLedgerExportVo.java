package com.smartdock.domain.vo;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 积分流水导出 VO（AI 缺口批 Q7，2026-10-14）：与「积分流水」页同口径的中文列。
 *
 * @author smartdock
 */
@Data
public class DcsAiCreditLedgerExportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 时间 */
    @ExcelProperty("时间")
    private String createTime;

    /** 类型（订阅/加油包/消耗/调整） */
    @ExcelProperty("类型")
    private String bizType;

    /** 积分变动（消耗为负） */
    @ExcelProperty("积分变动")
    private String credits;

    /** 周池积分 */
    @ExcelProperty("周池积分")
    private String weeklyCredits;

    /** 加油包积分 */
    @ExcelProperty("加油包积分")
    private String tankCredits;

    /** 业务场景 */
    @ExcelProperty("场景")
    private String scene;

    /** 操作人 */
    @ExcelProperty("操作人")
    private String operatorName;

    /** 备注 */
    @ExcelProperty("备注")
    private String remark;
}
