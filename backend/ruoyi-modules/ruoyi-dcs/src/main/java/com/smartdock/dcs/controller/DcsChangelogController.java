package com.smartdock.dcs.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.dcs.domain.DcsChangelog;
import com.smartdock.dcs.domain.bo.DcsChangelogBo;
import com.smartdock.dcs.service.DcsChangelogService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 更新日志（N3 2026-10-23）：读接口对租户管理员开放，写接口仅超管。
 *
 * <p>写操作统一 POST（WAF 只放行 GET/POST）。</p>
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/dcs/changelog")
@RequiredArgsConstructor
public class DcsChangelogController extends BaseController {

    private final DcsChangelogService changelogService;

    @SaCheckPermission("dcs:changelog:list")
    @GetMapping("/list")
    public TableDataInfo<DcsChangelog> list(DcsChangelogBo bo, PageQuery pq) {
        return changelogService.list(bo, pq);
    }

    @SaCheckPermission("dcs:changelog:query")
    @GetMapping("/{logId}")
    public R<DcsChangelog> getInfo(@PathVariable Long logId) {
        return R.ok(changelogService.getById(logId));
    }

    @SaCheckPermission("dcs:changelog:add")
    @RepeatSubmit
    @PostMapping
    public R<Long> add(@Validated(DcsChangelogBo.Add.class) @RequestBody DcsChangelogBo bo) {
        return R.ok(changelogService.add(bo));
    }

    @SaCheckPermission("dcs:changelog:edit")
    @RepeatSubmit
    @PostMapping("/edit")
    public R<Void> edit(@Validated(DcsChangelogBo.Edit.class) @RequestBody DcsChangelogBo bo) {
        changelogService.edit(bo);
        return R.ok();
    }

    @SaCheckPermission("dcs:changelog:remove")
    @PostMapping("/{logIds}")
    public R<Void> remove(@PathVariable String logIds) {
        List<Long> ids = Arrays.stream(logIds.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
        changelogService.delete(ids);
        return R.ok();
    }
}
