package com.smartdock.analytics.mapper;

import com.smartdock.analytics.domain.AnalyticsCustomReport;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 自定义分析视图 Mapper(R2-4)。
 *
 * @author smartdock
 */
@Mapper
public interface AnalyticsCustomReportMapper extends BaseMapperPlus<AnalyticsCustomReport, AnalyticsCustomReport> {
}
