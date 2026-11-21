package com.smartdock.risk.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.risk.domain.SensitiveWord;
import com.smartdock.risk.domain.vo.SensitiveWordVo;

/**
 * 敏感词配置 Mapper, 继承 BaseMapperPlus&lt;SensitiveWord, SensitiveWordVo&gt;。
 *
 * <p>无自定义方法, 敏感词的全部读写均使用 MyBatis-Plus 通用 CRUD。</p>
 *
 * @author smartdock
 */
public interface SensitiveWordMapper extends BaseMapperPlus<SensitiveWord, SensitiveWordVo> {
}
