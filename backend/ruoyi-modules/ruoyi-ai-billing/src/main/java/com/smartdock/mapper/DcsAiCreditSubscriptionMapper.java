package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.domain.DcsAiCreditSubscription;

/**
 * AI 积分订阅 Mapper（五需求批 R5）。计费链在反应式线程落账无登录租户上下文，
 * 谓词显式 tenant_id（钱包 Mapper 同款口径）。
 */
@InterceptorIgnore(tenantLine = "true")
public interface DcsAiCreditSubscriptionMapper extends BaseMapperPlus<DcsAiCreditSubscription, DcsAiCreditSubscription> {
}
