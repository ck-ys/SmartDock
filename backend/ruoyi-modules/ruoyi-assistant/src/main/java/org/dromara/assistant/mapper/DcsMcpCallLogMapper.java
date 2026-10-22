package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.assistant.domain.DcsMcpCallLog;

/** 批2 Q2=A：MCP 挂载/调用审计表 */
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface DcsMcpCallLogMapper extends BaseMapper<DcsMcpCallLog> {

    /** G4-#5：保留期分批清理（单批 5000，配合 do-while 清干净）——跨租户全量，
     *  类级 @InterceptorIgnore 已挡租户注入，SQL 不带租户条件即全表口径 */
    @org.apache.ibatis.annotations.Delete(
        "DELETE FROM dcs_mcp_call_log WHERE id IN "
            + "(SELECT id FROM dcs_mcp_call_log WHERE create_time < #{cutoff} LIMIT 5000)")
    int deleteBatchBefore(@org.apache.ibatis.annotations.Param("cutoff") java.util.Date cutoff);
}
