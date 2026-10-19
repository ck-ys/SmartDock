package org.dromara.system.service;

/**
 * 租户开通 SPI: 新建租户时, 各业务模块实现此接口初始化模块专属数据。
 *
 * <p><b>调用时机</b>: {@code SysTenantServiceImpl.saveTenant} 创建租户记录后,
 * Spring 收集所有 {@code TenantProvisioningHandler} Bean 依次调用 {@link #provision}。</p>
 *
 * <p><b>事务语义</b>: 实现方法应标注 {@code @Transactional(rollbackFor = Exception.class)};
 * 抛异常会回滚本实现的数据, 但<b>不会</b>回滚租户创建主事务(各实现独立提交)。
 * 如需"任一模块失败则整体失败", 需在调用方统一编排。</p>
 *
 * <p><b>租户上下文</b>: 调用时已通过 {@code TenantHelper.setDynamic(tenantId)} 设置动态租户,
 * 实现内可直接写库, MyBatis-Plus 租户拦截器自动填充 tenant_id。</p>
 *
 * @author ruoyi
 * @since 5.x
 */
public interface TenantProvisioningHandler {
    // ===== DCS 调用指南 =====
    // DCS 实现: 2 个 Bean 实现此 SPI
    //   - DcsRiskTenantProvisioningHandler: 初始化租户风控规则(敏感词/黑名单/验证码默认配置)
    //   - DcsScoreTenantProvisioningHandler: 初始化租户评分规则(默认维度/权重/标签模板)
    // 扩展点: C1组织/C2定时任务等新模块如需租户初始化数据, 新增实现类即可(自动被 Spring 收集)
    // ⚠️ 注意: provision 内不要假设其他模块已初始化(调用顺序不保证), 模块间数据依赖需另行编排
    // ===== DCS 调用指南 结束 =====

    /**
     * 为新租户初始化模块专属数据。
     *
     * @param tenantId 新租户标识
     */
    void provision(String tenantId);
}
