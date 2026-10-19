package com.smartdock.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.core.utils.DateUtils;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.helper.DataPermissionHelper;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.DcsFileReview;
import com.smartdock.domain.bo.DcsFileReviewQueryBo;
import com.smartdock.domain.vo.DcsFileReviewVo;
import com.smartdock.mapper.DcsFileReviewMapper;
import com.smartdock.risk.domain.bo.RiskEventCommand;
import com.smartdock.risk.service.RiskEventCollector;
import com.smartdock.service.IDcsFileReviewService;
import com.smartdock.service.support.FileReviewNoticeEvent;
import org.dromara.system.service.ISysUserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件审核单服务实现（12b）
 *
 * <p>决策两口径（合同#9）：approve/reject 均 @Transactional + CAS 条件 UPDATE
 * （WHERE status='PENDING'，影响 0 行=已被他人处理）；驳回通知与 CAS 同事务发布
 * （BEFORE_COMMIT 落库，回滚不留通知）。头像回写只在通过那一刻（合同#4）。</p>
 *
 * @author smartdock
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DcsFileReviewServiceImpl implements IDcsFileReviewService {

    private final DcsFileReviewMapper baseMapper;
    /** Spring 事件发布器(驳回通知走 FileReviewNoticeEvent 域事件, dcs 侧翻译, 模块化 2026-09-24) */
    private final ApplicationEventPublisher eventPublisher;
    /** 头像审核通过回写 sys_user.avatar（dcs→system 方向合法，合同#4） */
    private final ISysUserService sysUserService;
    /** 风险事件采集器（文件审核驳回记 FILE_REJECTED，风控2.0批2 文件联动） */
    private final RiskEventCollector riskEventCollector;

    /** 合法状态集（列表查询防注入式乱传） */
    private static final Set<String> VALID_STATUS = Set.of("PENDING", "APPROVED", "REJECTED");

    /** 风险事件类型：文件审核驳回（规则 seed 批1已落库 weight=3） */
    private static final String EVENT_TYPE_FILE_REJECTED = "FILE_REJECTED";
    /** 风险事件来源：文件审核 */
    private static final String RISK_SOURCE_FILE = "FILE";

    @Override
    public TableDataInfo<DcsFileReviewVo> queryList(DcsFileReviewQueryBo bo, PageQuery pageQuery) {
        // null 也要拦: Set.contains(null) 直接 NPE, 缺参应给可读错误(批4 E2E 回归露馅)
        if (bo.getStatus() == null || !VALID_STATUS.contains(bo.getStatus())) {
            throw new ServiceException("审核状态参数不合法");
        }
        LambdaQueryWrapper<DcsFileReview> lqw = buildQueryWrapper(bo);
        Page<DcsFileReviewVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    private LambdaQueryWrapper<DcsFileReview> buildQueryWrapper(DcsFileReviewQueryBo bo) {
        Map<String, Object> params = bo.getParams();
        Date beginTime = DateUtils.parseDate(params == null ? null : params.get("beginTime"));
        Date endTime = DateUtils.parseDate(params == null ? null : params.get("endTime"));
        LambdaQueryWrapper<DcsFileReview> lqw = Wrappers.lambdaQuery();
        lqw.eq(DcsFileReview::getStatus, bo.getStatus());
        lqw.eq(StringUtils.isNotBlank(bo.getCategory()), DcsFileReview::getFileCategory, bo.getCategory());
        lqw.eq(StringUtils.isNotBlank(bo.getBizSource()), DcsFileReview::getBizSource, bo.getBizSource());
        lqw.like(StringUtils.isNotBlank(bo.getUploaderName()), DcsFileReview::getUploaderName, bo.getUploaderName());
        lqw.between(beginTime != null && endTime != null, DcsFileReview::getCreateTime, beginTime, endTime);
        // 待审优先看最新的；已审按审结时间倒序（review_time 同序兜底 createTime）
        if ("PENDING".equals(bo.getStatus())) {
            lqw.orderByDesc(DcsFileReview::getCreateTime);
        } else {
            lqw.orderByDesc(DcsFileReview::getReviewTime).orderByDesc(DcsFileReview::getCreateTime);
        }
        return lqw;
    }

    @Override
    public long pendingCount() {
        return baseMapper.countPending(TenantHelper.getTenantId());
    }

    @Override
    public DcsFileReviewVo queryById(Long reviewId) {
        return baseMapper.selectVoById(reviewId);
    }

    /**
     * 批量通过：逐条 CAS（Q5-A），单条失败不影响整批，结果分桶返回
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApproveResult approve(List<Long> reviewIds) {
        if (CollUtil.isEmpty(reviewIds)) {
            throw new ServiceException("请选择要通过的文件");
        }
        Long reviewerId = LoginHelper.getUserId();
        LoginUser loginUser = LoginHelper.getLoginUser();
        String reviewerName = loginUser != null ? loginUser.getNickname() : "";
        // 审查修复：与 MP 租户拦截器同源取租户（动态租户优先）——超管切租户审核时
        // 若押 token 归属租户，CAS 恒 0 行误报"已被处理"（七席审查 P1）
        String tenantId = TenantHelper.getTenantId();
        List<Long> success = new ArrayList<>();
        List<Long> conflict = new ArrayList<>();
        for (Long reviewId : reviewIds) {
            DcsFileReview review = baseMapper.selectById(reviewId);
            // 单已不存在（含它表删除级联闭单后的残留引用）或 CAS 0 行 → 冲突桶
            if (review == null
                || baseMapper.markApproved(tenantId, reviewId, reviewerId, reviewerName) == 0) {
                conflict.add(reviewId);
                continue;
            }
            success.add(reviewId);
            // 头像单：通过那一刻才回写生效（驳回/待审都不动旧头像，合同#4）
            if ("AVATAR".equals(review.getBizSource())) {
                // 回写未命中（用户已删等）不阻断整批——审核单已 CAS 生效不可重试，
                // 只能留痕人工核对（七席审查：静默分叉比失败更难发现）
                boolean avatarWritten = DataPermissionHelper.ignore(
                    () -> sysUserService.updateUserAvatar(review.getUploaderId(), review.getOssId()));
                if (!avatarWritten) {
                    log.error("[fileReview] 头像审核通过但回写未命中：userId={} ossId={}，请人工核对 sys_user.avatar",
                        review.getUploaderId(), review.getOssId());
                }
            }
        }
        if (success.isEmpty()) {
            // 全冲突=没有任何生效变更，整体回滚以免空事务落审计
            throw new ServiceException("选中的文件均已被处理，请刷新列表");
        }
        return new ApproveResult(success, conflict);
    }

    /**
     * 驳回单条：CAS + 同事务通知；头像驳回不动旧头像
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long reviewId, String reason) {
        DcsFileReview review = baseMapper.selectById(reviewId);
        if (review == null) {
            throw new ServiceException("审核单不存在");
        }
        LoginUser rejectLoginUser = LoginHelper.getLoginUser();
        int rows = baseMapper.markRejected(TenantHelper.getTenantId(), reviewId,
            LoginHelper.getUserId(), rejectLoginUser != null ? rejectLoginUser.getNickname() : "", reason);
        if (rows == 0) {
            throw new ServiceException("该单已被处理，请刷新列表");
        }
        // 与 CAS 同事务：驳回回滚则通知不留（合同#9）；域事件同步投递给 dcs 侧翻译进通知管道
        eventPublisher.publishEvent(new FileReviewNoticeEvent(review, reason));
        // 风控联动（2026-09-22 风控2.0批2）：驳回提交成功后记 FILE_REJECTED（见方法注释）
        recordFileRejectedAfterCommit(review);
    }

    /**
     * 驳回提交成功后记 FILE_REJECTED 风险事件（风控2.0批2 文件联动）。
     *
     * <p>归责对象=上传人（userId=uploaderId，refId=审核单ID）；幂等键
     * {@code file:review:{reviewId}}——同单只记一次。挂 afterCommit 而非事务内直发：
     * record 是 @Async 不参与事务，若驳回回滚（如通知发布失败）而事件已先发，
     * 幂等键会把下一次真实驳回吞成"重复"，留下永不成立的幻影事件。</p>
     */
    private void recordFileRejectedAfterCommit(DcsFileReview review) {
        String ip = null;
        String userAgent = null;
        try {
            HttpServletRequest request = ServletUtils.getRequest();
            if (request != null) {
                ip = ServletUtils.getClientIP();
                userAgent = request.getHeader("User-Agent");
            }
        } catch (Exception ignore) {
            // 非 web 上下文（定时/级联触发）取不到请求信息，ip/ua 置空不阻断
        }
        RiskEventCommand command = new RiskEventCommand(
            TenantHelper.getTenantId(),
            EVENT_TYPE_FILE_REJECTED,
            RISK_SOURCE_FILE,
            review.getUploaderId(),
            ip,
            userAgent,
            review.getReviewId(),
            "文件审核驳回:" + StringUtils.blankToDefault(review.getFileName(), "文件"),
            "file:review:" + review.getReviewId(),
            new Date(),
            null
        );
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 终审P2-5: 事务已提交, 此处任何异常(如 @Async 线程池饱和拒绝)都会传播回
                    // 已成功的驳回调用方造成"驳回成功却报错"——风控事件属旁路记录, 失败只留痕不炸主流程
                    try {
                        riskEventCollector.record(command);
                    } catch (Exception ex) {
                        log.warn("文件驳回风控事件提交失败(幂等键 {}): {}", "file:review:" + review.getReviewId(), ex.getMessage());
                    }
                }
            });
        } else {
            // 无事务上下文（单测/非事务调用方）直接发——没有可回滚的驳回，不存在幻影
            riskEventCollector.record(command);
        }
    }
}
