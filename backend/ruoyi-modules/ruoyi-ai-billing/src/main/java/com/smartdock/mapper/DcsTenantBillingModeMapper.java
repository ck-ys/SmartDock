package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.domain.DcsTenantBillingMode;

/**
 * 租户 AI 消费方式开关 Mapper（业务批8 Q7 丁）：跨租户管理表，谓词显式 tenantId。
 * 读侧在反应式线程（计费装饰器 creditsMode 判定）也会调用，禁租户拦截。
 */
@InterceptorIgnore(tenantLine = "true")
public interface DcsTenantBillingModeMapper extends BaseMapperPlus<DcsTenantBillingMode, DcsTenantBillingMode> {

    /** 读开关行（无行=null，视同 WALLET） */
    @Select("SELECT * FROM dcs_tenant_billing_mode WHERE tenant_id = #{tenantId}")
    DcsTenantBillingMode selectByTenant(@Param("tenantId") String tenantId);

    /** upsert 开关（订阅成功自动切 CREDIT / 管理员显式切换共用；时间基准=App 时钟入参，
     *  与 deduct 行/ledgerRow 同纪律——JVM/DB 时钟实测有漂移，审计列须与流水可比） */
    @Insert("INSERT INTO dcs_tenant_billing_mode (tenant_id, consume_mode, update_by, update_time)"
        + " VALUES (#{tenantId}, #{mode}, #{updateBy}, #{updateTime})"
        + " ON CONFLICT (tenant_id) DO UPDATE SET consume_mode = EXCLUDED.consume_mode,"
        + " update_by = EXCLUDED.update_by, update_time = EXCLUDED.update_time")
    int upsertMode(@Param("tenantId") String tenantId, @Param("mode") String mode,
                   @Param("updateBy") Long updateBy, @Param("updateTime") java.util.Date updateTime);

    /**
     * dspro P1-2 回修：订阅到期自动回写 WALLET 改<b>条件写</b>——判定下沉到同一语句，
     * 消灭「读到过期 → 并发 subscribe 刚提交 CREDIT → 盲写覆盖回 WALLET 且不再自愈」
     * 的竞态（原盲写在行锁排队后必然覆盖后提交的 CREDIT）。生效订阅存在时 0 行受影响，
     * 两种交错顺序都收敛到正确终态；时间基准用 App 时钟入参与 activeSubscription 同源。
     */
    @Update("UPDATE dcs_tenant_billing_mode SET consume_mode = 'WALLET', update_time = #{now}"
        + " WHERE tenant_id = #{tenantId} AND consume_mode = 'CREDIT'"
        + " AND NOT EXISTS (SELECT 1 FROM dcs_ai_credit_subscription s"
        + " WHERE s.tenant_id = #{tenantId} AND s.status = '1' AND s.end_time > #{now})")
    int revertToWalletIfStillExpired(@Param("tenantId") String tenantId, @Param("now") java.util.Date now);
}
