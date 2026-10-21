package com.smartdock.profile.mapper;

import com.smartdock.profile.identity.ProfileIdentityLink;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 身份标识链接 Mapper。
 *
 * <p>唯一键 (tenant_id, source, identifier_type, identifier_value) 保证同一标识
 * 只有一个规范主体; 合并时用 {@link #moveSubject} 迁移链接, 不改历史事件。</p>
 *
 * @author smartdock
 */
@Mapper
public interface ProfileIdentityLinkMapper extends BaseMapperPlus<ProfileIdentityLink, ProfileIdentityLink> {

    @Select("""
        SELECT link_id, tenant_id, source, subject_type, subject_id, identifier_type,
               identifier_value, identifier_hash, confidence, is_secure, status,
               first_seen, last_seen
        FROM profile_identity_link
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND identifier_type = #{identifierType} AND identifier_value = #{identifierValue}
        LIMIT 1
        """)
    ProfileIdentityLink selectLink(@Param("tenantId") String tenantId,
                                   @Param("source") String source,
                                   @Param("identifierType") String identifierType,
                                   @Param("identifierValue") String identifierValue);

    @Select("""
        SELECT link_id, tenant_id, source, subject_type, subject_id, identifier_type,
               identifier_value, identifier_hash, confidence, is_secure, status,
               first_seen, last_seen
        FROM profile_identity_link
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId}
        ORDER BY last_seen DESC
        """)
    List<ProfileIdentityLink> selectBySubject(@Param("tenantId") String tenantId,
                                              @Param("source") String source,
                                              @Param("subjectType") String subjectType,
                                              @Param("subjectId") String subjectId);

    @Insert("""
        INSERT INTO profile_identity_link
            (link_id, tenant_id, source, subject_type, subject_id, identifier_type,
             identifier_value, identifier_hash, confidence, is_secure, status,
             first_seen, last_seen, create_time)
        VALUES
            (#{l.linkId}, #{l.tenantId}, #{l.source}, #{l.subjectType}, #{l.subjectId},
             #{l.identifierType}, #{l.identifierValue}, #{l.identifierHash}, #{l.confidence},
             #{l.isSecure}, #{l.status}, #{l.firstSeen}, #{l.lastSeen}, CURRENT_TIMESTAMP)
        ON CONFLICT (tenant_id, source, identifier_type, identifier_value) DO UPDATE SET
            subject_type = EXCLUDED.subject_type,
            subject_id = EXCLUDED.subject_id,
            identifier_hash = COALESCE(EXCLUDED.identifier_hash, profile_identity_link.identifier_hash),
            confidence = EXCLUDED.confidence,
            is_secure = EXCLUDED.is_secure,
            status = 'ACTIVE',
            last_seen = EXCLUDED.last_seen,
            update_time = CURRENT_TIMESTAMP
        """)
    int upsertLink(@Param("l") ProfileIdentityLink link);

    @Update("""
        UPDATE profile_identity_link
        SET subject_id = #{toSubjectId}, update_time = CURRENT_TIMESTAMP
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{fromSubjectId}
        """)
    int moveSubject(@Param("tenantId") String tenantId,
                    @Param("source") String source,
                    @Param("subjectType") String subjectType,
                    @Param("fromSubjectId") String fromSubjectId,
                    @Param("toSubjectId") String toSubjectId);

    /**
     * 主体名下的强标识(user_id/email/...)链接数: 大于 0 即真实用户, 合并前必查, 防止搬走真实用户身份。
     */
    @Select("""
        SELECT count(*)
        FROM profile_identity_link
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_id = #{subjectId} AND status = 'ACTIVE'
          AND identifier_type IN ('user_id','email_sha256','phone_sha256','crm_id','member_id','account_id')
        """)
    int countStrongLinks(@Param("tenantId") String tenantId,
                         @Param("source") String source,
                         @Param("subjectId") String subjectId);

    /**
     * 归并焦点上的事务级互斥(批11 Q4): 与快照链同款 pg advisory 手法。
     * 返回值必须非 void——MyBatis 对 @Select 无法把结果行映射到 void, 会炸
     * "No constructor found in void"并把整个身份解析打挂(E2E 实证)。
     */
    @org.apache.ibatis.annotations.Select("SELECT pg_advisory_xact_lock(hashtext(#{key}))")
    String advisoryXactLock(@org.apache.ibatis.annotations.Param("key") String key);
}
