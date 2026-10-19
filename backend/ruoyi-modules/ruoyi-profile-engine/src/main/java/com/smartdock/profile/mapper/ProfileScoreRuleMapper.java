package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileScoreRule;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 权重规则 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileScoreRuleMapper extends BaseMapperPlus<ProfileScoreRule, ProfileScoreRule> {
}
