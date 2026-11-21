package com.smartdock.mission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdock.mission.domain.Audit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 微核心: 审核决定 Mapper(纯追加, 无 jsonb 列, MP 标准方法全够)。
 *
 * @author smartdock
 */
@Mapper
public interface AuditMapper extends BaseMapper<Audit> {
}
