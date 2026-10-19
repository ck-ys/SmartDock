package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileScoreDimension;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 维度治理 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileScoreDimensionMapper extends BaseMapperPlus<ProfileScoreDimension, ProfileScoreDimension> {
}
