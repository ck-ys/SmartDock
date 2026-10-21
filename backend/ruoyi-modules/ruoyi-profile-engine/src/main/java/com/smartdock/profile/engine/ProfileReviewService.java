package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.profile.domain.ProfileEngineConfig;
import com.smartdock.profile.domain.ProfileReviewQueue;
import com.smartdock.profile.event.ProfileSubject;
import com.smartdock.profile.mapper.ProfileEngineConfigMapper;
import com.smartdock.profile.mapper.ProfileReviewQueueMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import com.smartdock.profile.query.ProfileReviewActionBo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 低分复核队列: 重算后自动入队/自动销案 + 人工确认/驳回。
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileReviewService {

    private final ProfileReviewQueueMapper reviewMapper;
    private final ProfileEngineConfigMapper configMapper;
    private final ProfileEngineConfigService configService;
    private final ProfileSubjectMapper subjectMapper;

    /**
     * 重算后回调: 低于阈值入 PENDING, 回升则把存量 PENDING 置 SUPERSEDED。
     *
     * @return 是否新建了复核项
     */
    public boolean evaluateAfterRecalc(ProfileScoreSnapshot snapshot) {
        if (snapshot == null || snapshot.getTotalScore() == null) {
            return false;
        }
        String tenantId = snapshot.getTenantId();
        String source = snapshot.getSource();
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(source)) {
            return false;
        }
        ProfileEngineConfig config = configService.getOrCreate(tenantId, source);
        if (!"1".equals(config.getReviewEnabled())) {
            return false;
        }
        BigDecimal threshold = config.getReviewThreshold() == null
            ? BigDecimal.valueOf(60) : config.getReviewThreshold();
        ProfileReviewQueue pending = pending(tenantId, source, snapshot.getSubjectType(), snapshot.getSubjectId());
        boolean low = snapshot.getTotalScore().compareTo(threshold) < 0;
        if (!low) {
            if (pending != null) {
                pending.setStatus("SUPERSEDED");
                pending.setReviewNote("分数回升至 " + snapshot.getTotalScore().toPlainString()
                    + "，自动销案（阈值 " + threshold.toPlainString() + "）");
                pending.setReviewedAt(new Date());
                reviewMapper.updateById(pending);
            }
            return false;
        }
        String reason = "总分 " + snapshot.getTotalScore().setScale(2, RoundingMode.HALF_UP).toPlainString()
            + " 低于复核阈值 " + threshold.setScale(2, RoundingMode.HALF_UP).toPlainString();
        if (pending != null) {
            pending.setSnapshotId(snapshot.getSnapshotId());
            pending.setTotalScore(snapshot.getTotalScore());
            pending.setTriggerReason(reason);
            reviewMapper.updateById(pending);
            return false;
        }
        // 已人工处理过且分数没有继续恶化: 不重复打扰
        ProfileReviewQueue last = latest(tenantId, source, snapshot.getSubjectType(), snapshot.getSubjectId());
        if (last != null && ("DISMISSED".equals(last.getStatus()) || "CONFIRMED".equals(last.getStatus()))
            && last.getTotalScore() != null
            && snapshot.getTotalScore().compareTo(last.getTotalScore()) >= 0) {
            return false;
        }
        ProfileReviewQueue review = new ProfileReviewQueue();
        review.setReviewId(IdWorker.getId());
        review.setTenantId(tenantId);
        review.setSource(source);
        review.setSubjectType(snapshot.getSubjectType());
        review.setSubjectId(snapshot.getSubjectId());
        review.setDisplayName(displayName(tenantId, source, snapshot.getSubjectType(), snapshot.getSubjectId()));
        review.setSnapshotId(snapshot.getSnapshotId());
        review.setTotalScore(snapshot.getTotalScore());
        review.setRiskLevel("HIGH");
        review.setTriggerReason(reason);
        review.setStatus("PENDING");
        try {
            reviewMapper.insert(review);
            return true;
        } catch (DuplicateKeyException concurrent) {
            // 并发重算同时入队: 唯一索引兜底, 保留已有 PENDING
            return false;
        }
    }

    public IPage<ProfileReviewQueue> page(String source, String status, String riskLevel,
                                          String keyword, int pageNum, int pageSize) {
        Page<ProfileReviewQueue> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 200));
        page.setOptimizeCountSql(false);
        // 批10 R5: 超时置顶在 SQL 层全局排序(此前只排当前页, 跨页顺序不保证)
        reviewMapper.selectPageOverdueFirst(page, currentTenant(),
            StringUtils.isBlank(source) ? null : source,
            StringUtils.isBlank(status) ? null : status,
            StringUtils.isBlank(riskLevel) ? null : riskLevel,
            StringUtils.isBlank(keyword) ? null : keyword);
        attachOverdue(page.getRecords());
        return page;
    }

    /** 复核 SLA 概况(批9 助手只读工具用)。 */
    public Map<String, Object> summary(String source) {
        LambdaQueryWrapper<ProfileReviewQueue> wrapper = new LambdaQueryWrapper<ProfileReviewQueue>()
            .eq(StringUtils.isNotBlank(source), ProfileReviewQueue::getSource, source)
            .eq(ProfileReviewQueue::getStatus, "PENDING");
        List<ProfileReviewQueue> pending = reviewMapper.selectList(wrapper);
        Map<String, Long> bySource = new LinkedHashMap<>();
        long overdue = 0;
        Date now = new Date();
        Map<String, Integer> slaBySource = slaBySource();
        Date oldest = null;
        for (ProfileReviewQueue r : pending) {
            bySource.merge(r.getSource(), 1L, Long::sum);
            Integer sla = slaBySource.getOrDefault(slaKey(r.getTenantId(), r.getSource()), 7);
            if (slaOverdueDays(r.getCreateTime(), sla, now) > 0) {
                overdue++;
            }
            if (r.getCreateTime() != null && (oldest == null || r.getCreateTime().before(oldest))) {
                oldest = r.getCreateTime();
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pendingTotal", pending.size());
        out.put("pendingBySource", bySource);
        out.put("overdueTotal", overdue);
        out.put("oldestCreatedAt", oldest);
        out.put("reviewEnabled", configService.getOrCreate(
            StringUtils.isBlank(source) ? "web" : source).getReviewEnabled());
        return out;
    }

    /** 超时天数现算(批9 Q4/Q8-A: 入队时间+SLA, 改配置立即生效, 不回填)。 */
    private void attachOverdue(List<ProfileReviewQueue> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<String, Integer> slaBySource = slaBySource();
        Date now = new Date();
        for (ProfileReviewQueue r : rows) {
            Integer sla = slaBySource.getOrDefault(slaKey(r.getTenantId(), r.getSource()), 7);
            long overdue = slaOverdueDays(r.getCreateTime(), sla, now);
            r.setOverdueDays(overdue);
            r.setSlaDays(sla);
        }
        // 超时的排前面(同组内按超时天数降序)
        rows.sort((a, b) -> Long.compare(b.getOverdueDays() == null ? 0 : b.getOverdueDays(),
            a.getOverdueDays() == null ? 0 : a.getOverdueDays()));
    }

    private Map<String, Integer> slaBySource() {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            for (ProfileEngineConfig config : TenantHelper.ignore(() ->
                configMapper.selectList(new LambdaQueryWrapper<ProfileEngineConfig>()))) {
                // 键带租户: 多租户共用同名 source(如 web)时不能互相覆盖
                out.put(slaKey(config.getTenantId(), config.getSource()),
                    config.getReviewSlaDays() == null ? 7 : config.getReviewSlaDays());
            }
        } catch (Exception ignored) {
            // 配置表不可用时走默认
        }
        return out;
    }

    private static String slaKey(String tenantId, String source) {
        return (tenantId == null ? "" : tenantId) + "|" + (source == null ? "" : source);
    }

    static long slaOverdueDays(Date createdAt, Integer slaDays, Date now) {
        if (createdAt == null) {
            return 0;
        }
        long sla = (slaDays == null ? 7 : slaDays) * 86_400_000L;
        long age = now.getTime() - createdAt.getTime();
        return age <= sla ? 0 : (age - sla) / 86_400_000L + 1;
    }

    public ProfileReviewQueue get(Long reviewId) {
        ProfileReviewQueue review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new ServiceException("复核项不存在");
        }
        return review;
    }

    public ProfileReviewQueue confirm(Long reviewId, ProfileReviewActionBo bo) {
        return finish(reviewId, "CONFIRMED", "CONFIRMED", bo);
    }

    public ProfileReviewQueue dismiss(Long reviewId, ProfileReviewActionBo bo) {
        return finish(reviewId, "DISMISSED", "DISMISSED", bo);
    }

    /**
     * 手动扫描当前 source 的最新快照, 补齐复核队列。
     */
    public Map<String, Object> scan(String source) {
        if (StringUtils.isBlank(source)) {
            throw new ServiceException("source 不能为空");
        }
        List<ProfileScoreSnapshot> snapshots = subjectMapper.selectLatestSnapshots(currentTenant(), source);
        int created = 0;
        for (ProfileScoreSnapshot snapshot : snapshots) {
            if (evaluateAfterRecalc(snapshot)) {
                created++;
            }
        }
        Long pending = reviewMapper.selectCount(new LambdaQueryWrapper<ProfileReviewQueue>()
            .eq(ProfileReviewQueue::getTenantId, currentTenant())
            .eq(ProfileReviewQueue::getSource, source)
            .eq(ProfileReviewQueue::getStatus, "PENDING"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scanned", snapshots.size());
        out.put("created", created);
        out.put("pending", pending == null ? 0 : pending);
        return out;
    }

    public ProfileEngineConfig config(String source) {
        return configService.getOrCreate(source);
    }

    private ProfileReviewQueue finish(Long reviewId, String status, String conclusion, ProfileReviewActionBo bo) {
        ProfileReviewQueue review = get(reviewId);
        if (!"PENDING".equals(review.getStatus())) {
            throw new ServiceException("只有待复核项可以处理, 当前状态: " + review.getStatus());
        }
        review.setStatus(status);
        review.setConclusion(conclusion);
        if (bo != null && bo.getNote() != null) {
            review.setReviewNote(StringUtils.substring(bo.getNote(), 0, 500));
        }
        if (bo != null && StringUtils.isNotBlank(bo.getRiskLevel())) {
            review.setRiskLevel(bo.getRiskLevel().trim().toUpperCase());
        }
        review.setReviewedBy(currentUser());
        review.setReviewedAt(new Date());
        review.setUpdateBy(currentUser());
        reviewMapper.updateById(review);
        return review;
    }

    private ProfileReviewQueue pending(String tenantId, String source, String subjectType, String subjectId) {
        return reviewMapper.selectList(new LambdaQueryWrapper<ProfileReviewQueue>()
                .eq(ProfileReviewQueue::getTenantId, tenantId)
                .eq(ProfileReviewQueue::getSource, source)
                .eq(ProfileReviewQueue::getSubjectType, subjectType)
                .eq(ProfileReviewQueue::getSubjectId, subjectId)
                .eq(ProfileReviewQueue::getStatus, "PENDING")
                .last("LIMIT 1"))
            .stream().findFirst().orElse(null);
    }

    private ProfileReviewQueue latest(String tenantId, String source, String subjectType, String subjectId) {
        return reviewMapper.selectList(new LambdaQueryWrapper<ProfileReviewQueue>()
                .eq(ProfileReviewQueue::getTenantId, tenantId)
                .eq(ProfileReviewQueue::getSource, source)
                .eq(ProfileReviewQueue::getSubjectType, subjectType)
                .eq(ProfileReviewQueue::getSubjectId, subjectId)
                .orderByDesc(ProfileReviewQueue::getCreateTime)
                .last("LIMIT 1"))
            .stream().findFirst().orElse(null);
    }

    private String displayName(String tenantId, String source, String subjectType, String subjectId) {
        return subjectMapper.selectList(new LambdaQueryWrapper<ProfileSubject>()
                .eq(ProfileSubject::getTenantId, tenantId)
                .eq(ProfileSubject::getSource, source)
                .eq(ProfileSubject::getSubjectType, subjectType)
                .eq(ProfileSubject::getSubjectId, subjectId)
                .last("LIMIT 1"))
            .stream().findFirst().map(ProfileSubject::getDisplayName).orElse(null);
    }

    private Long currentUser() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentTenant() {
        try {
            String t = TenantHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        try {
            String t = LoginHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 非登录上下文
        }
        return "000000";
    }
}
