package org.dromara.common.tenant.core;

import org.dromara.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户基类
 *
 * @author Michelle.Chung
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TenantEntity extends BaseEntity {

    // ===== DCS 调用指南 =====
    // DCS 引用频次: 38+ 个文件
    // 典型用法: DCS 所有实体(DcsMission/DcsProfile/DcsNotice 等)继承它, tenantId 字段由 MyBatis 拦截器自动填充, 实体不需要手动 set
    // 安全边界: tenantId 是多租户数据隔离核心字段, 严禁手动赋值, 由拦截器统一注入, 否则破坏租户隔离
    // 扩展点: 新增 DCS 业务实体继承 TenantEntity 即可自动获得多租户能力
    // ===== DCS 调用指南 结束 =====

    /**
     * 租户编号
     */
    private String tenantId;

}
