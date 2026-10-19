package com.smartdock.risk.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.risk.domain.DcsSensitiveWord;
import com.smartdock.risk.domain.vo.DcsSensitiveWordVo;

/**
 * 敏感词配置 Mapper, 继承 BaseMapperPlus&lt;DcsSensitiveWord, DcsSensitiveWordVo&gt;。
 *
 * <p>无自定义方法, 敏感词的全部读写均使用 MyBatis-Plus 通用 CRUD。</p>
 *
 * @author smartdock
 */
public interface DcsSensitiveWordMapper extends BaseMapperPlus<DcsSensitiveWord, DcsSensitiveWordVo> {
}
