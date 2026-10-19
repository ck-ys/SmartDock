package com.smartdock.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.smartdock.domain.DcsFileReview;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 文件审核单 视图对象（12b）
 *
 * @author smartdock
 */
@Data
@AutoMapper(target = DcsFileReview.class)
public class DcsFileReviewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long reviewId;

    private Long ossId;

    /** 原文件名 */
    private String fileName;

    /** 类别码 IMAGE/VIDEO/AUDIO/DOCUMENT/TEXT/ARCHIVE/OTHER */
    private String fileCategory;

    private Long fileSize;

    /** 来源码 MISSION_ATTACHMENT/DYNAMIC_FIELD/AVATAR/CHAT/RICH_TEXT/OTHER */
    private String bizSource;

    private Long missionId;

    private Long uploaderId;

    private String uploaderName;

    /** PENDING/APPROVED/REJECTED */
    private String status;

    private Long reviewerId;

    private String reviewerName;

    private Date reviewTime;

    private String rejectReason;

    /** file-guard 风险标签（批二/三）：深扫命中说明（宏/PDF JS/SVG 脚本），列表红 tag 展示；空=常规审核单 */
    private String guardReason;

    private Date createTime;
}
