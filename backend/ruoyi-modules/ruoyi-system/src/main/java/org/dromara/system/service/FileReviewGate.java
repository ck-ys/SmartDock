package org.dromara.system.service;

import java.util.List;
import java.util.Map;

/**
 * 文件审核闸门接口（12b 文件审核中心 P1）
 *
 * <p>定义在 ruoyi-system、实现在 ruoyi-file-security（2026-09-24 文件安全家族模块化迁出 dcs,
 * Maven 依赖方向 file-security → system，合同#5）。
 * system 侧通过 {@code ObjectProvider<FileReviewGate>} 可空注入：file-security 模块未装配时
 * 所有调用点按"无审核"直通，行为与功能上线前完全一致（合同#14 默认关）。</p>
 *
 * <p>实现方注意：判定只查 dcs_file_review（UNIQUE(oss_id) 索引），
 * 严禁把判定结果写入 SYS_OSS 30 天缓存（合同#3）。</p>
 *
 * @author smartdock
 */
public interface FileReviewGate {

    /**
     * 单个 ossId 对当前用户是否可见（preview/download 拦截用）
     *
     * @param ossId 文件在 sys_oss 的主键
     * @return true=放行（无单/已通过/命中放行矩阵）；false=拦截
     */
    boolean isVisibleToCurrentUser(Long ossId);

    /**
     * 批量取被拦截文件的信息（listByIds 占位标记用，一次 IN 查单免 N+1）
     *
     * @param ossIds 待判定的一组 ossId
     * @return 仅含被拦截项：ossId → 拦截信息（状态+理由）；全部放行时返回空 Map
     */
    Map<Long, ReviewBlock> blockedInfoMap(List<Long> ossIds);

    /**
     * 批量取【当前用户本人上传】且仍在 PENDING/REJECTED 的审核状态（12b 上传人自见标记）。
     *
     * <p>与 blockedInfoMap 的分工：本人文件按放行矩阵对本人可见（url 不置空），
     * 但前端需在其填写表单里打"审核中/已驳回"标记——刷新页面后标记来源即本方法。
     * 不看开关：关开关后存量待审单仍需让上传人看见自己的文件状态（Q4-A）。</p>
     *
     * @param ossIds 待标记的一组 ossId
     * @return 仅含本人上传且非 APPROVED 的项：ossId → 状态信息（REJECTED 带理由）
     */
    Map<Long, ReviewBlock> uploaderStatusMap(List<Long> ossIds);

    /**
     * oss 文件被删除后的级联闭单：仍在 PENDING 的审核单置 REJECTED（reason='源文件已删除'），
     * 防止"文件已删但待审单还在"的幽灵单污染审核队列（合同#11）
     *
     * @param ossIds 被删除的 ossId 集合
     */
    void onOssDeleted(List<Long> ossIds);

    /**
     * 拦截信息载体
     *
     * @param status 审核状态：PENDING 待审 / REJECTED 已驳回
     * @param reason 拦截原因说明（驳回时为驳回理由）
     */
    record ReviewBlock(String status, String reason) {
    }
}
