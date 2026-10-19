package com.smartdock.risk.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.risk.domain.DcsRiskAction;
import com.smartdock.risk.domain.vo.RiskActionVo;

/**
 * 风控处置记录 Mapper, 继承 BaseMapperPlus&lt;DcsRiskAction, RiskActionVo&gt;。
 *
 * <p>目前无自定义方法, 处置记录的全部读写(含分页查询、乐观锁条件更新)均使用 MyBatis-Plus 通用 CRUD。</p>
 *
 * @author smartdock
 */
public interface DcsRiskActionMapper extends BaseMapperPlus<DcsRiskAction, RiskActionVo> {
}
