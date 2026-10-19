package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 文件审核单 dcs_file_review（12b 文件审核中心 P1）
 *
 * <p>一 oss 一单（UNIQUE(oss_id) 兜底防重试双单）；驳回后重传=新 ossId 新单，旧单留痕；
 * 状态机 PENDING → APPROVED / REJECTED，均为终态，不做"重新打开旧单"。</p>
 *
 * <p>红线：biz_source / mission_id 仅作展示与任务关联（Q2-A 任务审核人放行判定），
 * 绝不作为免审依据——两者来自前端参数，可伪造（K3 盲审发现）。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_file_review")
public class DcsFileReview extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 审核单ID（雪花） */
    @TableId(value = "review_id")
    private Long reviewId;

    /** 关联 sys_oss.oss_id（一 oss 一单） */
    private Long ossId;

    /** 冗余原文件名（列表展示免 join sys_oss） */
    private String fileName;

    /** 文件类别：IMAGE/VIDEO/AUDIO/DOCUMENT/TEXT/ARCHIVE/OTHER（见 FileReviewCategory 映射表） */
    private String fileCategory;

    /** 文件字节数（冗余展示用） */
    private Long fileSize;

    /** 上传来源埋点：MISSION_ATTACHMENT/DYNAMIC_FIELD/AVATAR/CHAT/RICH_TEXT/OTHER（仅展示，不作为免审依据） */
    private String bizSource;

    /** 关联任务ID（Q2-A：任务/科目审核人放行判定用；聊天/头像等为空） */
    private Long missionId;

    /** 上传人 userId */
    private Long uploaderId;

    /** 上传人姓名（冗余） */
    private String uploaderName;

    /** 状态：PENDING 待审 / APPROVED 已通过 / REJECTED 已驳回（终态，CHECK 约束兜底） */
    private String status;

    /** 审核人 userId（通过/驳回者） */
    private Long reviewerId;

    /** 审核人姓名（冗余） */
    private String reviewerName;

    /** 审核时间 */
    private Date reviewTime;

    /** 驳回理由（驳回必填，随通知发给上传人；'源文件已删除'=删 oss 级联闭单） */
    private String rejectReason;

    /** file-guard 风险标签（批二）：深扫命中说明（宏/PDF JS/SVG 脚本），审核界面红 tag 展示；非 guard 建单为空 */
    private String guardReason;
}
