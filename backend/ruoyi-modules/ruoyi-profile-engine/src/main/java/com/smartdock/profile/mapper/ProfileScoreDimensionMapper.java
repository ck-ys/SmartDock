package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileScoreDimension;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 维度治理 Mapper。
 *
 * <p>calc_config 是 jsonb, 写入必须 ::jsonb 显式强转。</p>
 *
 * @author smartdock
 */
@Mapper
public interface ProfileScoreDimensionMapper extends BaseMapperPlus<ProfileScoreDimension, ProfileScoreDimension> {

    @Insert("""
        INSERT INTO profile_score_dimension
            (dimension_id, tenant_id, dim_code, dim_name, source, calc_mode, calc_config,
             score_max, enabled, builtin, sort_order, remark, create_by, create_time)
        VALUES
            (#{d.dimensionId}, #{d.tenantId}, #{d.dimCode}, #{d.dimName}, #{d.source}, #{d.calcMode},
             #{d.calcConfig}::jsonb, #{d.scoreMax}, #{d.enabled}, #{d.builtin}, #{d.sortOrder},
             #{d.remark}, #{d.createBy}, CURRENT_TIMESTAMP)
        """)
    int insertJsonb(@Param("d") ProfileScoreDimension d);

    @Update("""
        UPDATE profile_score_dimension
        SET dim_name = #{d.dimName}, calc_mode = #{d.calcMode}, calc_config = #{d.calcConfig}::jsonb,
            score_max = #{d.scoreMax}, enabled = #{d.enabled}, sort_order = #{d.sortOrder},
            remark = #{d.remark}, update_by = #{d.updateBy}, update_time = CURRENT_TIMESTAMP
        WHERE dimension_id = #{d.dimensionId} AND tenant_id = #{d.tenantId} AND del_flag = '0'
        """)
    int updateJsonb(@Param("d") ProfileScoreDimension d);

    @Update("""
        UPDATE profile_score_dimension
        SET enabled = #{enabled}, update_time = CURRENT_TIMESTAMP
        WHERE dimension_id = #{id} AND tenant_id = #{tenantId} AND del_flag = '0'
        """)
    int updateEnabled(@Param("id") Long id, @Param("tenantId") String tenantId,
                      @Param("enabled") String enabled);
}
