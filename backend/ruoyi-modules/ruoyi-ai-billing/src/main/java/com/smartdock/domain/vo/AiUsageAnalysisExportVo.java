package com.smartdock.domain.vo;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 用量分析报表导出 VO（AI 缺口批 Q5，2026-10-14）：按日×模型聚合行。
 *
 * @author smartdock
 */
@Data
public class AiUsageAnalysisExportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 日期 */
    @ExcelProperty("日期")
    private String day;

    /** 厂商 */
    @ExcelProperty("厂商")
    private String provider;

    /** 模型 */
    @ExcelProperty("模型")
    private String modelName;

    /** 调用次数 */
    @ExcelProperty("调用次数")
    private String calls;

    /** 成功次数 */
    @ExcelProperty("成功次数")
    private String successCalls;

    /** 平均耗时(ms) */
    @ExcelProperty("平均耗时(ms)")
    private String avgLatencyMs;

    /** 消耗(元) */
    @ExcelProperty("消耗(元)")
    private String cost;
}
