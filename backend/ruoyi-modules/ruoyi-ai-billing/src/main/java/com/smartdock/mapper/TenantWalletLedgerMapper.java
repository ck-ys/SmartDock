package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.domain.TenantWalletLedger;
import com.smartdock.domain.vo.TenantWalletLedgerVo;

/**
 * 租户钱包流水 Mapper。
 *
 * <p>流水表跟随钱包表, 同属跨租户管理, 关闭租户拦截器(同 {@link TenantWalletMapper})。</p>
 *
 * @author smartdock
 */
@InterceptorIgnore(tenantLine = "true")
public interface TenantWalletLedgerMapper extends BaseMapperPlus<TenantWalletLedger, TenantWalletLedgerVo> {
}
