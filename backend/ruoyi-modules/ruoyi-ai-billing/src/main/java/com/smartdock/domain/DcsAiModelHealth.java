package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 模型巡检健康行（AI 缺口批 Q9，2026-10-14 拍板全A）。
 *
 * <p>每日 04:00 Job 探活「在用模型」（租户助手默认 + 换模白名单 + 最近 7 天用过），
 * 每个 (tenant_id, config_id) 一行滚动更新。provider/model_name 冗余存显示名——配置行
 * 删除后历史健康行仍可读（badge 端点按 configId 对齐，读不到即自然不展示）。</p>
 *
 * <p>非 TenantEntity：调度上下文无登录租户，租户隔离由 mapper 级
 * {@code @InterceptorIgnore} + 显式 tenant_id 谓词自管（WalletAlertRecipientMapper 同配方）。</p>
 *
 * @author smartdock
 */
@Data
@TableName("dcs_ai_model_health")
public class DcsAiModelHealth implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 雪花 id */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务租户 */
    private String tenantId;

    /** AI 配置行 id（dcs_ai_config.config_id） */
    private Long configId;

    /** 厂商（冗余显示用） */
    private String provider;

    /** 模型名（冗余显示用） */
    private String modelName;

    /** 探活结果：OK / FAIL */
    private String status;

    /** 探活耗时（毫秒，OK 时有值） */
    private Integer latencyMs;

    /** 失败原因摘要（截 500 字）或成功标记 */
    private String detail;

    /** 连续 FAIL 天数（OK 归零；≥3 触发站内信） */
    private Integer failStreak;

    /** 最近一次探活时间 */
    private Date checkTime;

    /** 首次建档时间 */
    private Date createTime;
}
