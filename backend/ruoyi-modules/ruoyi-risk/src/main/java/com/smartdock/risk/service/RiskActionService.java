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
import com.smartdock.risk.config.DcsRolloutProperties;
import com.smartdock.risk.domain.DcsRiskAction;
import com.smartdock.risk.mapper.DcsRiskActionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/** Creates, expires, reviews and releases reversible L3/L4 security actions. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskActionService {
    /** 风险处置表 Mapper(可逆封禁/下线记录) */
    private final DcsRiskActionMapper actionMapper;
    /** 处置策略(等级→动作类型/时长映射) */
    private final RiskActionPolicy policy;

    /** 灰度配置(L3/L4 处置总开关) */
    @Autowired(required = false)
    private DcsRolloutProperties rolloutProperties;

    /**
     * 同步风险评估结果到可逆处置表(L3/L4 触发 TEMP_FREEZE / 强制下线等, 幂等 + 短路于人工解除)。
     * @return 新建或已存在的 ACTIVE 处置; 评估为空/开关关闭/被人工决策抑制时返回 null
     */
    public DcsRiskAction syncAssessment(String tenantId, RiskScoreService.RiskAssessment assessment, String reason) {
        // 短路1: L3/L4 处置总开关关闭 → 直接返回, 不做任何处置(灰度发布用)
        if (rolloutProperties != null && !rolloutProperties.isL34EnforcementEnabled()) return null;

        // 前置: 先把已过期的 ACTIVE 处置置为 EXPIRED(避免老处置占用配额)
        expireDue(tenantId);

        // 短路2: 评估结果为空或没有 userId(匿名访问) → 无法落到具体用户, 跳过
        if (assessment == null || assessment.userId() == null) return null;

        // 短路3: 当前风险等级不足以触发处置(policy.decision 返回 null = L0/L1/L2 无强制动作)
        RiskActionPolicy.Decision decision = policy.decision(assessment.level());
        if (decision == null) return null;

        // 短路4: 已有同类型 ACTIVE 处置 → 幂等返回, 不重复创建(避免一次风控触发多条封禁)
        DcsRiskAction existing = active(tenantId, assessment.userId(), decision.actionType());
        if (existing != null) return existing;

        // 短路5: 运维人工已决策(DISMISS) → 抑制本次自动处置, 避免与人工判断冲突
        if (manualDecisionSuppresses(tenantId, assessment, decision.actionType())) return null;

        // 正式创建处置记录: 设置所有字段(脱敏IP/雪花ID/过期时间=现在+duration)
        Date now = new Date();
        DcsRiskAction action = new DcsRiskAction();
        action.setActionId(IdUtil.getSnowflakeNextId());
        action.setTenantId(tenantId);
        action.setUserId(assessment.userId());
        action.setIpMasked(maskIp(assessment.ip()));
        action.setRiskLevel(assessment.level());
        action.setRiskScore(assessment.totalScore());
        action.setSourceTrack(assessment.sourceTrack());
        action.setActionType(decision.actionType());
        // triggerKey = userId:actionType, 配合唯一索引实现"同一用户同一类型只一条 ACTIVE"的幂等
        action.setTriggerKey(assessment.userId() + ":" + decision.actionType());
        action.setTriggerReason(reason == null || reason.isBlank()
            ? "风险评估达到 L" + assessment.level() : reason.trim());
        action.setStatus("ACTIVE");
        action.setStartTime(now);
        action.setExpireTime(Date.from(now.toInstant().plus(decision.duration())));
        action.setReviewStatus("PENDING");
        try {
            // ignore 模式插入: 风控处置跨租户可见(安全管理员需要看全租户), 不被租户隔离拦截
            TenantHelper.ignore(() -> actionMapper.insert(action));
        } catch (DuplicateKeyException ignored) {
            // 并发竞争: 另一线程已插入同 triggerKey 的 ACTIVE → 查回来返回(最终一致)
            return active(tenantId, assessment.userId(), decision.actionType());
        }
        log.warn("[RiskAction] created tenant={} action={} user={} level={} score={} expires={}",
            tenantId, action.getActionId(), action.getUserId(), action.getRiskLevel(), action.getRiskScore(), action.getExpireTime());
        return action;
    }

    /** 分页查询处置记录(支持按状态/类型/用户筛选); 自动先 expireDue 过期处置。 */
    public Page<DcsRiskAction> page(String tenantId, String status, String actionType, Long userId, PageQuery pageQuery) {
        expireDue(tenantId);
        return TenantHelper.ignore(() -> actionMapper.selectPage(pageQuery.build(),
            new LambdaQueryWrapper<DcsRiskAction>()
                .eq(DcsRiskAction::getTenantId, tenantId)
                .eq(status != null && !status.isBlank(), DcsRiskAction::getStatus, status)
                .eq(actionType != null && !actionType.isBlank(), DcsRiskAction::getActionType, actionType)
                .eq(userId != null, DcsRiskAction::getUserId, userId)
                .orderByDesc(DcsRiskAction::getStartTime)));
    }

    /** 查询单条处置详情(校验租户归属); 不存在或不属于该租户抛 ServiceException。 */
    public DcsRiskAction detail(String tenantId, Long actionId) {
        expireDue(tenantId);
        return requireAction(tenantId, actionId);
    }

    /** 统计租户内当前 ACTIVE 处置数(开关关闭返回 0); 自动先 expireDue。 */
    public long countActive(String tenantId) {
        if (!isEnforcementEnabled()) return 0L;
        expireDue(tenantId);
        Long count = TenantHelper.ignore(() -> actionMapper.selectCount(new LambdaQueryWrapper<DcsRiskAction>()
            .eq(DcsRiskAction::getTenantId, tenantId)
            .eq(DcsRiskAction::getStatus, "ACTIVE")));
        return count == null ? 0L : count;
    }

    /**
     * 解除一条 ACTIVE 处置(写 RELEASED + 留痕操作员/理由; 若复核仍 PENDING 同时 DISMISSED)。
     * 乐观锁: status='ACTIVE' 条件更新, updated=0 时抛"仅生效中的处置可以解除"。
     */
    @Transactional(rollbackFor = Exception.class)
    public void release(String tenantId, Long actionId, Long operatorId, String reason) {
        String normalized = policy.requireReason(reason);
        expireDue(tenantId);
        DcsRiskAction current = requireActionForUpdate(tenantId, actionId);
        if (!"ACTIVE".equals(current.getStatus())) throw new ServiceException("仅生效中的处置可以解除");
        Date now = new Date();
        LambdaUpdateWrapper<DcsRiskAction> update = new LambdaUpdateWrapper<DcsRiskAction>()
                .eq(DcsRiskAction::getTenantId, tenantId)
                .eq(DcsRiskAction::getActionId, actionId)
                .eq(DcsRiskAction::getStatus, "ACTIVE")
                .set(DcsRiskAction::getStatus, "RELEASED")
                .set(DcsRiskAction::getReleasedBy, operatorId)
                .set(DcsRiskAction::getReleasedTime, now)
                .set(DcsRiskAction::getReleaseReason, normalized);
        if ("PENDING".equals(current.getReviewStatus())) {
            update
                .set(DcsRiskAction::getReviewStatus, "DISMISSED")
                .set(DcsRiskAction::getReviewedBy, operatorId)
                .set(DcsRiskAction::getReviewedTime, now)
                .set(DcsRiskAction::getReviewConclusion, "DISMISSED")
                .set(DcsRiskAction::getReviewReason, normalized);
        }
        int updated = TenantHelper.ignore(() -> actionMapper.update(null, update));
        if (updated == 0) throw new ServiceException("仅生效中的处置可以解除");
        log.info("[RiskAction] released tenant={} action={} operator={} reasonLength={}",
            tenantId, actionId, operatorId, normalized.length());
    }

    /**
     * 人工复核一条 PENDING 处置(CONFIRMED 维持 / DISMISSED 自动释放); 乐观锁防重复复核。
     * @param conclusion CONFIRMED 或 DISMISSED
     */
    @Transactional(rollbackFor = Exception.class)
    public void review(String tenantId, Long actionId, Long operatorId, String conclusion, String reason) {
        String normalizedConclusion = policy.requireConclusion(conclusion);
        String normalizedReason = policy.requireReason(reason);
        expireDue(tenantId);
        DcsRiskAction current = requireActionForUpdate(tenantId, actionId);
        if (!"PENDING".equals(current.getReviewStatus())) throw new ServiceException("该处置已经完成复核");
        Date now = new Date();
        LambdaUpdateWrapper<DcsRiskAction> update = new LambdaUpdateWrapper<DcsRiskAction>()
                .eq(DcsRiskAction::getTenantId, tenantId)
                .eq(DcsRiskAction::getActionId, actionId)
                .eq(DcsRiskAction::getReviewStatus, "PENDING")
                .set(DcsRiskAction::getReviewStatus, normalizedConclusion)
                .set(DcsRiskAction::getReviewedBy, operatorId)
                .set(DcsRiskAction::getReviewedTime, now)
                .set(DcsRiskAction::getReviewConclusion, normalizedConclusion)
                .set(DcsRiskAction::getReviewReason, normalizedReason);
        if ("DISMISSED".equals(normalizedConclusion) && "ACTIVE".equals(current.getStatus())) {
            update
                .eq(DcsRiskAction::getStatus, "ACTIVE")
                .set(DcsRiskAction::getStatus, "RELEASED")
                .set(DcsRiskAction::getReleasedBy, operatorId)
                .set(DcsRiskAction::getReleasedTime, now)
                .set(DcsRiskAction::getReleaseReason, normalizedReason);
        }
        int updated = TenantHelper.ignore(() -> actionMapper.update(null, update));
        if (updated == 0) throw new ServiceException("该处置已经完成复核");
        log.info("[RiskAction] reviewed tenant={} action={} operator={} conclusion={}",
            tenantId, actionId, operatorId, normalizedConclusion);
    }

    /**
     * 批量解除处置（B 档新增）。
     *
     * <p>语义：循环调用单条 {@link #release}，部分失败时<strong>不回滚已成功的</strong>，
     * 而是聚合错误消息返回（业务场景：管理员批量解除 10 个处置，其中 1 个已自动到期，
     * 不应因这 1 条失败而把另外 9 条已成功的也回滚）。
     *
     * <p>不使用 {@code @Transactional}：每条 release 内部已有乐观锁（status='ACTIVE' 条件更新），
     * 失败的会抛 ServiceException 被 catch 收集。
     *
     * @param tenantId  租户 ID
     * @param actionIds 处置 ID 列表
     * @param operatorId 操作员 ID（留痕）
     * @param reason    操作理由（留痕）
     * @return 聚合结果消息，如 "3 条处理成功"；全部失败时抛 ServiceException
     */
    public String batchRelease(String tenantId, List<Long> actionIds, Long operatorId, String reason) {
        if (actionIds == null || actionIds.isEmpty()) {
            throw new ServiceException("处置 ID 列表不能为空");
        }
        int success = 0;
        List<String> failures = new ArrayList<>();
        for (Long actionId : actionIds) {
            try {
                release(tenantId, actionId, operatorId, reason);
                success++;
            } catch (ServiceException e) {
                // 收集失败原因（如"处置已自动到期"），不中断后续
                failures.add("ID " + actionId + ": " + e.getMessage());
            }
        }
        if (success == 0) {
            throw new ServiceException("批量解除全部失败：" + String.join("; ", failures));
        }
        log.info("[RiskAction] batch-released tenant={} operator={} success={} failures={}",
            tenantId, operatorId, success, failures.size());
        if (failures.isEmpty()) {
            return success + " 条处置已解除";
        }
        return success + " 条处理成功，" + failures.size() + " 条失败：" + String.join("; ", failures);
    }

    /**
     * 批量复核处置（B 档新增）。语义同 {@link #batchRelease}，部分失败聚合消息。
     *
     * @param tenantId   租户 ID
     * @param actionIds  处置 ID 列表
     * @param operatorId 操作员 ID
     * @param conclusion 复核结论：CONFIRMED / DISMISSED
     * @param reason     操作理由
     */
    public String batchReview(String tenantId, List<Long> actionIds, Long operatorId, String conclusion, String reason) {
        if (actionIds == null || actionIds.isEmpty()) {
            throw new ServiceException("处置 ID 列表不能为空");
        }
        int success = 0;
        List<String> failures = new ArrayList<>();
        for (Long actionId : actionIds) {
            try {
                review(tenantId, actionId, operatorId, conclusion, reason);
                success++;
            } catch (ServiceException e) {
                failures.add("ID " + actionId + ": " + e.getMessage());
            }
        }
        if (success == 0) {
            throw new ServiceException("批量复核全部失败：" + String.join("; ", failures));
        }
        log.info("[RiskAction] batch-reviewed tenant={} operator={} conclusion={} success={} failures={}",
            tenantId, operatorId, conclusion, success, failures.size());
        if (failures.isEmpty()) {
            return success + " 条处置已复核";
        }
        return success + " 条处理成功，" + failures.size() + " 条失败：" + String.join("; ", failures);
    }

    /** 断言用户未被临时安全冻结(若存在 ACTIVE TEMP_FREEZE 抛 ServiceException); 开关关闭直接放行。 */
    public void assertNotFrozen(String tenantId, Long userId) {
        if (!isEnforcementEnabled()) return;
        expireDue(tenantId);
        if (userId != null && active(tenantId, userId, "TEMP_FREEZE") != null) {
            throw new ServiceException("账户处于临时安全冻结，请等待自动到期或联系管理员复核解除");
        }
    }

    /** L3/L4 强制开关是否开启(未配置 rollout 时默认 true)。 */
    public boolean isEnforcementEnabled() {
        return rolloutProperties == null || rolloutProperties.isL34EnforcementEnabled();
    }

    private DcsRiskAction active(String tenantId, Long userId, String actionType) {
        Date now = new Date();
        return TenantHelper.ignore(() -> actionMapper.selectOne(new LambdaQueryWrapper<DcsRiskAction>()
            .eq(DcsRiskAction::getTenantId, tenantId)
            .eq(DcsRiskAction::getUserId, userId)
            .eq(DcsRiskAction::getActionType, actionType)
            .eq(DcsRiskAction::getStatus, "ACTIVE")
            .gt(DcsRiskAction::getExpireTime, now)
            .last("LIMIT 1")));
    }

    private boolean manualDecisionSuppresses(String tenantId, RiskScoreService.RiskAssessment assessment,
                                             String actionType) {
        Date now = new Date();
        DcsRiskAction decision = TenantHelper.ignore(() -> actionMapper.selectOne(
            new LambdaQueryWrapper<DcsRiskAction>()
                .eq(DcsRiskAction::getTenantId, tenantId)
                .eq(DcsRiskAction::getUserId, assessment.userId())
                .eq(DcsRiskAction::getActionType, actionType)
                .gt(DcsRiskAction::getExpireTime, now)
                .and(q -> q.eq(DcsRiskAction::getStatus, "RELEASED")
                    .or().eq(DcsRiskAction::getReviewStatus, "DISMISSED"))
                .orderByDesc(DcsRiskAction::getReleasedTime)
                .last("LIMIT 1")));
        if (decision == null || !Objects.equals(decision.getSourceTrack(), assessment.sourceTrack())) return false;
        boolean suppressed = decision.getRiskScore() != null && decision.getRiskScore() >= assessment.totalScore();
        if (suppressed) {
            log.info("[RiskAction] suppressed by manual decision tenant={} action={} user={} type={} score={}",
                tenantId, decision.getActionId(), assessment.userId(), actionType, assessment.totalScore());
        }
        return suppressed;
    }

    private void expireDue(String tenantId) {
        Date now = new Date();
        int expired = TenantHelper.ignore(() -> actionMapper.update(null,
            new LambdaUpdateWrapper<DcsRiskAction>()
                .eq(DcsRiskAction::getTenantId, tenantId)
                .eq(DcsRiskAction::getStatus, "ACTIVE")
                .le(DcsRiskAction::getExpireTime, now)
                .set(DcsRiskAction::getStatus, "EXPIRED")));
        if (expired > 0) log.info("[RiskAction] expired tenant={} count={}", tenantId, expired);
    }

    private DcsRiskAction requireAction(String tenantId, Long actionId) {
        if (actionId == null) throw new ServiceException("处置ID不能为空");
        DcsRiskAction action = TenantHelper.ignore(() -> actionMapper.selectOne(new LambdaQueryWrapper<DcsRiskAction>()
            .eq(DcsRiskAction::getTenantId, tenantId)
            .eq(DcsRiskAction::getActionId, actionId)
            .last("LIMIT 1")));
        if (action == null) throw new ServiceException("处置不存在或不属于当前租户");
        return action;
    }

    private DcsRiskAction requireActionForUpdate(String tenantId, Long actionId) {
        if (actionId == null) throw new ServiceException("处置ID不能为空");
        DcsRiskAction action = TenantHelper.ignore(() -> actionMapper.selectOne(new LambdaQueryWrapper<DcsRiskAction>()
            .eq(DcsRiskAction::getTenantId, tenantId)
            .eq(DcsRiskAction::getActionId, actionId)
            .last("LIMIT 1 FOR UPDATE")));
        if (action == null) throw new ServiceException("处置不存在或不属于当前租户");
        return action;
    }

    private String maskIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        int lastDot = ip.lastIndexOf('.');
        if (lastDot > 0) return ip.substring(0, lastDot + 1) + "*";
        if (ip.contains(":")) return ip.substring(0, Math.min(ip.length(), 8)) + "::*";
        return "*";
    }
}
