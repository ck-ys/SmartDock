package com.smartdock.dcs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.dcs.domain.DcsNotice;
import com.smartdock.dcs.mapper.DcsNoticeMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 站内通知最小集: 只发给自己租户内指定用户, 只读自己的。
 *
 * @author smartdock
 */
@Service
@RequiredArgsConstructor
public class DcsNoticeService {

    private final DcsNoticeMapper noticeMapper;

    /** 发通知(业务事件内联调用; 失败不阻断由调用方 try 决定) */
    public void notify(Long userId, String noticeType, String title, String content, Long taskId) {
        DcsNotice n = build(userId, noticeType, title, content, taskId);
        noticeMapper.insert(n);
    }

    /**
     * 带业务幂等键的通知(批4 回修): 同 (租户, 用户, 键) 只落一条, 已存在则静默跳过。
     *
     * <p>积分/对账告警族的频次闸——键设计即告警频次(周/月/一次), 源侧无状态表,
     * 判重全靠这里的唯一索引。空键退化为普通 notify。</p>
     *
     * @return 是否真正写入(false=键已存在被跳过)
     */
    public boolean notifyIdem(Long userId, String noticeType, String title, String content,
                              Long taskId, String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) {
            notify(userId, noticeType, title, content, taskId);
            return true;
        }
        DcsNotice n = build(userId, noticeType, title, content, taskId);
        n.setDedupKey(dedupKey.length() > 128 ? dedupKey.substring(0, 128) : dedupKey);
        return noticeMapper.insertIgnoreDedup(n) > 0;
    }

    private DcsNotice build(Long userId, String noticeType, String title, String content, Long taskId) {
        DcsNotice n = new DcsNotice();
        n.setId(IdWorker.getId());
        n.setTenantId(currentTenant());
        n.setUserId(userId);
        n.setNoticeType(noticeType);
        n.setTitle(title != null && title.length() > 200 ? title.substring(0, 200) : title);
        n.setContent(content != null && content.length() > 1000 ? content.substring(0, 1000) : content);
        n.setTaskId(taskId);
        n.setReadFlag("0");
        n.setCreateTime(new Date());
        return n;
    }

    /** 我的通知(未读优先, 同级按时间倒序) */
    public TableDataInfo<DcsNotice> myNotices(String readFlag, PageQuery pq, Long userId) {
        Page<DcsNotice> page = noticeMapper.selectPage(pq.build(), new LambdaQueryWrapper<DcsNotice>()
            .eq(DcsNotice::getUserId, userId)
            .eq(readFlag != null && !readFlag.isBlank(), DcsNotice::getReadFlag, readFlag)
            .orderByAsc(DcsNotice::getReadFlag)
            .orderByDesc(DcsNotice::getId));
        return TableDataInfo.build(page);
    }

    /** 标记已读(只能标自己的) */
    public void markRead(Long noticeId, Long userId) {
        DcsNotice n = noticeMapper.selectById(noticeId);
        if (n == null) {
            throw new ServiceException("通知不存在");
        }
        if (!userId.equals(n.getUserId())) {
            throw new ServiceException("只能操作自己的通知");
        }
        n.setReadFlag("1");
        noticeMapper.updateById(n);
    }

    /** 未读数(助手工具/角标用) */
    public long unreadCount(Long userId) {
        return noticeMapper.selectCount(new LambdaQueryWrapper<DcsNotice>()
            .eq(DcsNotice::getUserId, userId)
            .eq(DcsNotice::getReadFlag, "0"));
    }

    private String currentTenant() {
        // 工具线程无 Sa-Token 上下文: TenantHelper(含 dynamic)优先
        try {
            String t = org.dromara.common.tenant.helper.TenantHelper.getTenantId();
            if (t != null && !t.isBlank()) {
                return t;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        String login = LoginHelper.getTenantId();
        // 批11 回修(终审 P1): LoginHelper 不抛异常只回 null——判空后 fail-fast, 不再静默默认租户
        if (login != null && !login.isBlank()) {
            return login;
        }
        return com.smartdock.profile.engine.ProfileTenantContext.require();
    }
}
