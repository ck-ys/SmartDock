package com.smartdock.service.support;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.domain.DcsFileReview;
import com.smartdock.mapper.DcsFileReviewMapper;
import com.smartdock.service.IDcsFileReviewConfigService;
import org.dromara.system.service.FileReviewGate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件审核闸门实现（12b 文件审核中心 P1）
 *
 * <p>实现 ruoyi-system 定义的 {@link FileReviewGate}，供 preview/download 拦截、
 * listByIds 占位标记、oss 删除级联闭单三处调用（合同#3/#11）。</p>
 *
 * <p>判定序（合同#14 短路序）：①配置关/查无行 → 全放行（不查单）；②查单
 * （UNIQUE(oss_id) 索引/批量 IN，租户拦截器天然隔离，严禁进 SYS_OSS 缓存）；
 * ③对 PENDING/REJECTED/EXEMPT 单逐条走放行矩阵纯函数 {@link #allow}。
 * EXEMPT=反馈截图免审留痕单（三件套 ⓪.5），矩阵内受众收紧为仅上传人+管理员。</p>
 *
 * <p>onOssDeleted 不看开关：关开关只影响新上传与拦截（Q4-A），删除级联闭单
 * 属数据卫生，存量待审单照样要闭。</p>
 *
 * @author smartdock
 */
@Service
@RequiredArgsConstructor
public class DcsFileReviewGateImpl implements FileReviewGate {

    private final DcsFileReviewMapper reviewMapper;
    /** 任务审核人存在性探针(端口反转, dcs 侧 MissionReviewProbeImpl 落地, 模块化 2026-09-24) */
    private final MissionReviewProbe missionReviewProbe;
    private final IDcsFileReviewConfigService configService;

    /**
     * 单点判定：preview/download 拦截入口
     */
    @Override
    public boolean isVisibleToCurrentUser(Long ossId) {
        // ① 功能整体关闭 → 直通（存量/AUTO 天然放行，正向判定"有 PENDING/REJECTED 单才拦"）
        if (!isEnabled()) {
            return true;
        }
        // ② 查单：无单或已通过 → 放行
        DcsFileReview review = reviewMapper.selectByOssId(ossId);
        if (review == null || "APPROVED".equals(review.getStatus())) {
            return true;
        }
        // ③ 放行矩阵
        return decide(review, currentUser());
    }

    /**
     * 批量判定：listByIds 占位标记入口，一次 IN 查单免 N+1
     */
    @Override
    public Map<Long, ReviewBlock> blockedInfoMap(List<Long> ossIds) {
        if (CollUtil.isEmpty(ossIds) || !isEnabled()) {
            return Map.of();
        }
        List<DcsFileReview> reviews = reviewMapper.selectByOssIds(ossIds);
        CurrentUser cu = currentUser();
        Map<Long, ReviewBlock> blocked = new HashMap<>();
        for (DcsFileReview review : reviews) {
            String status = review.getStatus();
            // EXEMPT（反馈截图免审留痕单）同进拦截判定：对非受众拦（K3 P1-1 收紧，见 allow 矩阵）
            if (!"PENDING".equals(status) && !"REJECTED".equals(status) && !"EXEMPT".equals(status)) {
                continue;
            }
            if (!decide(review, cu)) {
                blocked.put(review.getOssId(), new ReviewBlock(status, blockReason(review)));
            }
        }
        return blocked;
    }

    /**
     * oss 删除级联闭单：仍在 PENDING 的单置 REJECTED（reason='源文件已删除'），防幽灵待审单
     */
    @Override
    public void onOssDeleted(List<Long> ossIds) {
        if (CollUtil.isEmpty(ossIds)) {
            return;
        }
        reviewMapper.closePendingByOssIds(ossIds, "源文件已删除", LoginHelper.getUserId());
    }

    /**
     * 上传人自见标记：本人上传且 PENDING/REJECTED 的单（本人按矩阵可见，仅补状态标记）。
     * 不看开关——关开关后存量待审单仍要让上传人看见自己文件的状态（Q4-A）。
     */
    @Override
    public Map<Long, ReviewBlock> uploaderStatusMap(List<Long> ossIds) {
        if (CollUtil.isEmpty(ossIds)) {
            return Map.of();
        }
        Long uid = LoginHelper.getUserId();
        if (uid == null) {
            return Map.of();
        }
        List<DcsFileReview> reviews = reviewMapper.selectByOssIds(ossIds);
        Map<Long, ReviewBlock> owned = new HashMap<>();
        for (DcsFileReview review : reviews) {
            String status = review.getStatus();
            if (uid.equals(review.getUploaderId())
                && ("PENDING".equals(status) || "REJECTED".equals(status))) {
                owned.put(review.getOssId(), new ReviewBlock(status, blockReason(review)));
            }
        }
        return owned;
    }

    private boolean isEnabled() {
        return "1".equals(configService.getEffective().getEnabled());
    }

    /**
     * 两段式判定：先按"非任务审核人"跑矩阵（省一次 dcs_review_task 查询），
     * 仅当前几条未命中且单带 missionId 时才回查任务审核人再判一次
     */
    private boolean decide(DcsFileReview review, CurrentUser cu) {
        ReviewContext ctx = new ReviewContext(review.getStatus(), review.getUploaderId(), review.getMissionId());
        if (allow(ctx, cu, false)) {
            return true;
        }
        if (ctx.missionId() == null || cu.userId() == null) {
            return false;
        }
        return allow(ctx, cu, isMissionReviewer(ctx.missionId(), cu.userId()));
    }

    /**
     * Q2-A：当前用户是否该任务【在审】审核人（端口反转：dcs 侧 MissionReviewProbeImpl 查 dcs_review_task，
     * 存在性查询走 reviewer/mission 现有索引；拆分前为直接注入 DcsReviewTaskMapper，逻辑不变）
     */
    private boolean isMissionReviewer(Long missionId, Long userId) {
        return missionReviewProbe.hasPendingReviewTask(missionId, userId);
    }

    /**
     * 组装当前用户上下文（放行矩阵第 3-5 条的判定输入）
     */
    private CurrentUser currentUser() {
        return new CurrentUser(LoginHelper.getUserId(),
            LoginHelper.isSuperAdmin(), LoginHelper.isTenantAdmin(),
            StpUtil.hasPermission("dcs:fileReview:approve"));
    }

    /**
     * 拦截提示语：已驳回带理由；EXEMPT 反馈截图专用语；其余统一"审核中"
     */
    private String blockReason(DcsFileReview review) {
        if ("EXEMPT".equals(review.getStatus())) {
            return "反馈截图，仅反馈人与管理员可见";
        }
        if ("REJECTED".equals(review.getStatus()) && StrUtil.isNotBlank(review.getRejectReason())) {
            return review.getRejectReason();
        }
        return "审核中";
    }

    // ===== 放行矩阵纯函数区（单测直接调用，矩阵序=计划头部表格） =====

    /** 审核单上下文：状态 + 上传人 + 关联任务 */
    public record ReviewContext(String status, Long uploaderId, Long missionId) {
    }

    /** 当前用户上下文：身份 + 权限判定结果 */
    public record CurrentUser(Long userId, boolean isSuperAdmin, boolean isTenantAdmin, boolean hasApprovePerm) {
    }

    /**
     * 放行矩阵纯函数：对存在 PENDING/REJECTED/EXEMPT 单的文件，按序判定（任一命中放行）
     * <ol>
     *   <li>无单或 APPROVED → 放行（本函数入参即"有单"场景，此条兜底防御）</li>
     *   <li>uploader_id == 当前用户 → 放行（本人可见自己的待审/被驳回文件）</li>
     *   <li>超管/租户管理员 → 放行</li>
     *   <li>EXEMPT（反馈截图免审留痕单）到此为止：仅上述受众放行——file_reviewer 权限与
     *       任务审核人不在反馈截图受众内，防伪造 FEEDBACK 免审文件被再引用到业务面传播
     *       （K3 深审 P1-1 收紧）</li>
     *   <li>hasPermission(dcs:fileReview:approve) → 放行（file_reviewer）</li>
     *   <li>单的 missionId 非空且当前用户是该任务在审审核人 → 放行（Q2-A）</li>
     *   <li>其余 → 拦</li>
     * </ol>
     */
    public static boolean allow(ReviewContext review, CurrentUser cu, boolean isMissionReviewer) {
        if (review == null || "APPROVED".equals(review.status())) {
            return true;
        }
        if (cu.userId() != null && cu.userId().equals(review.uploaderId())) {
            return true;
        }
        if (cu.isSuperAdmin() || cu.isTenantAdmin()) {
            return true;
        }
        if ("EXEMPT".equals(review.status())) {
            return false;
        }
        if (cu.hasApprovePerm()) {
            return true;
        }
        return review.missionId() != null && isMissionReviewer;
    }
}
