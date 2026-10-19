package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.domain.DcsAiCreditPlan;

/**
 * AI 积分套餐档位 Mapper（五需求批 R5）。种子管理表、跨租户读取，禁租户拦截。
 */
@InterceptorIgnore(tenantLine = "true")
public interface DcsAiCreditPlanMapper extends BaseMapperPlus<DcsAiCreditPlan, DcsAiCreditPlan> {
}
