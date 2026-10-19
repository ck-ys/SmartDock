package com.smartdock.risk.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.risk.domain.DcsProfileReview;
import com.smartdock.risk.domain.vo.ProfileReviewAggregateVo;
import com.smartdock.risk.mapper.DcsProfileReviewMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Separate business-profile review queue; security evidence is deliberately absent. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileRiskReviewService {
    /** 画像复核表读写 Mapper（HIGH 风险入队/分页/复核） */
    private final DcsProfileReviewMapper reviewMapper;
    /** 风险处置策略服务（校验复核结论/理由） */
    private final RiskActionPolicy policy;

    /**
     * 画像分达到 HIGH 时入复核队列(2026-08-23 语义改造: 同人仅保留一条 PENDING)。
     * <p>upsertPending 以部分唯一索引 (tenant_id,user_id) WHERE status='PENDING' 仲裁——
     * 同人已有待复核时新评分快照覆盖旧快照(不再按快照堆叠行, 根治 4.4 万条积压问题);
     * 非 HIGH / userId 为空 / scoreId 为空直接跳过。</p>
     */
    public void ensurePending(String tenantId, Long userId, Long scoreId, String ruleVersion,
                              String profileRiskLevel, String triggerReason) {
        if (!"HIGH".equals(profileRiskLevel) || userId == null || scoreId == null) return;
        DcsProfileReview review = new DcsProfileReview();
        review.setReviewId(IdUtil.getSnowflakeNextId());
        review.setTenantId(tenantId);
        review.setUserId(userId);
        review.setScoreId(scoreId);
        review.setRuleVersion(ruleVersion);
        review.setProfileRiskLevel(profileRiskLevel);
        review.setStatus("PENDING");
        review.setTriggerReason(triggerReason == null || triggerReason.isBlank() ? "画像分达到高风险区间" : triggerReason.trim());
        int rows = TenantHelper.ignore(() -> reviewMapper.upsertPending(review));
        if (rows > 0) {
            log.info("[ProfileReview] upsert tenant={} user={} score={} rule={}",
                tenantId, userId, scoreId, ruleVersion);
        }
    }

    /** 分页查询画像复核记录(按状态/用户筛选)。 */
    public Page<DcsProfileReview> page(String tenantId, String status, Long userId, PageQuery pageQuery) {
        return TenantHelper.ignore(() -> reviewMapper.selectPage(pageQuery.build(),
            new LambdaQueryWrapper<DcsProfileReview>()
                .eq(DcsProfileReview::getTenantId, tenantId)
                .eq(status != null && !status.isBlank(), DcsProfileReview::getStatus, status)
                .eq(userId != null, DcsProfileReview::getUserId, userId)
                .orderByDesc(DcsProfileReview::getCreateTime)));
    }

    /** 统计租户内待复核<b>用户</b>数(同人只算一个, 2026-08-23 口径改造, 大盘卡片使用)。 */
    public long countPending(String tenantId) {
        return TenantHelper.ignore(() -> reviewMapper.countPendingUsers(tenantId));
    }

    /**
     * 聚合分页: 每用户一行待复核画像(同人最新快照+积压快照数+首末入队时间), 按最新入队倒序。
     * <p>行数上限=租户用户数(入队语义改造后同人只挂一条), 内存分页可接受。</p>
     */
    public Page<ProfileReviewAggregateVo> pageAggregate(String tenantId, Long userId, PageQuery pageQuery) {
        List<ProfileReviewAggregateVo> all = TenantHelper.ignore(() -> reviewMapper.selectPendingAggregate(tenantId));
        // 按用户精确过滤(聚合行数=用户数, 全量取出后内存过滤足够)
        if (userId != null) {
            all = all.stream().filter(vo -> userId.equals(vo.getUserId())).toList();
        }
        // 三审修正P1-1: GET 不传分页参数时 pageNum/pageSize 为 null, 直接拆箱会 NPE; 与其它端点 build() 行为对齐兜底
        int pageNum = pageQuery.getPageNum() == null ? 1 : pageQuery.getPageNum();
        int pageSize = pageQuery.getPageSize() == null ? 10 : pageQuery.getPageSize();
        int from = (int) Math.min((long) (pageNum - 1) * pageSize, all.size());
        int to = (int) Math.min((long) from + pageSize, all.size());
        Page<ProfileReviewAggregateVo> page = new Page<>(pageNum, pageSize, all.size());
        page.setRecords(new ArrayList<>(all.subList(from, to)));
        return page;
    }

    /**
     * 按用户一次性复核其全部 PENDING 画像(CONFIRMED/DISMISSED), 2026-08-23 积压治理配套。
     *
     * <p>单条 UPDATE 全量置结论: 无部分失败态(要么全成要么异常), 与逐条 batchReview 语义互补。
     * 乐观性由 WHERE status='PENDING' 保证——并发已处理时 updated=0 抛业务异常。</p>
     *
     * @return 实际复核条数
     */
    public long reviewAllForUser(String tenantId, Long userId, Long operatorId, String conclusion, String reason) {
        String normalizedConclusion = policy.requireConclusion(conclusion);
        String normalizedReason = policy.requireReason(reason);
        int updated = TenantHelper.ignore(() -> reviewMapper.update(null,
            new LambdaUpdateWrapper<DcsProfileReview>()
                .eq(DcsProfileReview::getTenantId, tenantId)
                .eq(DcsProfileReview::getUserId, userId)
                .eq(DcsProfileReview::getStatus, "PENDING")
                .set(DcsProfileReview::getStatus, normalizedConclusion)
                .set(DcsProfileReview::getConclusion, normalizedConclusion)
                .set(DcsProfileReview::getReviewReason, normalizedReason)
                .set(DcsProfileReview::getReviewedBy, operatorId)
                .set(DcsProfileReview::getReviewedTime, new Date())));
        if (updated == 0) throw new ServiceException("该用户当前没有待复核画像");
        log.info("[ProfileReview] review-all-user tenant={} user={} operator={} conclusion={} rows={}",
            tenantId, userId, operatorId, normalizedConclusion, updated);
        return updated;
    }

    /**
     * 人工复核一条 PENDING 画像(CONFIRMED/DISMISSED); 乐观锁 status='PENDING' 防重复处理。
     * @param conclusion CONFIRMED 或 DISMISSED
     */
    public void review(String tenantId, Long reviewId, Long operatorId, String conclusion, String reason) {
        String normalizedConclusion = policy.requireConclusion(conclusion);
        String normalizedReason = policy.requireReason(reason);
        int updated = TenantHelper.ignore(() -> reviewMapper.update(null,
            new LambdaUpdateWrapper<DcsProfileReview>()
                .eq(DcsProfileReview::getTenantId, tenantId)
                .eq(DcsProfileReview::getReviewId, reviewId)
                .eq(DcsProfileReview::getStatus, "PENDING")
                .set(DcsProfileReview::getStatus, normalizedConclusion)
                .set(DcsProfileReview::getConclusion, normalizedConclusion)
                .set(DcsProfileReview::getReviewReason, normalizedReason)
                .set(DcsProfileReview::getReviewedBy, operatorId)
                .set(DcsProfileReview::getReviewedTime, new Date())));
        if (updated == 0) throw new ServiceException("画像复核不存在、已处理或不属于当前租户");
        log.info("[ProfileReview] reviewed tenant={} review={} operator={} conclusion={}",
            tenantId, reviewId, operatorId, normalizedConclusion);
    }

    /**
     * 批量复核画像风险（B 档新增）。
     *
     * <p>语义：循环单条 update，部分失败时聚合消息返回（如某条已被其他管理员处理过）。
     * 不使用事务，每条 update 内部已有 status='PENDING' 乐观锁保证幂等。
     *
     * @param tenantId   租户 ID
     * @param reviewIds  复核 ID 列表
     * @param operatorId 操作员 ID（留痕）
     * @param conclusion 复核结论：CONFIRMED / DISMISSED
     * @param reason     操作理由（留痕）
     * @return 聚合结果消息
     */
    public String batchReview(String tenantId, List<Long> reviewIds, Long operatorId, String conclusion, String reason) {
        if (reviewIds == null || reviewIds.isEmpty()) {
            throw new ServiceException("复核 ID 列表不能为空");
        }
        String normalizedConclusion = policy.requireConclusion(conclusion);
        String normalizedReason = policy.requireReason(reason);
        Date now = new Date();
        int success = 0;
        List<String> failures = new ArrayList<>();
        for (Long reviewId : reviewIds) {
            int updated = TenantHelper.ignore(() -> reviewMapper.update(null,
                new LambdaUpdateWrapper<DcsProfileReview>()
                    .eq(DcsProfileReview::getTenantId, tenantId)
                    .eq(DcsProfileReview::getReviewId, reviewId)
                    .eq(DcsProfileReview::getStatus, "PENDING")
                    .set(DcsProfileReview::getStatus, normalizedConclusion)
                    .set(DcsProfileReview::getConclusion, normalizedConclusion)
                    .set(DcsProfileReview::getReviewReason, normalizedReason)
                    .set(DcsProfileReview::getReviewedBy, operatorId)
                    .set(DcsProfileReview::getReviewedTime, now)));
            if (updated > 0) {
                success++;
            } else {
                failures.add("ID " + reviewId + ": 不存在、已处理或不属于当前租户");
            }
        }
        if (success == 0) {
            throw new ServiceException("批量复核全部失败：" + String.join("; ", failures));
        }
        log.info("[ProfileReview] batch-reviewed tenant={} operator={} conclusion={} success={} failures={}",
            tenantId, operatorId, normalizedConclusion, success, failures.size());
        if (failures.isEmpty()) {
            return success + " 条画像已复核";
        }
        return success + " 条处理成功，" + failures.size() + " 条失败：" + String.join("; ", failures);
    }
}
