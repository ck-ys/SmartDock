package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 助手模型设置（批3，2026-10-09，Q5/Q11）：助手默认模型指向 + 允许用户自选开关，每租户一行。
 *
 * <p>Q11 双槽口径：「助手默认」放 AI 助手管理页（本表），「接口默认」星标放 AI 配置页
 * （dcs_ai_config.is_default），两槽独立互不挤占。解析优先级链（AssistantModelFactory）：
 * 用户会话选择 &gt; 助手默认（本表）&gt; 接口默认星标 &gt; 任意可用行。</p>
 *
 * <p>无行=助手默认槽空 → 解析直接走接口默认段，语义完整不缺失。</p>
 */
@Data
@TableName("dcs_assistant_model_setting")
public class DcsAssistantModelSetting implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花） */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 租户编号（唯一，每租户一行） */
    private String tenantId;

    /** 助手默认模型 config_id（指向 dcs_ai_config；保存时校验为本租户可用 CHAT 行） */
    private Long assistantDefaultConfigId;

    /**
     * 业务反馈批1015c Q1(拍板A)：后台 AI 默认模型 config_id——画像诊断/字段生成/评分/
     * 规则推荐等后台链（AiGateway）的默认指向；0 或 null=跟随接口默认（★→最近可用）。
     * 与「助手默认」（聊天链）分槽：后台功能常要稳（不追新），聊天常要聪明，可各配各的。
     */
    private Long backendDefaultConfigId;

    /** 允许用户在聊天里自选模型：1=开（默认 0 关，开成本闸门；开启后选择器显示单价） */
    private String allowUserSwitch;

    /** 可选范围（可选）：JSON 数组 config_id 白名单；空=全部可用 CHAT 行。P2 拍板（2026-10-09 Q1:B）：预留未消费——存储链在（实体/upsert/save 接口），消费端（model-choice/options 过滤）未实现，勿按生效字段排查问题 */
    private String switchScope;

    /**
     * 业务反馈 #14 追加 Q6-A（2026-10-16）：后台 AI 思考档（语义键 FAST/BALANCED/DEEP，
     * 存槽绑定）。空/null=不启用档位覆盖（沿用 dcs_ai_config 行的 reasoning_effort）；
     * 画像设置弹窗按当前槽模型能力带出选项（TIERS=三档单选，SWITCH=关/开 映射
     * FAST/BALANCED，NONE=不支持）。消费端：AiGatewayImpl 经 AiCapabilityPort 翻译成
     * reasoning_effort 注入后台链。
     */
    private String backendThinkingTier;

    /**
     * 业务反馈 #14 追加 Q6-A：后台 AI 单次最大输出 tokens（槽绑定）。null=沿用
     * dcs_ai_config 行的 max_tokens；0=显式不限制；>0=覆盖。默认建议 2048（后台任务
     * 不追长文，防单次烧量）。
     */
    private Integer backendMaxTokens;

    /** 审计列（P2 修复 2026-10-09 Q3：DDL 建表时已存在但实体未映射；补齐后 upsert 落操作人/时间，可追溯「谁改的」） */
    private Long createDept;
    private Long createBy;
    private java.util.Date createTime;
    private Long updateBy;
    private java.util.Date updateTime;
    private String delFlag;
}
