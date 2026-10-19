package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileEngineConfig;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 画像引擎配置 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileEngineConfigMapper extends BaseMapperPlus<ProfileEngineConfig, ProfileEngineConfig> {
}
