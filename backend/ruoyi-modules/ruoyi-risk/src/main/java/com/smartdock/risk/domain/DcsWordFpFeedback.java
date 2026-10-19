package com.smartdock.risk.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * 机检误报按词反馈 dcs_word_fp_feedback（风控2.0 U03，2026-09-23）
 *
 * <p>用途：大盘 TOP 命中词表的"按词误报率"分子。判误报时对行内容重跑 FORM 场景
 * 词库检查取代表词写入（machine_check 行级标记不含词面，无法直接按词聚合；
 * 代表词=BLOCK 优先首个/无则首个命中，与事件 hit_word 口径对齐防分子>分母，终审P1-2）。</p>
 *
 * <p>口径红线：本表是纠机审的校准账，与 dcs_risk_event 完全隔离——判误报不产生
 * 风险事件、不给用户加风险分（批2 拍板不变）。批4 起（2026-10-18）大盘
 * falseFeedbackCount 统一改计本表全量行数——旧 dcs_mission_detail 行级
 * machine_check 口径已随微核心化退役，单一口径勿混。</p>
 *
 * <p>幂等：一行一判——判误报写入前先删同 detail_id 旧反馈，标记清空重判后覆盖。</p>
 *
 * <p>批4 登记（三审 dsflash）：本表当前无写入方（旧 mission 明细页 FP 端点已随批3 退役），
 * 计数在新基线上为冻结存量——写入方恢复/口径冻结由批5 拍板。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_word_fp_feedback")
public class DcsWordFpFeedback extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 反馈ID（雪花） */
    @TableId(value = "feedback_id")
    private Long feedbackId;

    /** 被判误报的词面（判误报时重跑 FORM 检查的代表词：BLOCK 优先首个/无则首个命中） */
    private String wordText;

    /** 关联明细行（一行一判只写一个代表词；重判先删后插，(detail_id,word_text) 唯一索引兜底并发） */
    private Long detailId;

    /** 关联任务（事件关联与排查用） */
    private Long missionId;

    /** 判误报的审核人（发布人/管理员） */
    private Long reporterId;
}
