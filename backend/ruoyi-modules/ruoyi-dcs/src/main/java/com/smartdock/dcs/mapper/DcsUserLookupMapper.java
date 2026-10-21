package com.smartdock.dcs.mapper;

import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 微核心的用户简档只读入口(sys_user 直读, 三审 glm53 P2-11 / 5.3fh P1-3)。
 *
 * <p>用途有二: ①create/publish 时校验目标成员存在且同租户(防跨租户指派静默失效);
 * ②dcs_create_task 确认卡展示成员姓名(防模型编造 userId 后用户无从核对)。
 * 只读 user_id/nick_name/user_name 三列; 租户过滤由 MP 租户拦截器自动追加。</p>
 *
 * @author smartdock
 */
public interface DcsUserLookupMapper {

    /** 按 id 批量取简档; 返回行: user_id, nick_name, user_name */
    @Select("""
        <script>
        SELECT user_id, nick_name, user_name FROM sys_user
        WHERE del_flag = '0' AND user_id IN
        <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
        </script>
        """)
    List<Map<String, Object>> selectBriefsByIds(@org.apache.ibatis.annotations.Param("ids") List<Long> ids);

    /** 在职管理员用户 id(superadmin/tenant_admin)(批11 Q5: 导出审计站内通知收件人)。租户条件由插件追加。 */
    @Select("""
        SELECT DISTINCT u.user_id FROM sys_user u
        JOIN sys_user_role ur ON ur.user_id = u.user_id
        JOIN sys_role r ON r.role_id = ur.role_id
        WHERE u.del_flag = '0' AND u.status = '0' AND r.status = '0' AND r.role_key IN ('superadmin', 'tenant_admin')
        """)
    List<Long> selectAdminUserIds();
}
