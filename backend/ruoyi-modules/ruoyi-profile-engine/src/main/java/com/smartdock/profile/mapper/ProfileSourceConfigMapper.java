package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileSourceConfig;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 数据源显示配置 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileSourceConfigMapper extends BaseMapperPlus<ProfileSourceConfig, ProfileSourceConfig> {
}
