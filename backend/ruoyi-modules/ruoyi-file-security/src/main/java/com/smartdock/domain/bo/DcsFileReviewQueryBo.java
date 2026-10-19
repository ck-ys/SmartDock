package com.smartdock.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 文件审核单列表查询入参（12b）
 *
 * <p>status 必传（PENDING/APPROVED/REJECTED）——待审/已审两个页签各查各的；
 * 时间范围走 params.beginTime/params.endTime（照 sys_oss 列表惯例）。</p>
 *
 * @author smartdock
 */
@Data
public class DcsFileReviewQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 审核状态：PENDING 待审 / APPROVED 已通过 / REJECTED 已驳回（页签决定，服务端校验） */
    private String status;

    /** 文件类别：IMAGE/VIDEO/AUDIO/DOCUMENT/TEXT/ARCHIVE/OTHER */
    private String category;

    /** 来源：MISSION_ATTACHMENT/DYNAMIC_FIELD/AVATAR/CHAT/RICH_TEXT/OTHER */
    private String bizSource;

    /** 上传人姓名（模糊） */
    private String uploaderName;

    /** 附加查询参数：beginTime/endTime（上传时间范围） */
    private Map<String, Object> params;
}
