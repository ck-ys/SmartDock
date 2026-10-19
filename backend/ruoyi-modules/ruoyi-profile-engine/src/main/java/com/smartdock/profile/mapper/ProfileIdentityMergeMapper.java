package com.smartdock.profile.mapper;

import com.smartdock.profile.identity.ProfileIdentityMerge;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 身份合并审计 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileIdentityMergeMapper extends BaseMapperPlus<ProfileIdentityMerge, ProfileIdentityMerge> {
}
