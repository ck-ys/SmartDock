package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.assistant.domain.DcsMcpServer;

/** 批2 Q2=A：MCP 服务注册表（试点平台级，@InterceptorIgnore 语义见 ck 唯一键=tenant_id） */
@Mapper
public interface DcsMcpServerMapper extends BaseMapper<DcsMcpServer> {
}
