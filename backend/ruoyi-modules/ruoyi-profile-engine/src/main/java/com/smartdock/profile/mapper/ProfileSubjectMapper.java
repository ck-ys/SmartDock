package com.smartdock.profile.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.profile.event.ProfileSubject;
import org.apache.ibatis.annotations.Mapper;

/**
 * 画像主体 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileSubjectMapper extends BaseMapperPlus<ProfileSubject, ProfileSubject> {
}
