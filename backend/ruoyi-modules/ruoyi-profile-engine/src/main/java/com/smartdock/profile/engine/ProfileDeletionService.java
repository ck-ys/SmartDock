package com.smartdock.profile.engine;

import com.smartdock.analytics.engine.AnalyticsService;
import com.smartdock.profile.domain.ProfileDeletionLog;
import com.smartdock.profile.mapper.ProfileDeletionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主体级删除向导(批11 Q2): 预览波及面 → 确认 → 事务清理(含归档/匿名期事件)
 * → 派生重算收敛 → profile_deletion_log 审计。
 *
 * <p>删除范围 = 主体本人行 + 其匿名期事件(按访客键集合归属)。共享浏览器边界
 * 在预览页披露波及数, 删除动作必须回显 subjectId 确认。重算走 rebuildRange
 * 分布式锁, 窗口取删除事件最早/最晚时刻所在自然日, 覆盖全部被删会话。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileDeletionService {

    private final ProfileDeletionMapper deletionMapper;
    private final AnalyticsService analyticsService;
    private final TransactionTemplate txTemplate;

    /** 预览波及面: 不动任何数据, 返回各域将删除的行数与重算窗口。 */
    public Map<String, Object> preview(String source, String subjectType, String subjectId) {
        String tenantId = ProfileTenantContext.require();
        List<String> keys = deletionMapper.selectVisitorKeys(tenantId, source, subjectType, subjectId);
        Map<String, Object> out = counts(tenantId, source, subjectType, subjectId, keys);
        Date min = deletionMapper.minOccurred(tenantId, source, subjectType, subjectId, keys);
        Date max = deletionMapper.maxOccurred(tenantId, source, subjectType, subjectId, keys);
        out.put("visitorKeys", keys);
        out.put("minOccurred", min);
        out.put("maxOccurred", max);
        out.put("rebuildDays", rebuildDays(min, max));
        boolean nothing = keys.size() <= 1 && ((long) out.get("events") + (long) out.get("archives")
            + (long) out.get("links") + (long) out.get("subjects")) == 0;
        out.put("deletable", !nothing);
        if (nothing) {
            out.put("warning", "该主体在所选源下无可删数据(可能已清理或不存在)");
        }
        return out;
    }

    /**
     * 执行删除: 必须回显 subjectId 确认。事务内清理九处, 事务外重算收敛, 全程留审计行。
     * 重算失败不回滚清理(GDPR 口径以删除为准), 记入 errorMsg 并抛出提醒人工重算。
     */
    public Map<String, Object> execute(String source, String subjectType, String subjectId, String confirmText) {
        if (!subjectId.equals(confirmText)) {
            throw new ServiceException("确认文本与主体 ID 不一致, 拒绝执行");
        }
        String tenantId = ProfileTenantContext.require();
        List<String> keys = deletionMapper.selectVisitorKeys(tenantId, source, subjectType, subjectId);
        Map<String, Object> before = counts(tenantId, source, subjectType, subjectId, keys);
        Date min = deletionMapper.minOccurred(tenantId, source, subjectType, subjectId, keys);
        Date max = deletionMapper.maxOccurred(tenantId, source, subjectType, subjectId, keys);

        ProfileDeletionLog row = new ProfileDeletionLog();
        row.setTenantId(tenantId);
        row.setSource(source);
        row.setSubjectType(subjectType);
        row.setSubjectId(subjectId);
        row.setPreview(JsonUtils.toJsonString(before));
        row.setDaysRebuilt(0);
        try {
            int[] r = txTemplate.execute(status -> {
                int events = deletionMapper.deleteEvents(tenantId, source, subjectType, subjectId, keys);
                int archives = deletionMapper.deleteArchives(tenantId, source, subjectType, subjectId, keys);
                int sessions = deletionMapper.deleteSessions(tenantId, source, subjectType, subjectId, keys);
                int firstSeen = deletionMapper.deleteFirstSeen(tenantId, source, keys);
                int links = deletionMapper.deleteLinks(tenantId, source, subjectType, subjectId);
                int snapshots = deletionMapper.deleteSnapshots(tenantId, source, subjectType, subjectId)
                    + deletionMapper.deleteScoreSnapshots(tenantId, source, subjectType, subjectId);
                int members = deletionMapper.deleteMembers(tenantId, source, subjectType, subjectId);
                int reviews = deletionMapper.deleteReviews(tenantId, source, subjectType, subjectId);
                int subjects = deletionMapper.deleteSubject(tenantId, source, subjectType, subjectId);
                log.info("[Profile] deletion executed tenant={} source={} subject={}/{} events={} archives={} "
                    + "sessions={} firstSeen={} links={} snapshots={} members={} reviews={} subjects={}",
                    tenantId, source, subjectType, subjectId, events, archives, sessions, firstSeen,
                    links, snapshots, members, reviews, subjects);
                return new int[]{events, archives, sessions, links, snapshots, members};
            });
            row.setEventsDeleted((long) r[0]);
            row.setArchivesDeleted((long) r[1]);
            row.setSessionsDeleted((long) r[2]);
            row.setLinksDeleted((long) r[3]);
            row.setSnapshotsDeleted((long) r[4]);
            row.setMembersDeleted((long) r[5]);
            row.setStatus("EXECUTED");
            if (min != null && max != null) {
                try {
                    analyticsService.rebuildRange(tenantId, source, startOfDay(min),
                        startOfNextDay(max), true, "DELETION");
                    row.setDaysRebuilt(rebuildDays(min, max));
                } catch (Exception ex) {
                    row.setErrorMsg("rebuild failed: " + ex.getMessage());
                    log.error("[Profile] deletion rebuild failed tenant={} source={} subject={}",
                        tenantId, source, subjectId, ex);
                    saveLog(row);
                    throw new ServiceException("数据已删除但派生重算失败, 请手动重建该时间段: " + ex.getMessage());
                }
            }
            saveLog(row);
            Map<String, Object> out = new LinkedHashMap<>(before);
            out.put("status", "EXECUTED");
            out.put("daysRebuilt", row.getDaysRebuilt());
            out.put("logId", row.getLogId());
            return out;
        } catch (ServiceException se) {
            throw se;
        } catch (Exception e) {
            row.setStatus("FAILED");
            row.setErrorMsg(truncate(e.getMessage()));
            saveLog(row);
            log.error("[Profile] deletion failed tenant={} source={} subject={}/{}",
                tenantId, source, subjectType, subjectId, e);
            throw new ServiceException("删除失败已回滚: " + truncate(e.getMessage()));
        }
    }

    /** 最近 50 条删除审计。 */
    public List<ProfileDeletionLog> recentLogs() {
        return deletionMapper.selectRecent(ProfileTenantContext.require());
    }

    // ------------------------------------------------------------ internal

    private Map<String, Object> counts(String tenantId, String source, String subjectType,
                                       String subjectId, List<String> keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", deletionMapper.countEvents(tenantId, source, subjectType, subjectId, keys));
        out.put("archives", deletionMapper.countArchives(tenantId, source, subjectType, subjectId, keys));
        out.put("sessions", deletionMapper.countSessions(tenantId, source, subjectType, subjectId, keys));
        out.put("links", deletionMapper.countLinks(tenantId, source, subjectType, subjectId));
        out.put("snapshots", deletionMapper.countSnapshots(tenantId, source, subjectType, subjectId));
        out.put("members", deletionMapper.countMembers(tenantId, source, subjectType, subjectId));
        out.put("reviews", deletionMapper.countReviews(tenantId, source, subjectType, subjectId));
        out.put("subjects", deletionMapper.countSubjects(tenantId, source, subjectType, subjectId));
        return out;
    }

    private void saveLog(ProfileDeletionLog row) {
        row.setExecutedAt(new Date());
        row.setCreateTime(new Date());
        row.setCreateBy(LoginHelper.getUserId());
        deletionMapper.insert(row);
    }

    private static int rebuildDays(Date min, Date max) {
        if (min == null || max == null) {
            return 0;
        }
        return (int) ((startOfNextDay(max).getTime() - startOfDay(min).getTime()) / 86_400_000L);
    }

    private static Date startOfDay(Date d) {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    private static Date startOfNextDay(Date d) {
        return new Date(startOfDay(d).getTime() + 86_400_000L);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 480 ? s.substring(0, 480) : s;
    }
}
