package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 触达逐人明细（批9 Q3-A 风暴加固）：确认发送时冻结的收件人快照。
 * dedup_key = reachId:userId —— 同批次同用户只发一次（uk_profile_reach_item 兜底），
 * 失败重试只重发 status=FAILED 的行。
 *
 * @author smartdock
 */
@Data
@TableName("profile_reach_item")
public class ProfileReachItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "item_id", type = IdType.ASSIGN_ID)
    private Long itemId;

    private String tenantId;

    private Long reachId;

    private String subjectType;

    private String subjectId;

    private Long userId;

    private String userName;

    private String dedupKey;

    /** PENDING / SUCCESS / FAILED */
    private String status;

    private String errorMsg;

    private Date sentTime;

    private Date createTime;
}
