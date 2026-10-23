package com.smartdock.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Q5-A（2026-10-23）：厂商模板（选厂商 → 地址/格式/模型清单全预填）。
 *
 * @author smartdock
 */
@Data
@AllArgsConstructor
public class DcsAiProviderTemplateVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 模板 ID（同一 provider 可有国内/国际/网关多个变体） */
    private String templateId;

    /** 人话名称 */
    private String label;

    /** 落库 provider 枚举值 */
    private String provider;

    /** 默认接口格式 */
    private String apiFormat;

    /** 默认 Base URL（OpenAI 兼容端点优先，保证对话主链可用） */
    private String baseUrl;

    /** 默认模型 */
    private String defaultModel;

    /** 控制台/申请 Key 地址 */
    private String consoleUrl;

    /** 常用模型清单（前 8 个，供下拉建议） */
    private List<String> models;

    /** 备注（如「国际站」「需账号级 AK/SK」） */
    private String note;
}
