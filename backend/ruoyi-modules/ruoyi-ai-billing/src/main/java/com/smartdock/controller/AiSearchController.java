package com.smartdock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.smartdock.domain.AiSearchService;
import com.smartdock.domain.vo.AiSearchBalanceVo;
import com.smartdock.domain.vo.AiSearchProviderVo;
import com.smartdock.domain.vo.AiSearchServiceVo;
import com.smartdock.service.IAiSearchService;

import java.util.List;

/**
 * 联网搜索服务 Controller（额度三级管控批 Q16=1，2026-11-26）。
 *
 * <p>放在「AI 模型与钥匙」页的搜索服务小节下——与模型钥匙同一页、同一套列模板，
 * 管理员只记一个地方（板上 Q16 案 1 的理由）。</p>
 *
 * <p>权限串：读走 {@code smartdock:ai:list}（与钥匙管理同页同权限，租户管理员已授），
 * 写走 {@code smartdock:ai:edit}（未授给租户管理员——只有超管能改，与「接模型」一致）。</p>
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai-search")
public class AiSearchController extends BaseController {

    private final IAiSearchService searchService;

    /** 搜索服务列表（一家一行；空列表=还没接入）。出参只带密钥掩码，明文永不出网 */
    @SaCheckPermission("smartdock:ai:list")
    @GetMapping("/list")
    public R<List<AiSearchServiceVo>> list() {
        return R.ok(searchService.list(LoginHelper.getTenantId()));
    }

    /** 搜索厂商目录（新增时的下拉） */
    @SaCheckPermission("smartdock:ai:list")
    @GetMapping("/providers")
    public R<List<AiSearchProviderVo>> providers() {
        return R.ok(searchService.providers());
    }

    /** 新增/更新（按租户+厂商幂等；key 留空=不改） */
    @SaCheckPermission("smartdock:ai:edit")
    @Log(title = "搜索服务-保存", businessType = BusinessType.UPDATE)
    @PostMapping("/save")
    public R<AiSearchService> save(@RequestBody AiSearchService bo) {
        return R.ok(searchService.save(LoginHelper.getTenantId(), bo, LoginHelper.getUserId()));
    }

    @SaCheckPermission("smartdock:ai:edit")
    @Log(title = "搜索服务-启停", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/status")
    public R<Void> changeStatus(@PathVariable Long id, @RequestParam String enabled) {
        searchService.changeStatus(LoginHelper.getTenantId(), id, enabled);
        return R.ok();
    }

    @SaCheckPermission("smartdock:ai:edit")
    @Log(title = "搜索服务-删除", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        searchService.remove(LoginHelper.getTenantId(), id);
        return R.ok();
    }

    /** 查一次余额（官方接口）；查不到会回 status=UNSUPPORTED/ERROR，不拿旧值冒充 */
    @SaCheckPermission("smartdock:ai:list")
    @PostMapping("/{id}/refresh-balance")
    public R<AiSearchBalanceVo> refreshBalance(@PathVariable Long id) {
        return R.ok(searchService.refreshBalance(LoginHelper.getTenantId(), id));
    }
}
