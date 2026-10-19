package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.domain.DcsTenantWalletLedger;
import com.smartdock.domain.vo.DcsTenantWalletLedgerVo;

/**
 * 租户钱包流水 Mapper。
 *
 * <p>流水表跟随钱包表, 同属跨租户管理, 关闭租户拦截器(同 {@link DcsTenantWalletMapper})。</p>
 *
 * @author smartdock
 */
@InterceptorIgnore(tenantLine = "true")
public interface DcsTenantWalletLedgerMapper extends BaseMapperPlus<DcsTenantWalletLedger, DcsTenantWalletLedgerVo> {
}
