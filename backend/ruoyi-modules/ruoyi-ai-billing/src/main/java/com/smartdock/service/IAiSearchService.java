package com.smartdock.service;

import com.smartdock.domain.AiSearchService;
import com.smartdock.domain.vo.AiSearchBalanceVo;
import com.smartdock.domain.vo.AiSearchProviderVo;
import com.smartdock.domain.vo.AiSearchServiceVo;

import java.util.List;

/**
 * 联网搜索服务（额度三级管控批 Q16=1）。
 *
 * <p>两套余量口径<b>不许混</b>：账户余额（元，官方接口自动查）与资源包次数
 * （厂商没接口，靠「管理员填总量 + 系统每搜一次减一」估算）各占一列；查不到就返回
 * 「查不到」，绝不换算成同一个单位硬比。</p>
 */
public interface IAiSearchService {

    /** 当前租户的搜索服务行（出参只带密钥掩码；没有就返回空列表——界面显示「还没接入」） */
    List<AiSearchServiceVo> list(String tenantId);

    /** 新增/更新一行（按 tenant_id + provider 幂等；id 空=新增） */
    AiSearchService save(String tenantId, AiSearchService bo, Long operatorId);

    /** 启用/停用 */
    void changeStatus(String tenantId, Long id, String enabled);

    /** 删除（逻辑删；不物理删——流水/审计还能对上） */
    void remove(String tenantId, Long id);

    /**
     * 查一次账户余额（官方 {@code GET /v1/fund/remaining} → {@code data.remaining}，单位元），
     * 结果写回快照列。查不到就把状态标 UNSUPPORTED/ERROR，**不拿旧值冒充新值**。
     */
    AiSearchBalanceVo refreshBalance(String tenantId, Long id);

    /**
     * 资源包估算计数：一次成功搜索 +1（失败/被拦不计）。**只对有 pack_total 的行加**。
     * 调用方是工具层（WebSearchTools），拿不到时静默跳过（不打断已计费的搜索）。
     */
    void countSearch(String tenantId, String provider);

    /** 工具层用的 key 解析（多把轮询；空=没接入，走老的系统参数兜底） */
    String resolveApiKey(String tenantId, String provider);

    /**
     * 搜索调用结果回报（多把钥匙 Q3=1）：成功传 null（清冷却），失败传异常（按错误码冷却）。
     * 调用方是工具层，回报失败不影响本次搜索的结果返回。
     */
    void reportSearchResult(String tenantId, String provider, Throwable error);

    /** 搜索厂商目录（将来加别家只多一条） */
    List<AiSearchProviderVo> providers();
}
