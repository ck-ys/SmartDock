package org.dromara.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.system.domain.SysNotice;
import org.dromara.system.mapper.SysNoticeMapper;
import org.dromara.system.mapper.SysNoticeReadMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 公告门户（N3 2026-10-23）：给登录用户看的已发布公告 + 服务端已读。
 *
 * <p>可见性：status=0、发布时间已到（空=立即）、未过期（空=长期）。置顶优先，
 * 其余按发布时间倒序。租户隔离由租户插件追加 tenant_id。</p>
 *
 * @author smartdock
 */
@Service
@RequiredArgsConstructor
public class SysNoticePortalService {

    private final SysNoticeMapper noticeMapper;
    private final SysNoticeReadMapper noticeReadMapper;

    /** 公告卡片流（分页；readFlag=0 只看未读、1 只看已读）。 */
    public TableDataInfo<SysNotice> list(PageQuery pq, String readFlag) {
        QueryWrapper<SysNotice> wrapper = visibleWrapper();
        if ("0".equals(readFlag) || "1".equals(readFlag)) {
            String exists = "EXISTS (SELECT 1 FROM sys_notice_read r WHERE r.notice_id = sys_notice.notice_id"
                + " AND r.user_id = {0})";
            wrapper.apply("0".equals(readFlag) ? "NOT " + exists : exists, LoginHelper.getUserId());
        }
        Page<SysNotice> page = noticeMapper.selectPage(pq.build(), wrapper
            .orderByDesc("is_top")
            // 老公告 publish_time 为空：PG 的 DESC 会把 NULL 排到最前，必须回落到创建时间
            .orderByDesc("COALESCE(publish_time, create_time)")
            .orderByDesc("notice_id"));
        sanitize(page.getRecords());
        fillReadFlag(page.getRecords());
        return TableDataInfo.build(page);
    }

    /** 公告详情（同样受可见性约束）。 */
    public SysNotice detail(Long noticeId) {
        SysNotice notice = noticeMapper.selectOne(visibleWrapper().eq("notice_id", noticeId));
        if (notice == null) {
            throw new ServiceException("公告不存在或已下线");
        }
        sanitize(List.of(notice));
        fillReadFlag(List.of(notice));
        return notice;
    }

    /** 标记已读（只能标记自己可见的公告）。 */
    public void markRead(Long noticeId) {
        SysNotice notice = noticeMapper.selectOne(visibleWrapper().eq("notice_id", noticeId));
        if (notice == null) {
            throw new ServiceException("公告不存在或已下线");
        }
        noticeReadMapper.markRead(noticeId, LoginHelper.getUserId(), TenantHelper.getTenantId());
    }

    /** 未读公告数（铃铛角标）。 */
    public long unreadCount() {
        // 与列表同源：超管切租户后已读口径跟着被切换到的租户走（TenantHelper 动态优先）
        return noticeReadMapper.countUnread(TenantHelper.getTenantId(), LoginHelper.getUserId(), new Date());
    }

    private QueryWrapper<SysNotice> visibleWrapper() {
        Date now = new Date();
        QueryWrapper<SysNotice> wrapper = new QueryWrapper<>();
        wrapper.eq("status", "0")
            .and(w -> w.isNull("publish_time").or().le("publish_time", now))
            .and(w -> w.isNull("expire_time").or().gt("expire_time", now));
        return wrapper;
    }

    /**
     * 公告正文来自后台富文本编辑器，门户按 HTML 渲染；出口统一过白名单过滤，
     * 脚本/事件属性/危险标签不进浏览器（后台原文不动）。
     *
     * <p>Hutool 默认白名单不含 ul/ol/li，列表会被压成裸文本：先转成「• + 换行」再过滤。
     * script/style 整块移除（只留标签会漏出脚本正文，变成可见乱码）。</p>
     */
    private void sanitize(List<SysNotice> rows) {
        for (SysNotice n : rows) {
            if (n.getNoticeContent() != null) {
                String html = n.getNoticeContent()
                    .replaceAll("(?is)<script[^>]*>.*?</script>", "")
                    .replaceAll("(?is)<style[^>]*>.*?</style>", "")
                    .replaceAll("(?i)<li[^>]*>", "• ")
                    .replaceAll("(?i)</li>", "\n")
                    .replaceAll("(?i)</?(ul|ol)[^>]*>", "");
                n.setNoticeContent(cn.hutool.http.HtmlUtil.filter(html));
            }
        }
    }

    private void fillReadFlag(List<SysNotice> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(SysNotice::getNoticeId).toList();
        Set<Long> readIds = new HashSet<>();
        try {
            readIds.addAll(noticeReadMapper.selectReadIds(TenantHelper.getTenantId(), LoginHelper.getUserId(), ids));
        } catch (Exception ignored) {
            // 已读表异常时按未读展示，不阻塞公告阅读
        }
        for (SysNotice n : rows) {
            n.setReadFlag(readIds.contains(n.getNoticeId()) ? "1" : "0");
        }
    }
}
