package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileReviewQueue;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 低分复核队列 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileReviewQueueMapper extends BaseMapperPlus<ProfileReviewQueue, ProfileReviewQueue> {
}
