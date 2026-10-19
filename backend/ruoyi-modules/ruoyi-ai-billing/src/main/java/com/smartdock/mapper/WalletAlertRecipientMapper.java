package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 钱包告警收件人解析 Mapper(批2 2026-09-25, 拍板 Q7-⑨)。
 *
 * <p>背景: 原 WalletAlertService.findTenantAdminUserIds 按"userId≤2 即管理员"的简化约定圈人,
 * 无 userId≤2 用户的租户(如 900001)低余额告警静默无人接收。本 Mapper 按 sys_user_role 关联
 * 真角色解析, 口径与 dcs 侧 DcsChangelogMapper.selectAllTenantAdmins 一致(role_key='admin',
 * 角色启用、在职未删)。</p>
 *
 * <p>类级 {@code @InterceptorIgnore(tenantLine="true")}: 调度线程无租户上下文, 显式租户谓词
 * 自带隔离, 拦截器放行跨 sys_user/sys_role 扫描。</p>
 *
 * @author smartdock
 */
@InterceptorIgnore(tenantLine = "true")
public interface WalletAlertRecipientMapper {

    /**
     * 解析指定租户的管理员用户ID(role_key='admin', 角色启用、在职未删)。
     */
    @Select("""
        SELECT DISTINCT u.user_id
          FROM sys_user u
          JOIN sys_user_role ur ON ur.user_id = u.user_id
          JOIN sys_role r ON r.role_id = ur.role_id
         WHERE u.tenant_id = #{tenantId}
           AND r.role_key = 'admin'
           AND r.del_flag = '0'
           AND r.status = '0'
           AND u.del_flag = '0'
           AND u.status = '0'
        """)
    List<Long> selectTenantAdminUserIds(@Param("tenantId") String tenantId);
}
