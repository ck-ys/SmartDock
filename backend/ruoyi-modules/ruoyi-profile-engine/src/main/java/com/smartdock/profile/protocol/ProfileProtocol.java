package com.smartdock.profile.protocol;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 画像接入协议版本 profile_protocol。
 *
 * <p>manifest 是画像接入的唯一声明式契约: subjectTypes/events/dimensions/metrics/
 * rules/scoreModels/segments/identity。发布后不可变, 同 source 只保留一条 PUBLISHED。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_protocol")
public class ProfileProtocol extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "protocol_id", type = IdType.ASSIGN_ID)
    private Long protocolId;

    private String tenantId;

    private String source;

    private String version;

    /** DRAFT / PUBLISHED / ARCHIVED */
    private String status;

    /** 完整 manifest(JSONB 列, Java 侧按字符串承载) */
    private String manifest;

    private String checksum;

    private Date publishedAt;

    private Long publishedBy;
}
