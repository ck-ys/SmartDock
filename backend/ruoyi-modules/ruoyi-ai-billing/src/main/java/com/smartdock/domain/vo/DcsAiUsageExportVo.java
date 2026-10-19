package com.smartdock.domain.vo;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 用量明细导出 VO（AI 缺口批 Q7，2026-10-14）：全字段字符串化——Excel 里看到什么
 * 由后端统一格式化，与页面口径一致（时间/Token/金额不再出现原生类型裸奔）。
 *
 * @author smartdock
 */
@Data
public class DcsAiUsageExportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 时间 */
    @ExcelProperty("时间")
    private String createTime;

    /** 调用方（中文） */
    @ExcelProperty("调用方")
    private String caller;

    /** 业务场景（原文，内部串） */
    @ExcelProperty("场景")
    private String scene;

    /** 厂商 */
    @ExcelProperty("厂商")
    private String provider;

    /** 模型 */
    @ExcelProperty("模型")
    private String modelName;

    /** 计费模式（中文） */
    @ExcelProperty("计费模式")
    private String billingMode;

    /** 输入 Token */
    @ExcelProperty("输入Token")
    private String promptTokens;

    /** 缓存 Token */
    @ExcelProperty("缓存Token")
    private String cachedPromptTokens;

    /** 输出 Token */
    @ExcelProperty("输出Token")
    private String completionTokens;

    /** 总 Token */
    @ExcelProperty("总Token")
    private String totalTokens;

    /** 耗时(ms) */
    @ExcelProperty("耗时(ms)")
    private String latencyMs;

    /** 基础成本 */
    @ExcelProperty("基础成本")
    private String baseCostAmount;

    /** 服务费 */
    @ExcelProperty("服务费")
    private String serviceFeeAmount;

    /** 最终消耗 */
    @ExcelProperty("最终消耗")
    private String costAmount;

    /** 币种 */
    @ExcelProperty("币种")
    private String currency;

    /** 状态（成功/失败） */
    @ExcelProperty("状态")
    private String success;

    /** 错误信息 */
    @ExcelProperty("错误信息")
    private String errorMsg;
}
