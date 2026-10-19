package org.dromara.system.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件安全检测器 SPI（file-guard 批二，计划 FILE_DETECTION_GUARD_PLAN_20260918）
 *
 * <p>与 {@link FileReviewGate} 同构：定义在 ruoyi-system、实现在 ruoyi-file-security
 * （2026-09-24 文件安全家族模块化迁出 dcs, Maven 依赖方向 file-security → system）；
 * system 侧 {@code ObjectProvider<FileGuardChecker>}
 * 可空注入，file-security 模块未装配时所有上传零行为变化。</p>
 *
 * <p>调用时机：SysOssServiceImpl.upload 入口、对象落 MinIO【之前】——
 * 拒传（DENY，白名单外/魔数伪装）以 ServiceException 抛出且对象不落库；
 * 强制进审（REVIEW，宏/PDF JS/SVG 脚本等灰度信号）以返回值传递，
 * 经 OssFileUploadedEvent.guardReason 到达审核建单监听器（grill Q2=C 分层混合）。
 * 检测对全体用户一致（安全层≠内容审核层，超管/租管不豁免）。</p>
 *
 * @author smartdock
 */
public interface FileGuardChecker {

    /**
     * 上传前置安全检测（三层：扩展名白名单 → 魔数 → 内容深扫，超深扫上限只做前两层）。
     *
     * @param file 待上传文件
     * @return null=放行（含 OBSERVE 模式下的只记日志不拦截）；
     *         非空=强制进审的风险说明（ENFORCE 模式，展示在审核单风险标签上）
     * @throws org.dromara.common.core.exception.ServiceException 确定性危险拒传（ENFORCE 模式）
     */
    String checkUpload(MultipartFile file);
}
