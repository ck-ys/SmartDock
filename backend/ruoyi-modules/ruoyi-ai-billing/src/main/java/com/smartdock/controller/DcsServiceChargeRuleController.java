package com.smartdock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import com.smartdock.domain.DcsServiceChargeRule;
import com.smartdock.domain.vo.DcsServiceChargeRuleVo;
import com.smartdock.service.support.TenantWalletService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务计费规则 Controller。
 *
 * @author smartdock
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dcs/service-charge-rule")
public class DcsServiceChargeRuleController extends BaseController {

    private final TenantWalletService walletService;

    /**
     * 分页查询
     *
     * @param pageQuery 分页参数
     * @param tenantId 租户ID（可选）
     * @param serviceCode 服务编码（可选）
     * @return 分页的服务计费规则列表
     */
    @SaCheckPermission("dcs:service-charge-rule:list")
    @GetMapping("/list")
    public TableDataInfo<DcsServiceChargeRuleVo> list(PageQuery pageQuery,
                                                      @RequestParam(required = false) String tenantId,
                                                      @RequestParam(required = false) String serviceCode) {
        return walletService.rulePageList(pageQuery, tenantId, serviceCode);
    }

    /**
     * 详情
     *
     * @param ruleId 服务计费规则ID
     * @return 服务计费规则业务数据
     */
    @SaCheckPermission("dcs:service-charge-rule:list")
    @GetMapping("/{ruleId}")
    public R<DcsServiceChargeRuleVo> getInfo(@PathVariable Long ruleId) {
        return R.ok(walletService.ruleById(ruleId));
    }

    /**
     * 新增
     *
     * @param entity 服务计费规则实体
     * @return 新建记录的ID
     */
    @SaCheckPermission("dcs:service-charge-rule:edit")
    @Log(title = "服务计费规则-新增", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Long> add(@RequestBody DcsServiceChargeRule entity) {
        entity.setRuleId(null);
        entity.setTenantId(null);
        return R.ok(walletService.ruleInsert(entity));
    }

    /**
     * 修改
     *
     * @param entity 服务计费规则实体
     * @return 操作结果
     */
    @SaCheckPermission("dcs:service-charge-rule:edit")
    @Log(title = "服务计费规则-修改", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/edit")
    public R<Void> edit(@RequestBody DcsServiceChargeRule entity) {
        return toAjax(walletService.ruleUpdate(entity));
    }

    /**
     * 删除
     *
     * @param ruleIds 服务计费规则ID数组
     * @return 操作结果
     */
    @SaCheckPermission("dcs:service-charge-rule:edit")
    @Log(title = "服务计费规则-删除", businessType = BusinessType.DELETE)
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/{ruleIds}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ruleIds) {
        return toAjax(walletService.ruleDelete(List.of(ruleIds)));
    }
}
