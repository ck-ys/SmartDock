package com.smartdock.analytics.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 会话明细的显示名只读入口（2026-10-30 题10）。
 *
 * <p>只读 sys_user 的 user_id / nick_name / user_name 三列；租户过滤由 MP 租户拦截器追加。
 * 与 dcs 侧 DcsUserLookupMapper 同口径，这里不跨模块依赖，各取所需。
 * 入参用 Collection：调用方去重后是 Set，别为了签名再拷一份。</p>
 *
 * @author smartdock
 */
@Mapper
public interface AnalyticsUserLookupMapper {

    @Select("""
        <script>
        SELECT user_id, nick_name, user_name FROM sys_user
        WHERE del_flag = '0' AND user_id IN
        <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
        </script>
        """)
    List<Map<String, Object>> selectBriefsByIds(@Param("ids") Collection<Long> ids);
}
