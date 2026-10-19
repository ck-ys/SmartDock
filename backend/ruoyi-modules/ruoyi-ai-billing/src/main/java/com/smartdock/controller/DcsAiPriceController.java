package com.smartdock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import com.smartdock.domain.DcsAiModelPrice;
import com.smartdock.domain.vo.DcsAiModelPriceVo;
import com.smartdock.service.IDcsAiModelPriceService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 模型价格查询/保存 Controller(切换模型时回填 + 保存价格参数)。
 *
 * @author smartdock
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dcs/ai-price")
public class DcsAiPriceController extends BaseController {

    private final IDcsAiModelPriceService modelPriceService;

    /**
     * 查询当前租户+厂商+模型的有效价格(前端切换模型时自动加载)。
     *
     * @param tenantId  租户ID
     * @param provider  厂商标识
     * @param modelName 模型名称
     * @return 有效模型价格数据
     */
    @SaCheckPermission("dcs:ai:list")
    @GetMapping("/effective")
    public R<DcsAiModelPriceVo> effective(@RequestParam String tenantId,
                                           @RequestParam String provider,
                                           @RequestParam String modelName) {
        DcsAiModelPrice price = modelPriceService.findEffectivePrice(tenantId, provider, modelName);
        if (price == null) {
            return R.ok(null);
        }
        DcsAiModelPriceVo vo = BeanUtil.copyProperties(price, DcsAiModelPriceVo.class);
        return R.ok(vo);
    }

    /**
     * 保存/更新模型价格(有则更新无则插入)。
     *
     * @param entity AI 模型价格实体
     * @return 保存记录的ID
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI模型价格-保存", businessType = BusinessType.UPDATE)
    @PostMapping("/upsert")
    public R<Long> upsert(@RequestBody DcsAiModelPrice entity) {
        // 先尝试找现有行(同租户同厂商同模型)
        DcsAiModelPrice existing = modelPriceService.findEffectivePrice(
            entity.getTenantId(), entity.getProvider(), entity.getModelName());
        if (existing != null && existing.getTenantId().equals(entity.getTenantId())) {
            entity.setPriceId(existing.getPriceId());
            // 直接 updateById, 绕过 BO 转换
            modelPriceService.directUpdate(entity);
            return R.ok(existing.getPriceId());
        }
        modelPriceService.directInsert(entity);
        return R.ok(entity.getPriceId());
    }
}
