package com.smartdock.profile.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.smartdock.profile.domain.ProfilePiiField;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * PII 字段清单 Mapper(批11 Q1)。清单是全租户共享的静态配置, 读走忽略租户插件。
 *
 * @author smartdock
 */
@Mapper
public interface ProfilePiiFieldMapper extends BaseMapperPlus<ProfilePiiField, ProfilePiiField> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT field_key, mask_rule, remark, enabled
        FROM profile_pii_field
        WHERE enabled = '1'
        """)
    List<ProfilePiiField> selectEnabled();

    /** 全量清单(含停用)。表无 tenant_id 列, MP 通用方法会被租户插件注入炸——一律自写。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT field_key, mask_rule, remark, enabled FROM profile_pii_field ORDER BY field_key
        """)
    List<ProfilePiiField> selectAll();

    @InterceptorIgnore(tenantLine = "true")
    @org.apache.ibatis.annotations.Insert("""
        INSERT INTO profile_pii_field (field_key, mask_rule, remark, enabled)
        VALUES (#{fieldKey}, #{maskRule}, #{remark}, #{enabled})
        ON CONFLICT (field_key) DO UPDATE SET
          mask_rule = EXCLUDED.mask_rule, remark = EXCLUDED.remark, enabled = EXCLUDED.enabled
        """)
    int upsert(ProfilePiiField field);

    @InterceptorIgnore(tenantLine = "true")
    @org.apache.ibatis.annotations.Delete("DELETE FROM profile_pii_field WHERE field_key = #{fieldKey}")
    int deleteByKey(@org.apache.ibatis.annotations.Param("fieldKey") String fieldKey);
}
