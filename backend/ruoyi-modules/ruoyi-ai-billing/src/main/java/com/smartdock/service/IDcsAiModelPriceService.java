package com.smartdock.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import com.smartdock.domain.DcsAiModelPrice;
import com.smartdock.domain.bo.DcsAiModelPriceBo;
import com.smartdock.domain.bo.DcsAiModelPriceQueryBo;
import com.smartdock.domain.vo.DcsAiModelPriceVo;

import java.util.Collection;

/**
 * AI 模型价格配置 Service。
 *
 * @author smartdock
 */
public interface IDcsAiModelPriceService {

    /** 分页查询 */
    TableDataInfo<DcsAiModelPriceVo> queryPageList(DcsAiModelPriceQueryBo bo, PageQuery pageQuery);

    /** 按ID查询 */
    DcsAiModelPriceVo queryById(Long priceId);

    /** 新增 */
    Long insertByBo(DcsAiModelPriceBo bo);

    /** 修改 */
    Boolean updateByBo(DcsAiModelPriceBo bo);

    /** 批量删除 */
    Boolean deleteByIds(Collection<Long> ids);

    /** 取指定租户/厂商/模型当前生效的价格记录。 */
    DcsAiModelPrice findEffectivePrice(String tenantId, String provider, String modelName);

    /**
     * 直接插入实体(绕过 BO 转换, 供 upsert API 使用)。
     */
    void directInsert(DcsAiModelPrice entity);

    /**
     * 直接更新实体(绕过 BO 转换, 供 upsert API 使用)。
     */
    void directUpdate(DcsAiModelPrice entity);

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
