package com.smartdock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import com.smartdock.domain.bo.DcsAiModelPriceBo;
import com.smartdock.domain.bo.DcsAiModelPriceQueryBo;
import com.smartdock.domain.vo.DcsAiModelPriceVo;
import com.smartdock.service.IDcsAiModelPriceService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * DCS AI 模型价格配置 Controller。
 *
 * @author smartdock
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dcs/ai-model-price")
public class DcsAiModelPriceController extends BaseController {

    private final IDcsAiModelPriceService priceService;

    /**
     * 分页查询。
     *
     * @param bo         AI 模型价格查询条件
     * @param pageQuery 分页参数
     * @return 分页的 AI 模型价格列表
     */
    @SaCheckPermission("dcs:ai:list")
    @GetMapping("/list")
    public TableDataInfo<DcsAiModelPriceVo> list(DcsAiModelPriceQueryBo bo, PageQuery pageQuery) {
        return priceService.queryPageList(bo, pageQuery);
    }

    /**
     * 详情。
     *
     * @param priceId 价格配置ID
     * @return AI 模型价格详情数据
     */
    @SaCheckPermission("dcs:ai:query")
    @GetMapping("/{priceId}")
    public R<DcsAiModelPriceVo> getInfo(@PathVariable Long priceId) {
        return R.ok(priceService.queryById(priceId));
    }

    /**
     * 新增。
     *
     * @param bo AI 模型价格业务对象
     * @return 新建记录的ID
     */
    @SaCheckPermission("dcs:ai:add")
    @Log(title = "DCS AI模型价格", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody DcsAiModelPriceBo bo) {
        return R.ok(priceService.insertByBo(bo));
    }

    /**
     * 修改。
     *
     * @param bo AI 模型价格业务对象
     * @return 操作结果
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI模型价格", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/edit")
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody DcsAiModelPriceBo bo) {
        return toAjax(priceService.updateByBo(bo));
    }

    /**
     * 删除。
     *
     * @param priceIds 价格配置ID数组
     * @return 操作结果
     */
    @SaCheckPermission("dcs:ai:remove")
    @Log(title = "DCS AI模型价格", businessType = BusinessType.DELETE)
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/{priceIds}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] priceIds) {
        return toAjax(priceService.deleteByIds(List.of(priceIds)));
    }

    /**
     * Q6-A（2026-10-23 拍板）：从 models.dev 同步价格。
     * 人工价（manual_override='1'）跳过，不被覆盖；返回 inserted/updated/skipped 计数。
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI模型价格同步", businessType = BusinessType.UPDATE)
    @PostMapping("/sync-models-dev")
    public R<java.util.Map<String, Object>> syncModelsDev() {
        return R.ok(priceService.syncFromModelsDev(org.dromara.common.satoken.utils.LoginHelper.getTenantId()));
    }

    /**
     * Q6-A：JSON 幂等导入价格（{"prices":[...]}），可重复执行。
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI模型价格导入", businessType = BusinessType.IMPORT)
    @PostMapping("/import")
    public R<java.util.Map<String, Object>> importJson(@RequestBody java.util.Map<String, Object> payload) {
        return R.ok(priceService.importJson(org.dromara.common.satoken.utils.LoginHelper.getTenantId(), payload));
    }
}
