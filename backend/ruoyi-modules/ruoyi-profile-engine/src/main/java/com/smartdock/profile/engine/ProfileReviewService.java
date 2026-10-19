package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.profile.domain.ProfileEngineConfig;
import com.smartdock.profile.domain.ProfileReviewQueue;
import com.smartdock.profile.event.ProfileSubject;
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
    private final ProfileSubjectMapper subjectMapper;
    private final ProfileEngineConfigService configService;

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
        LambdaQueryWrapper<ProfileReviewQueue> wrapper = new LambdaQueryWrapper<ProfileReviewQueue>()
            .eq(StringUtils.isNotBlank(source), ProfileReviewQueue::getSource, source)
            .eq(StringUtils.isNotBlank(status), ProfileReviewQueue::getStatus, status)
            .eq(StringUtils.isNotBlank(riskLevel), ProfileReviewQueue::getRiskLevel, riskLevel)
            .and(StringUtils.isNotBlank(keyword), w -> w
                .like(ProfileReviewQueue::getSubjectId, keyword)
                .or()
                .like(ProfileReviewQueue::getDisplayName, keyword))
            .orderByDesc(ProfileReviewQueue::getCreateTime);
        reviewMapper.selectPage(page, wrapper);
        return page;
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
