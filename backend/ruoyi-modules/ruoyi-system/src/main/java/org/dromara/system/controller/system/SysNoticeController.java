package org.dromara.system.controller.system;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.service.DictService;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.system.domain.bo.SysNoticeBo;
import org.dromara.system.domain.vo.SysNoticeVo;
import org.dromara.system.service.ISysNoticeService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 公告 信息操作处理
 *
 * @author Lion Li
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/notice")
public class SysNoticeController extends BaseController {

    private final ISysNoticeService noticeService;

    /**
     * 获取通知公告列表
     */
    @SaCheckPermission("system:notice:list")
    @GetMapping("/list")
    public TableDataInfo<SysNoticeVo> list(SysNoticeBo notice, PageQuery pageQuery) {
        return noticeService.selectPageNoticeList(notice, pageQuery);
    }

    /**
     * 根据通知公告编号获取详细信息
     *
     * @param noticeId 公告ID
     */
    @SaCheckPermission("system:notice:query")
    @GetMapping(value = "/{noticeId}")
    public R<SysNoticeVo> getInfo(@PathVariable Long noticeId) {
        return R.ok(noticeService.selectNoticeById(noticeId));
    }

    /**
     * 新增通知公告
     */
    @SaCheckPermission("system:notice:add")
    @Log(title = "通知公告", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Void> add(@Validated @RequestBody SysNoticeBo notice) {
        fillPublishTime(notice);
        int rows = noticeService.insertNotice(notice);
        if (rows <= 0) {
            return R.fail();
        }
        // N3 2026-10-23：公告改为持久化 + 门户/铃铛拉取。原 publishAll 是全租户广播，
        // 会把 A 租户的公告标题推给 B 租户在线用户；这里不再广播，避免跨租户泄露。
        return R.ok();
    }

    /**
     * 修改通知公告
     */
    @SaCheckPermission("system:notice:edit")
    @Log(title = "通知公告", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated @RequestBody SysNoticeBo notice) {
        fillPublishTime(notice);
        return toAjax(noticeService.updateNotice(notice));
    }

    /**
     * 新增时补默认值：正常状态且未指定发布时间=立即发布；未指定置顶=否。
     *
     * <p>编辑时不再补默认——否则普通改错字会把公告刷成“最新”，也会顺手取消置顶。</p>
     */
    private void fillPublishTime(SysNoticeBo notice) {
        if (notice.getNoticeId() != null) {
            return;
        }
        if ("0".equals(notice.getStatus()) && notice.getPublishTime() == null) {
            notice.setPublishTime(new java.util.Date());
        }
        if (notice.getIsTop() == null || notice.getIsTop().isBlank()) {
            notice.setIsTop("0");
        }
    }

    /**
     * 删除通知公告
     *
     * @param noticeIds 公告ID串
     */
    @SaCheckPermission("system:notice:remove")
    @Log(title = "通知公告", businessType = BusinessType.DELETE)
    @DeleteMapping("/{noticeIds}")
    public R<Void> remove(@PathVariable Long[] noticeIds) {
        return toAjax(noticeService.deleteNoticeByIds(noticeIds));
    }
}
