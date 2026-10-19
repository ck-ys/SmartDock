package com.smartdock.profile.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 身份标识 → 规范主体 profile_identity_link。
 *
 * <p>标识符分强/弱: user_id/email_sha256/phone_sha256 等强 ID 冲突时绝不自动合并;
 * anonymous_id 可做登录合并; device_id 只关联不合并(共享设备保护)。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_identity_link")
public class ProfileIdentityLink extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "link_id", type = IdType.ASSIGN_ID)
    private Long linkId;

    private String tenantId;

    private String source;

    private String subjectType;

    /** 规范主体 ID */
    private String subjectId;

    /** user_id / anonymous_id / device_id / email_sha256 / phone_sha256 ... */
    private String identifierType;

    /** 归一化后的标识值(超长值落 SHA-256) */
    private String identifierValue;

    /** 可选的原始值哈希留痕 */
    private String identifierHash;

    private BigDecimal confidence;

    /** 1=可用于公开查找(强 ID); 0=只做关联线索 */
    private String isSecure;

    /** ACTIVE / REVOKED */
    private String status;

    private Date firstSeen;

    private Date lastSeen;
}
