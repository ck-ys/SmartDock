package org.dromara.system.controller.system;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.system.domain.SysNotice;
import org.dromara.system.service.SysNoticePortalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公告门户（N3 2026-10-23）：登录用户读取已发布公告并标记已读。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/dcs/announcement")
@RequiredArgsConstructor
public class SysNoticePortalController extends BaseController {

    private final SysNoticePortalService portalService;

    @SaCheckPermission("dcs:announcement:list")
    @GetMapping("/list")
    public TableDataInfo<SysNotice> list(PageQuery pq, @RequestParam(required = false) String readFlag) {
        return portalService.list(pq, readFlag);
    }

    @SaCheckPermission("dcs:announcement:list")
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(portalService.unreadCount());
    }

    @SaCheckPermission("dcs:announcement:list")
    @GetMapping("/{noticeId}")
    public R<SysNotice> detail(@PathVariable Long noticeId) {
        return R.ok(portalService.detail(noticeId));
    }

    @SaCheckPermission("dcs:announcement:list")
    @PostMapping("/{noticeId}/read")
    public R<Void> read(@PathVariable Long noticeId) {
        portalService.markRead(noticeId);
        return R.ok();
    }
}
