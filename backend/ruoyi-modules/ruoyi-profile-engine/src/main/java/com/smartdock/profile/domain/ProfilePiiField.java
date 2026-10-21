package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * PII 字段清单(批11 Q1-A): 哪些键名是敏感字段、用什么规则打码。
 * 键名匹配发生在一切出参 map(主体 properties / 事件 props·context / 会话行)上, 忽略大小写与 -_ 差异。
 *
 * @author smartdock
 */
@Data
@TableName("profile_pii_field")
public class ProfilePiiField implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 键名(如 phone / email / search_term) */
    @TableId(type = IdType.ASSIGN_ID)
    private String fieldKey;

    /** 打码规则: MOBILE/EMAIL/IP/TERM/EDGE/FULL */
    private String maskRule;

    /** 说明 */
    private String remark;

    /** 1=启用 0=停用 */
    private String enabled;
}
