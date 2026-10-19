package com.smartdock.service.support;

/**
 * 任务审核人存在性探针（模块化 2026-09-24 端口反转）。
 *
 * <p>文件审核闸门 {@code DcsFileReviewGateImpl} 判定"当前用户是否该任务在审审核人"
 * 需要查 dcs 核心的 dcs_review_task 表; file-security 不依赖 ruoyi-dcs(依赖方向单向),
 * 故本模块只声明端口, dcs 侧 {@code MissionReviewProbeImpl} 落地查询, admin 聚合时 Spring 注入。</p>
 *
 * @author smartdock
 */
public interface MissionReviewProbe {

    /**
     * 指定用户是否为指定任务【在审】(PENDING)审核人。
     *
     * @param missionId 任务ID(可为空, 空直接 false)
     * @param userId    用户ID(可为空, 空直接 false)
     */
    boolean hasPendingReviewTask(Long missionId, Long userId);
}
