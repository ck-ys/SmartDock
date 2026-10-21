package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.assistant.domain.DcsMcpCallLog;

/** 批2 Q2=A：MCP 挂载/调用审计表 */
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface DcsMcpCallLogMapper extends BaseMapper<DcsMcpCallLog> {
}
