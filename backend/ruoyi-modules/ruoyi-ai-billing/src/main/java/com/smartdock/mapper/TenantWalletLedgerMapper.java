package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.domain.TenantWalletLedger;
import com.smartdock.domain.vo.TenantWalletLedgerVo;
import com.smartdock.domain.vo.WalletLedgerSummaryVo;

import java.util.Date;

/**
 * 租户钱包流水 Mapper。
 *
 * <p>流水表跟随钱包表, 同属跨租户管理, 关闭租户拦截器(同 {@link TenantWalletMapper})。</p>
 *
 * @author smartdock
 */
@InterceptorIgnore(tenantLine = "true")
public interface TenantWalletLedgerMapper extends BaseMapperPlus<TenantWalletLedger, TenantWalletLedgerVo> {

    /**
     * 按筛选条件合计收入/支出/净额/笔数（2026-11-25 钱包流水批 Q5）。
     *
     * <p>旧口径是「当前页合计」——翻页数字就变，用户以为同一个数在跳。这里与列表同条件全量聚合。</p>
     *
     * <p>租户拦截器对本表关闭，所以 tenant_id 条件必须由调用方显式传入（不传=全平台，仅超管口径）。
     * 时间区间用半开 [begin, endExcl)，见 {@code com.smartdock.common.text.DayRange}。</p>
     */
    @Select("<script>"
        + "SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN amount ELSE 0 END), 0) AS total_in,"
        + " COALESCE(SUM(CASE WHEN direction = 'OUT' THEN amount ELSE 0 END), 0) AS total_out,"
        + " COALESCE(SUM(CASE WHEN direction = 'IN' THEN amount ELSE -amount END), 0) AS net,"
        + " COUNT(*) AS count"
        + " FROM tenant_wallet_ledger WHERE del_flag = '0'"
        + "<if test='tenantId != null'> AND tenant_id = #{tenantId}</if>"
        + "<if test='walletId != null'> AND wallet_id = #{walletId}</if>"
        + "<if test='serviceCode != null'> AND service_code = #{serviceCode}</if>"
        + "<if test='direction != null'> AND direction = #{direction}</if>"
        + "<if test='bizType != null'> AND biz_type = #{bizType}</if>"
        + "<if test='begin != null'> AND create_time &gt;= #{begin}</if>"
        + "<if test='endExcl != null'> AND create_time &lt; #{endExcl}</if>"
        + "</script>")
    WalletLedgerSummaryVo sumByFilter(@Param("tenantId") String tenantId,
                                      @Param("walletId") Long walletId,
                                      @Param("serviceCode") String serviceCode,
                                      @Param("direction") String direction,
                                      @Param("bizType") String bizType,
                                      @Param("begin") Date begin,
                                      @Param("endExcl") Date endExcl);
}
