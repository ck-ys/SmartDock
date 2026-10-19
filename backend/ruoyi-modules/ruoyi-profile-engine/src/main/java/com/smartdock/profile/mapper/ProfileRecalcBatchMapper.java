package com.smartdock.profile.mapper;

import com.smartdock.profile.engine.ProfileRecalcBatch;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 重算批次 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileRecalcBatchMapper extends BaseMapperPlus<ProfileRecalcBatch, ProfileRecalcBatch> {
}
