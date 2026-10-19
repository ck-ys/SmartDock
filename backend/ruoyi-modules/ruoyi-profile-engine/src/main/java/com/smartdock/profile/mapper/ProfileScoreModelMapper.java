package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileScoreModel;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 评分模型 Mapper。config 是 jsonb, 写入必须 ::jsonb。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileScoreModelMapper extends BaseMapperPlus<ProfileScoreModel, ProfileScoreModel> {

    @Insert("""
        INSERT INTO profile_score_model
            (model_id, tenant_id, source, model_code, model_name, model_type, model_version,
             config, score_max, enabled, builtin, remark, revision, create_by, create_time)
        VALUES
            (#{m.modelId}, #{m.tenantId}, #{m.source}, #{m.modelCode}, #{m.modelName}, #{m.modelType},
             #{m.modelVersion}, #{m.config}::jsonb, #{m.scoreMax}, #{m.enabled}, #{m.builtin},
             #{m.remark}, #{m.revision}, #{m.createBy}, CURRENT_TIMESTAMP)
        """)
    int insertJsonb(@Param("m") ProfileScoreModel m);

    @Update("""
        UPDATE profile_score_model
        SET model_name = #{m.modelName}, model_type = #{m.modelType}, model_version = #{m.modelVersion},
            config = #{m.config}::jsonb, score_max = #{m.scoreMax}, enabled = #{m.enabled},
            remark = #{m.remark}, revision = #{m.revision}, update_by = #{m.updateBy},
            update_time = CURRENT_TIMESTAMP
        WHERE model_id = #{m.modelId} AND tenant_id = #{m.tenantId} AND del_flag = '0'
        """)
    int updateJsonb(@Param("m") ProfileScoreModel m);
}
