package com.smartdock.profile.mapper;

import com.smartdock.profile.protocol.ProfileProtocol;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 画像接入协议 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileProtocolMapper extends BaseMapperPlus<ProfileProtocol, ProfileProtocol> {

    @Insert("""
        INSERT INTO profile_protocol
            (protocol_id, tenant_id, source, version, status, manifest, checksum,
             published_at, published_by, create_by, create_time)
        VALUES
            (#{p.protocolId}, #{p.tenantId}, #{p.source}, #{p.version}, #{p.status},
             #{p.manifest}::jsonb, #{p.checksum}, #{p.publishedAt}, #{p.publishedBy},
             #{p.createBy}, CURRENT_TIMESTAMP)
        """)
    int insertJsonb(@Param("p") ProfileProtocol p);

    @Update("""
        UPDATE profile_protocol
        SET status = 'ARCHIVED', update_time = CURRENT_TIMESTAMP
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND status = 'PUBLISHED' AND del_flag = '0'
        """)
    int archivePublished(@Param("tenantId") String tenantId, @Param("source") String source);

    @Select("""
        SELECT protocol_id, tenant_id, source, version, status, manifest::text AS manifest,
               checksum, published_at, published_by, create_by, create_time,
               update_by, update_time
        FROM profile_protocol
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND status = 'PUBLISHED' AND del_flag = '0'
        ORDER BY published_at DESC NULLS LAST, create_time DESC
        LIMIT 1
        """)
    ProfileProtocol selectCurrent(@Param("tenantId") String tenantId, @Param("source") String source);

    @Select("""
        SELECT protocol_id, tenant_id, source, version, status, manifest::text AS manifest,
               checksum, published_at, published_by, create_by, create_time,
               update_by, update_time
        FROM profile_protocol
        WHERE tenant_id = #{tenantId} AND source = #{source} AND del_flag = '0'
        ORDER BY create_time DESC
        LIMIT 50
        """)
    List<ProfileProtocol> selectHistory(@Param("tenantId") String tenantId, @Param("source") String source);

    @Select("""
        SELECT protocol_id, tenant_id, source, version, status, manifest::text AS manifest,
               checksum, published_at, published_by, create_by, create_time,
               update_by, update_time
        FROM profile_protocol
        WHERE tenant_id = #{tenantId} AND source = #{source} AND version = #{version}
          AND del_flag = '0'
        LIMIT 1
        """)
    ProfileProtocol selectVersion(@Param("tenantId") String tenantId, @Param("source") String source,
                                  @Param("version") String version);
}
