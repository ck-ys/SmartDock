package com.smartdock.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import com.smartdock.domain.AiModelPrice;
import com.smartdock.domain.bo.AiModelPriceBo;
import com.smartdock.domain.bo.AiModelPriceQueryBo;
import com.smartdock.domain.vo.AiModelPriceVo;

import java.util.Collection;

/**
 * AI 模型价格配置 Service。
 *
 * @author smartdock
 */
public interface IAiModelPriceService {

    /** 分页查询 */
    TableDataInfo<AiModelPriceVo> queryPageList(AiModelPriceQueryBo bo, PageQuery pageQuery);

    /** 按ID查询 */
    AiModelPriceVo queryById(Long priceId);

    /** 新增 */
    Long insertByBo(AiModelPriceBo bo);

    /** 修改 */
    Boolean updateByBo(AiModelPriceBo bo);

    /** 批量删除 */
    Boolean deleteByIds(Collection<Long> ids);

    /** 取指定租户/厂商/模型当前生效的价格记录。 */
    AiModelPrice findEffectivePrice(String tenantId, String provider, String modelName);

    /**
     * 直接插入实体(绕过 BO 转换, 供 upsert API 使用)。
     */
    void directInsert(AiModelPrice entity);

    /**
     * 直接更新实体(绕过 BO 转换, 供 upsert API 使用)。
     */
    void directUpdate(AiModelPrice entity);

    /**
     * Q6-A（2026-10-23 拍板）：从 models.dev 同步价格。
     * 只覆盖非人工价（manual_override='0'）；返回 inserted/updated/skipped/total。
     */
    java.util.Map<String, Object> syncFromModelsDev(String tenantId);

    /**
     * Q6-A：JSON 幂等导入价格（{\"prices\":[...]}）。
     */
    java.util.Map<String, Object> importJson(String tenantId, java.util.Map<String, Object> payload);
}
