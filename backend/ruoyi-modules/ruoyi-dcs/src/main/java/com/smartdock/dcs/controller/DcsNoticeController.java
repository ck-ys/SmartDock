package com.smartdock.dcs.controller;

import org.dromara.common.satoken.utils.LoginHelper;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.dcs.domain.DcsNotice;
import com.smartdock.dcs.service.DcsNoticeService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知接口(端点组 10 前半)。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/dcs/notice")
@RequiredArgsConstructor
public class DcsNoticeController extends BaseController {

    private final DcsNoticeService noticeService;

    @SaCheckPermission("dcs:notice:list")
    @GetMapping("/my")
    public TableDataInfo<DcsNotice> my(String readFlag, PageQuery pq) {
        return noticeService.myNotices(readFlag, pq, LoginHelper.getUserId());
    }

    @RepeatSubmit
    @SaCheckPermission("dcs:notice:list")
    @PostMapping("/{id}/read")
    public R<Void> markRead(@PathVariable Long id) {
        noticeService.markRead(id, LoginHelper.getUserId());
        return R.ok();
    }
}
