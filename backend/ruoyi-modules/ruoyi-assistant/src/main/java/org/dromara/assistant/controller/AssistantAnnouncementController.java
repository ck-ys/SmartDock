package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.domain.DcsAssistantAnnouncement;
import org.dromara.assistant.mapper.AssistantAnnouncementMapper;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * AI 助手公告端点（AI 缺口批 Q11，2026-10-14 拍板全A）。
 *
 * <p>管理台「公告」页签发布/撤回；用户侧 GET /active 取最新一条生效公告渲染顶部横幅
 * （每用户本地可关闭，新公告 id 变化自然重现）。全 GET/POST（用户铁律 #21）。</p>
 */
@Tag(name = "助手公告", description = "管理台发布/撤回 + 用户侧横幅")
@RestController
@RequestMapping("/dcs/assistant/announcement")
@RequiredArgsConstructor
public class AssistantAnnouncementController extends BaseController {

    private final AssistantAnnouncementMapper announcementMapper;

    /** 用户侧：当前租户最新一条生效公告（无=null，前端不渲染横幅） */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/active")
    public R<Map<String, Object>> active() {
        DcsAssistantAnnouncement row =
            announcementMapper.selectActive(LoginHelper.getTenantId());
        if (row == null) {
            return R.ok(null);
        }
        return R.ok(Map.of(
            "id", row.getId(),
            "title", row.getTitle(),
            "content", StrUtil.nullToEmpty(row.getContent()),
            "style", StrUtil.nullToDefault(row.getStyle(), "NORMAL"),
            "publishTime", row.getPublishTime() == null ? "" : row.getPublishTime().toString()));
    }

    /** 管理端：本租户公告列表（含已撤回，发布时间倒序） */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/admin/list")
    public TableDataInfo<DcsAssistantAnnouncement> adminList() {
        List<DcsAssistantAnnouncement> rows = announcementMapper.selectList(
            new LambdaQueryWrapper<DcsAssistantAnnouncement>()
                .eq(DcsAssistantAnnouncement::getTenantId, LoginHelper.getTenantId())
                .orderByDesc(DcsAssistantAnnouncement::getPublishTime)
                .orderByDesc(DcsAssistantAnnouncement::getId));
        TableDataInfo<DcsAssistantAnnouncement> data = TableDataInfo.build(rows);
        data.setTotal(rows.size());
        return data;
    }

    /** 公告载荷 */
    @Data
    public static class AnnouncementBo {
        /** 编辑时传（null=新增发布） */
        private Long id;
        /** 标题（必填 ≤120） */
        private String title;
        /** 正文（可空 ≤1000） */
        private String content;
        /** NORMAL / MAINTENANCE */
        private String style;
    }

    /** 管理端：发布/编辑公告（保存即生效 status=1，publish_time 刷新=置顶重现） */
    @SaCheckPermission("dcs:assistant:config")
    @Log(title = "AI助手-公告发布", businessType = BusinessType.INSERT)
    @PostMapping("/admin/publish")
    public R<Void> publish(@RequestBody AnnouncementBo bo) {
        if (StrUtil.isBlank(bo.getTitle()) || bo.getTitle().trim().length() > 120) {
            throw new ServiceException("公告标题必填且不超过 120 字");
        }
        if (bo.getContent() != null && bo.getContent().length() > 1000) {
            throw new ServiceException("公告正文不超过 1000 字");
        }
        String style = "MAINTENANCE".equals(bo.getStyle()) ? "MAINTENANCE" : "NORMAL";
        String tenantId = LoginHelper.getTenantId();
        Date now = new Date();
        if (bo.getId() == null) {
            DcsAssistantAnnouncement row = new DcsAssistantAnnouncement();
            row.setId(IdWorker.getId());
            row.setTenantId(tenantId);
            row.setTitle(bo.getTitle().trim());
            row.setContent(StrUtil.trimToNull(bo.getContent()));
            row.setStyle(style);
            row.setStatus("1");
            row.setPublishBy(LoginHelper.getUserId());
            row.setPublishTime(now);
            row.setCreateTime(now);
            announcementMapper.insert(row);
        } else {
            DcsAssistantAnnouncement existing = announcementMapper.selectById(bo.getId());
            if (existing == null || !existing.getTenantId().equals(tenantId)) {
                throw new ServiceException("公告不存在或不属于当前租户", 403);
            }
            existing.setTitle(bo.getTitle().trim());
            existing.setContent(StrUtil.trimToNull(bo.getContent()));
            existing.setStyle(style);
            existing.setStatus("1");
            existing.setPublishBy(LoginHelper.getUserId());
            existing.setPublishTime(now);
            existing.setUpdateTime(now);
            announcementMapper.updateById(existing);
        }
        return R.ok();
    }

    /** 管理端：撤回公告（status=0 保留历史；用户侧横幅立即消失） */
    @SaCheckPermission("dcs:assistant:config")
    @Log(title = "AI助手-公告撤回", businessType = BusinessType.UPDATE)
    @PostMapping("/admin/revoke")
    public R<Void> revoke(@RequestParam Long id) {
        DcsAssistantAnnouncement existing = announcementMapper.selectById(id);
        if (existing == null || !existing.getTenantId().equals(LoginHelper.getTenantId())) {
            throw new ServiceException("公告不存在或不属于当前租户", 403);
        }
        DcsAssistantAnnouncement patch = new DcsAssistantAnnouncement();
        patch.setId(id);
        patch.setStatus("0");
        patch.setUpdateTime(new Date());
        announcementMapper.updateById(patch);
        return R.ok();
    }
}
