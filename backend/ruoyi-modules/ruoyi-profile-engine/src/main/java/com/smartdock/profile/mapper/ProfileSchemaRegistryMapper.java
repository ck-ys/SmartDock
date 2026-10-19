package com.smartdock.profile.mapper;

import com.smartdock.profile.protocol.ProfileSchemaRegistry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 事件 schema 注册表 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileSchemaRegistryMapper extends BaseMapperPlus<ProfileSchemaRegistry, ProfileSchemaRegistry> {

    @Select("""
        SELECT schema_id, tenant_id, source, event_name, schema_version,
               schema_json::text AS schema_json, status, create_by, create_time,
               update_by, update_time
        FROM profile_schema_registry
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND event_name = #{eventName} AND schema_version = #{schemaVersion}
          AND status = 'ACTIVE' AND del_flag = '0'
        LIMIT 1
        """)
    ProfileSchemaRegistry selectActive(@Param("tenantId") String tenantId,
                                       @Param("source") String source,
                                       @Param("eventName") String eventName,
                                       @Param("schemaVersion") String schemaVersion);

    @Select("""
        SELECT schema_id, tenant_id, source, event_name, schema_version,
               schema_json::text AS schema_json, status, create_by, create_time,
               update_by, update_time
        FROM profile_schema_registry
        WHERE tenant_id = #{tenantId} AND source = #{source} AND del_flag = '0'
        ORDER BY event_name, schema_version DESC
        """)
    List<ProfileSchemaRegistry> selectBySource(@Param("tenantId") String tenantId,
                                               @Param("source") String source);

    @Insert("""
        INSERT INTO profile_schema_registry
            (schema_id, tenant_id, source, event_name, schema_version, schema_json,
             status, create_by, create_time)
        VALUES
            (#{s.schemaId}, #{s.tenantId}, #{s.source}, #{s.eventName}, #{s.schemaVersion},
             #{s.schemaJson}::jsonb, #{s.status}, #{s.createBy}, CURRENT_TIMESTAMP)
        ON CONFLICT (tenant_id, source, event_name, schema_version) DO UPDATE SET
            schema_json = EXCLUDED.schema_json,
            status = 'ACTIVE',
            update_by = EXCLUDED.create_by,
            update_time = CURRENT_TIMESTAMP
        """)
    int insertJsonb(@Param("s") ProfileSchemaRegistry s);
}
