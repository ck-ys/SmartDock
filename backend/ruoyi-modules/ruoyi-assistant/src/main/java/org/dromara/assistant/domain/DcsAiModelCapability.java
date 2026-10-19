package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 模型能力目录（2026-10-09 批1，Q13-A 双层档位）。
 *
 * <p>档位能力归模型声明：「支持思考」≠「支持调档」。SEED 行=官方预置（tenant 000000，
 * 随 migration 升级）；MANUAL 行=租户覆盖（读取优先，reset 即删）。兜底行 provider='*'
 * 未匹配时按仅开/关最保守处理。schema 参考 ZCode zcode-builtin.json（modelMatch 正则+values 档位表）。</p>
 */
@Data
@TableName("dcs_ai_model_capability")
public class DcsAiModelCapability implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花；SEED 行 migration 固定 id 保证幂等） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号（SEED 行恒 000000；MANUAL 行=覆盖所属租户） */
    private String tenantId;

    /** 厂商标识（大写，与 dcs_ai_config.provider 同口径；兜底行='*'） */
    private String provider;

    /** 模型名正则（全匹配 ^(?:pattern)$，大小写不敏感） */
    private String modelPattern;

    /** 能力类型：TIERS=可调档 / SWITCH=仅开·关 / NONE=不支持思考 */
    private String tierKind;

    /** 原生档位 JSON 数组字符串，如 ["low","high","max"] */
    private String tierValues;

    /** 语义档→原生档 JSON 对象字符串，键 QUICK/BALANCED/DEEP */
    private String semanticMap;

    /** 上下文窗口（token；未核实可空，前端展示"目录带出"） */
    private Long contextWindow;

    /** 单次回答最大输出 tokens（批C C2；未核实可空） */
    private Long maxOutputTokens;

    /** 支持图片输入（批C C2；未核实按 false 保守） */
    private Boolean supportsImage;

    /** 支持视频输入（批C C2） */
    private Boolean supportsVideo;

    /** 支持音频输入（批C C2） */
    private Boolean supportsAudio;

    /** 来源：SEED=官方预置 / MANUAL=租户覆盖 */
    private String source;

    /** 匹配顺序（小者先；specific 规则排前） */
    private Integer ruleOrder;

    /** 创建部门 */
    private Long createDept;

    /** 创建者 */
    private Long createBy;

    /** 创建时间 */
    private Date createTime;

    /** 更新者 */
    private Long updateBy;

    /** 更新时间 */
    private Date updateTime;

    /** 删除标志（0=存在 1=删除） */
    private String delFlag;
}
