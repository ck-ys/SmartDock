package com.smartdock.analytics.mapper;

import com.smartdock.analytics.domain.AnalyticsSite;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 采集站点。
 *
 * @author smartdock
 */
@Mapper
public interface AnalyticsSiteMapper extends BaseMapperPlus<AnalyticsSite, AnalyticsSite> {
}
