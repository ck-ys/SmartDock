package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.assistant.domain.DcsMcpServer;

/** 批2 Q2=A：MCP 服务注册表——平台级共享行，读写全租户口径（SQL 自带 tenant_id='000000'） */
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface DcsMcpServerMapper extends BaseMapper<DcsMcpServer> {
}
