package com.smartdock.dcs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdock.dcs.domain.DcsAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 微核心: 审核决定 Mapper(纯追加, 无 jsonb 列, MP 标准方法全够)。
 *
 * @author smartdock
 */
@Mapper
public interface DcsAuditMapper extends BaseMapper<DcsAudit> {
}
