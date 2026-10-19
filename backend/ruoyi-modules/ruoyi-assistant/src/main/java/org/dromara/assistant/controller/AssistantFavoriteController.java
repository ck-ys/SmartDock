package org.dromara.assistant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.service.AssistantFavoriteService;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 助手常用问句收藏端点（C2 收藏跨设备，2026-10-07）。
 * 替代 localStorage：用户级落库，换设备/换浏览器 chips 同步。
 */
@Tag(name = "助手常用问句收藏", description = "用户级收藏落库，换设备/换浏览器 chips 同步")
@RestController
@RequestMapping("/dcs/assistant/favorite")
@RequiredArgsConstructor
public class AssistantFavoriteController extends BaseController {

    private final AssistantFavoriteService favoriteService;

    /** 收藏/删除请求体 */
    @Data
    public static class ContentBo {
        @NotNull(message = "缺少内容")
        @NotBlank(message = "缺少内容")
        private String content;
    }

    /** 用户收藏清单（新→旧） */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/list")
    public R<List<String>> list() {
        return R.ok(favoriteService.list(LoginHelper.getTenantId(), LoginHelper.getUserId()));
    }

    /** 新增收藏（上限 6；返回最新清单） */
    @SaCheckPermission("dcs:assistant:use")
    @PostMapping("/add")
    public R<List<String>> add(@RequestBody ContentBo bo) {
        return R.ok(favoriteService.add(LoginHelper.getTenantId(), LoginHelper.getUserId(), bo.getContent()));
    }

    /** 删除收藏（返回最新清单） */
    @SaCheckPermission("dcs:assistant:use")
    @PostMapping("/remove")
    public R<List<String>> remove(@RequestBody ContentBo bo) {
        return R.ok(favoriteService.remove(LoginHelper.getTenantId(), LoginHelper.getUserId(), bo.getContent()));
    }
}
