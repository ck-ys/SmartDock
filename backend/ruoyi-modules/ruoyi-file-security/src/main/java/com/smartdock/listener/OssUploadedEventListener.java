package com.smartdock.listener;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.domain.DcsFileReview;
import com.smartdock.domain.DcsFileReviewConfig;
import com.smartdock.enums.FileReviewCategory;
import com.smartdock.mapper.DcsFileReviewMapper;
import com.smartdock.service.IDcsFileReviewConfigService;
import org.dromara.system.event.OssFileUploadedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * OSS 上传完成事件监听器（批4 端口补齐重建，2026-10-18；三审 dsflash P0）。
 *
 * <p>旧实现随 com.smartdock 退役后 OssFileUploadedEvent 全仓零消费者——上传建单链
 * 整条断流（审核单永不创建、guard 强制进审信号被丢）。本监听器在 file-security 模块内
 * 重建：模块拥有审核单/配置/类目全部资产，且 file-security→system 依赖边已存在
 * （继承旧合同#5 方向），比落 admin 组合根更内聚。</p>
 *
 * <p>同步 @EventListener（非 @Async、非 TransactionalEventListener）——与发布方
 * SysOssServiceImpl 同线程同事务：这里建单失败即整个上传事务回滚，
 * 发布方补偿删除已上传的 OSS 对象（合同#1 fail-closed，不留孤儿对象）。</p>
 *
 * <p>判定序（严格照此，勿调换；自旧仓 OssUploadedEventListener 原样继承）：
 * ⓪guard 强制进审（优先级最高）；⓪.5 bizSource=FEEDBACK/ASSISTANT → EXEMPT 留痕单
 * （免人工审但闸门收紧可见面——唯一例外）；①配置关/查无行 → SKIPPED；
 * ②超管/租户管理员 → SKIPPED；③类别未启用 → SKIPPED；④建单 PENDING。除 ⓪.5 外
 * bizSource/missionId 只是埋点，任何分支不得据此免审（K3 盲审红线）。</p>
 *
 * @author smartdock
 */
@Component
@RequiredArgsConstructor
public class OssUploadedEventListener {

    private final IDcsFileReviewConfigService configService;
    private final DcsFileReviewMapper reviewMapper;

    /**
     * 上传建单：决定该文件是否进入待审队列并回写 reviewStatus。
     */
    @EventListener
    public void onUploaded(OssFileUploadedEvent e) {
        // ⓪ file-guard 强制进审：优先级最高——无视配置开关/管理员豁免/类目勾选
        //    （安全层保护下载者，与内容审核的豁免是两回事）
        if (StrUtil.isNotBlank(e.getGuardReason())) {
            DcsFileReview guardReview = buildReview(e, "PENDING");
            guardReview.setGuardReason(e.getGuardReason());
            reviewMapper.insert(guardReview);
            e.setReviewStatus("PENDING");
            return;
        }
        // ⓪.5 反馈截图/助手附件豁免：受众=本人+管理员，免人工审但必须留痕（EXEMPT）。
        //    bizSource 是客户端可伪造参数，纯 SKIPPED 零留痕会让伪造未审文件被再引用传播——
        //    闸门对陌生人拦（DcsFileReviewGateImpl），留痕字段事后可追查。
        if ("FEEDBACK".equals(e.getBizSource()) || "ASSISTANT".equals(e.getBizSource())) {
            reviewMapper.insert(buildReview(e, "EXEMPT"));
            e.setReviewStatus("SKIPPED");
            return;
        }
        // ① 功能整体关闭（查无行/开关!='1'）→ 不建单直通（关开关只影响新上传，存量单仍可审）
        DcsFileReviewConfig cfg = configService.getEffective();
        if (!"1".equals(cfg.getEnabled())) {
            e.setReviewStatus("SKIPPED");
            return;
        }
        // ② 管理员豁免：超管或租户管理员（超管 rolePermission 是 superadmin，二合一判定）
        if (LoginHelper.isSuperAdmin() || LoginHelper.isTenantAdmin()) {
            e.setReviewStatus("SKIPPED");
            return;
        }
        // ③ 类别未勾选 → 不建单，业务上与 APPROVED 等价（不落数据）
        FileReviewCategory cat = FileReviewCategory.classify(e.getSuffix());
        if (!StrUtil.split(cfg.getCategories(), ',').contains(cat.name())) {
            e.setReviewStatus("SKIPPED");
            return;
        }
        // ④ 建单：同事务 insert，UNIQUE(oss_id) 兜底防重复消费；异常向上抛=上传整体失败
        reviewMapper.insert(buildReview(e, "PENDING"));
        e.setReviewStatus("PENDING");
    }

    /** 组装审核单（昵称取不到时空串兜底——本监听器异常会把整笔上传回滚，判空防 NPE） */
    private DcsFileReview buildReview(OssFileUploadedEvent e, String status) {
        DcsFileReview review = new DcsFileReview();
        review.setOssId(e.getOssId());
        review.setFileName(e.getOriginalName());
        review.setFileCategory(FileReviewCategory.classify(e.getSuffix()).name());
        review.setFileSize(e.getFileSize());
        review.setBizSource(e.getBizSource());
        review.setMissionId(e.getMissionId());
        review.setUploaderId(e.getUploaderId());
        LoginUser loginUser = LoginHelper.getLoginUser();
        review.setUploaderName(loginUser != null ? loginUser.getNickname() : "");
        review.setStatus(status);
        return review;
    }
}
