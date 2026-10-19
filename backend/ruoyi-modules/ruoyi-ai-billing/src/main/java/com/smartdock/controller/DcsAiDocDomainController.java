package com.smartdock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.net.URI;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import com.smartdock.domain.DcsAiDocDomain;
import com.smartdock.domain.vo.DcsAiDocDomainVo;
import com.smartdock.mapper.DcsAiDocDomainMapper;
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
 * AI 文档域名白名单 Controller。
 *
 * <p>智能识别计费参数时, 抓取的 URL 域名必须命中此白名单(SSRF 防御)。
 * 超管可在菜单中增删域名。</p>
 *
 * @author smartdock
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dcs/ai-doc-domain")
public class DcsAiDocDomainController extends BaseController {

    private final DcsAiDocDomainMapper domainMapper;

    /**
     * 分页查询。
     *
     * @param pageQuery 分页参数
     * @param domain    域名关键字(模糊匹配, 可选)
     * @param provider  厂商过滤(可选)
     * @param enabled   启用状态过滤(可选)
     * @return 分页的 AI 文档域名列表
     */
    @SaCheckPermission("dcs:ai:list")
    @GetMapping("/list")
    public TableDataInfo<DcsAiDocDomainVo> list(PageQuery pageQuery,
                                                  @RequestParam(required = false) String domain,
                                                  @RequestParam(required = false) String provider,
                                                  @RequestParam(required = false) String enabled) {
        LambdaQueryWrapper<DcsAiDocDomain> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(domain)) {
            wrapper.like(DcsAiDocDomain::getDomain, domain);
        }
        if (StrUtil.isNotBlank(provider)) {
            wrapper.eq(DcsAiDocDomain::getProvider, provider);
        }
        if (StrUtil.isNotBlank(enabled)) {
            wrapper.eq(DcsAiDocDomain::getEnabled, enabled);
        }
        wrapper.orderByAsc(DcsAiDocDomain::getDomain);
        Page<DcsAiDocDomainVo> page = domainMapper.selectVoPage(pageQuery.build(), wrapper);
        return TableDataInfo.build(page);
    }

    /**
     * 详情。
     *
     * @param domainId 域名白名单记录ID
     * @return AI 文档域名详情数据
     */
    @SaCheckPermission("dcs:ai:list")
    @GetMapping("/{domainId}")
    public R<DcsAiDocDomainVo> getInfo(@PathVariable Long domainId) {
        return R.ok(domainMapper.selectVoById(domainId));
    }

    /**
     * 新增。
     *
     * @param entity AI 文档域名实体
     * @return 新建记录的ID
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "AI文档域名-新增", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Long> add(@RequestBody DcsAiDocDomain entity) {
        entity.setDomainId(null);
        entity.setTenantId(null);
        entity.setDomain(extractHost(entity.getDomain()));
        entity.setEnabled(StrUtil.blankToDefault(entity.getEnabled(), "1"));
        domainMapper.insert(entity);
        return R.ok(entity.getDomainId());
    }

    /**
     * 修改。
     *
     * @param entity AI 文档域名实体
     * @return 操作结果
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "AI文档域名-修改", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/edit")
    public R<Void> edit(@RequestBody DcsAiDocDomain entity) {
        entity.setDomain(extractHost(entity.getDomain()));
        return toAjax(domainMapper.updateById(entity) > 0);
    }

    /**
     * 从用户输入提取纯主机名: 兼容 {@code https://llmapiprices.xyz/}、{@code llmapiprices.xyz:443/path} 等。
     * 白名单匹配按 host 后缀比对, 存完整 URL 会导致永远匹配不到。
     */
    private String extractHost(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String trimmed = StrUtil.trim(raw);
        try {
            String withScheme = trimmed.contains("://") ? trimmed : "https://" + trimmed;
            String host = new URI(withScheme).getHost();
            return host != null ? host.toLowerCase() : trimmed.toLowerCase();
        } catch (Exception e) {
            return trimmed.toLowerCase();
        }
    }

    /**
     * 删除。
     *
     * @param domainIds 域名白名单记录ID数组
     * @return 操作结果
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "AI文档域名-删除", businessType = BusinessType.DELETE)
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/{domainIds}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] domainIds) {
        return toAjax(domainMapper.deleteByIds(List.of(domainIds)) > 0);
    }
}
