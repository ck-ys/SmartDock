package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileScoreRule;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 权重规则 Mapper。
 *
 * <p>weights 是 jsonb, 写入必须 ::jsonb 显式强转。</p>
 *
 * @author smartdock
 */
@Mapper
public interface ProfileScoreRuleMapper extends BaseMapperPlus<ProfileScoreRule, ProfileScoreRule> {

    @Insert("""
        INSERT INTO profile_score_rule
            (rule_id, tenant_id, source, subject_type, rule_version, rule_name, status, weights, remark,
             published_at, published_by, base_rule_version, revision, enabled, create_by, create_time)
        VALUES
            (#{r.ruleId}, #{r.tenantId}, #{r.source}, #{r.subjectType}, #{r.ruleVersion}, #{r.ruleName},
             #{r.status}, #{r.weights}::jsonb, #{r.remark}, #{r.publishedAt}, #{r.publishedBy},
             #{r.baseRuleVersion}, #{r.revision}, #{r.enabled}, #{r.createBy}, CURRENT_TIMESTAMP)
        """)
    int insertJsonb(@Param("r") ProfileScoreRule r);

    @Update("""
        UPDATE profile_score_rule
        SET rule_version = #{r.ruleVersion}, rule_name = #{r.ruleName}, status = #{r.status},
            weights = #{r.weights}::jsonb, remark = #{r.remark}, published_at = #{r.publishedAt},
            published_by = #{r.publishedBy}, base_rule_version = #{r.baseRuleVersion},
            revision = #{r.revision}, enabled = #{r.enabled}, update_by = #{r.updateBy},
            update_time = CURRENT_TIMESTAMP
        WHERE rule_id = #{r.ruleId} AND tenant_id = #{r.tenantId} AND del_flag = '0'
        """)
    int updateJsonb(@Param("r") ProfileScoreRule r);

    @Update("""
        UPDATE profile_score_rule
        SET status = 'ARCHIVED', enabled = '0', update_time = CURRENT_TIMESTAMP
        WHERE tenant_id = #{tenantId} AND source = #{source} AND subject_type = #{subjectType}
          AND status = 'PUBLISHED' AND del_flag = '0'
        """)
    int archivePublished(@Param("tenantId") String tenantId, @Param("source") String source,
                         @Param("subjectType") String subjectType);
}
