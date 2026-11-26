package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.domain.AiSearchService;

import java.util.List;

/**
 * 联网搜索服务 Mapper（额度三级管控批 Q16=1；多把钥匙 2026-11-26 拍板 Q2/Q4）。
 *
 * <p>类级 {@code @InterceptorIgnore(tenantLine="true")}：与 ai_credit_ledger 同口径——
 * 搜索调用跑在模型工具线程里，登录租户上下文不一定在，全部谓词显式带 tenantId。</p>
 */
@InterceptorIgnore(tenantLine = "true")
public interface AiSearchServiceMapper extends BaseMapperPlus<AiSearchService, AiSearchService> {

    /**
     * 资源包估算计数（Q16=1；多把钥匙 Q4=1 改为按 id 只减「这次实际用的那把」）。
     *
     * <p><b>为什么必须带 id</b>：多把钥匙时若仍按 tenant+provider 更新，一次搜索会把该厂商
     * 所有行都 +1——实际搜 1 次、账上减 N 次，数字翻倍。带上 id 才是「用的是哪把就扣哪把」。</p>
     *
     * <p><b>为什么是原子 UPDATE 而不是「读-加-写」</b>：并发搜索会互相覆盖（丢计数），
     * 而这里丢的就是「用户还剩几次」这种要给人看的数。用 {@code pack_used + 1} 单语句由 DB 自增。</p>
     *
     * <p>只对 {@code pack_total IS NOT NULL} 的行加——没买包的企业不该攒一个没意义的计数。</p>
     */
    @Insert("UPDATE ai_search_service SET pack_used = pack_used + 1"
        + " WHERE id = #{id} AND del_flag = '0' AND pack_total IS NOT NULL")
    int incPackUsedById(@Param("id") Long id);

    /**
     * 当前租户的某厂商全部未删除行，按 id 升序（主钥匙 = 第一行）。
     *
     * <p>id 升序是轮询与「主钥匙」口径的唯一依据，与 {@code ProviderKeyPool.enabledKeys}
     * 完全一致——两套钥匙行为一致，管理员学一次就懂两边。</p>
     */
    @Select("SELECT * FROM ai_search_service WHERE tenant_id = #{tenantId}"
        + " AND provider = #{provider} AND del_flag = '0' ORDER BY id ASC")
    List<AiSearchService> selectListByTenantProvider(@Param("tenantId") String tenantId,
                                                     @Param("provider") String provider);

    /**
     * 单行取回（余额刷新、启停、删除用）。多把之后不能再靠 tenant+provider 定位。
     */
    @Select("SELECT * FROM ai_search_service WHERE id = #{id} AND del_flag = '0'")
    AiSearchService selectActiveById(@Param("id") Long id);
}
