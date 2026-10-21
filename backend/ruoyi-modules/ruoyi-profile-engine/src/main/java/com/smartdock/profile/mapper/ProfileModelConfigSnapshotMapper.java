package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileModelConfigSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 模型配置版本存档 Mapper（批9 Q6-A）。config 是 jsonb, 写入必须 ::jsonb。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileModelConfigSnapshotMapper extends BaseMapperPlus<ProfileModelConfigSnapshot, ProfileModelConfigSnapshot> {

    @Insert("""
        INSERT INTO profile_model_config_snapshot
            (config_snapshot_id, tenant_id, source, model_id, model_code, model_version,
             model_type, config, score_max, published_by, published_at, create_time)
        VALUES
            (#{s.configSnapshotId}, #{s.tenantId}, #{s.source}, #{s.modelId}, #{s.modelCode},
             #{s.modelVersion}, #{s.modelType}, #{s.config}::jsonb, #{s.scoreMax}, #{s.publishedBy},
             CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON CONFLICT (tenant_id, model_id, model_version) DO UPDATE
        SET config = EXCLUDED.config, score_max = EXCLUDED.score_max,
            model_type = EXCLUDED.model_type,
            published_by = EXCLUDED.published_by, published_at = CURRENT_TIMESTAMP
        """)
    int upsertJsonb(@Param("s") ProfileModelConfigSnapshot snapshot);

    @Select("""
        SELECT * FROM profile_model_config_snapshot
        WHERE tenant_id = #{tenantId} AND model_id = #{modelId} AND del_flag = '0'
        ORDER BY published_at DESC
        LIMIT #{limit}
        """)
    List<ProfileModelConfigSnapshot> selectByModel(@Param("tenantId") String tenantId,
                                                   @Param("modelId") Long modelId,
                                                   @Param("limit") int limit);

    @Select("""
        SELECT * FROM profile_model_config_snapshot
        WHERE tenant_id = #{tenantId} AND model_id = #{modelId}
          AND model_version = #{version} AND del_flag = '0'
        LIMIT 1
        """)
    ProfileModelConfigSnapshot selectByVersion(@Param("tenantId") String tenantId,
                                               @Param("modelId") Long modelId,
                                               @Param("version") String version);
}
