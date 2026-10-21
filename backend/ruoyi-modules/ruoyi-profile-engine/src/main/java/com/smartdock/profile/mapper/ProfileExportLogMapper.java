package com.smartdock.profile.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.smartdock.profile.domain.ProfileExportLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 导出审计(批11 Q5)。手写 SQL 自带租户条件(模块铁律)。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileExportLogMapper extends BaseMapperPlus<ProfileExportLog, ProfileExportLog> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT * FROM profile_export_log
        WHERE tenant_id = #{tenantId}
        ORDER BY export_at DESC
        LIMIT 50
        """)
    List<ProfileExportLog> selectRecent(@Param("tenantId") String tenantId);
}
