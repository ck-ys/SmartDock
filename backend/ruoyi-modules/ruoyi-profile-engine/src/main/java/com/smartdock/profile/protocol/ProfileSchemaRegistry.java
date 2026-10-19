package com.smartdock.profile.protocol;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 事件 schema 注册表 profile_schema_registry。
 *
 * <p>轻校验: schema_json 只要求 type/required/properties 三类核心字段,
 * 不引入完整 JSON Schema/Iglu; 未注册的事件按“放行”处理, 保证旧接入零破坏。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_schema_registry")
public class ProfileSchemaRegistry extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "schema_id", type = IdType.ASSIGN_ID)
    private Long schemaId;

    private String tenantId;

    private String source;

    private String eventName;

    private String schemaVersion;

    /** JSONB: {type:"object", required:[...], properties:{...}} */
    private String schemaJson;

    /** ACTIVE / DISABLED */
    private String status;
}
