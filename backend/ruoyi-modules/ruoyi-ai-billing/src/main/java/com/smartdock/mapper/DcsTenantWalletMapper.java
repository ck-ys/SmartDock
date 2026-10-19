package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.domain.DcsTenantWallet;
import com.smartdock.domain.vo.DcsTenantWalletVo;

import java.math.BigDecimal;

/**
 * 租户钱包 Mapper。
 *
 * <p>钱包是跨租户管理表(超管为每个租户初始化/充值/扣减), 所有查询已显式带 {@code tenantId} 条件,
 * 关闭租户拦截器避免 INSERT 保留 entity 租户而 UPDATE 被拦截器覆盖为当前登录租户的不对称行为。</p>
 *
 * @author smartdock
 */
@InterceptorIgnore(tenantLine = "true")
public interface DcsTenantWalletMapper extends BaseMapperPlus<DcsTenantWallet, DcsTenantWalletVo> {

    /**
     * 条件扣减(批D D1-③, P16 配方): 单条原子 UPDATE, 余额足额才扣、防并发透支——
     * affected=0 即余额不足(或并发下被抢先扣空), 不再依赖先读后写的乐观锁重试。
     * 相对减(balance_amount = balance_amount - amount)天然串行于行锁, 链式流水由
     * 调用方以扣减后余额反推期初(见 TenantWalletService#doDeductConditional)。
     * status='ACTIVE'(glm53 终审 P2): 冻结/停用钱包不得扣(与 legacy doDeduct 的
     * loadAndCheck 校验对齐; affected=0 时由服务层回读区分话术)。
     */
    @Update("UPDATE dcs_tenant_wallet SET balance_amount = balance_amount - #{amount}, version = version + 1 "
        + "WHERE wallet_id = #{walletId} AND del_flag = '0' AND status = 'ACTIVE' "
        + "AND (negative_allowed = '1' OR balance_amount - COALESCE(frozen_amount, 0) >= #{amount})")
    int conditionalDeduct(@Param("walletId") Long walletId, @Param("amount") BigDecimal amount);
}
