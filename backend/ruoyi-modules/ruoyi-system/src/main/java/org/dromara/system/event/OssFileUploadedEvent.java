package org.dromara.system.event;

import lombok.Getter;
import lombok.Setter;

/**
 * OSS 文件上传完成事件（12b 文件审核中心 P1）
 *
 * <p>由 SysOssServiceImpl 在【sys_oss 插入同一事务内】同步发布（普通 ApplicationEvent，
 * 非 @Async 非 TransactionalEventListener）——dcs 侧 OssUploadedEventListener 同线程同事务
 * 建审核单；监听器抛异常则整个事务回滚，上传方补偿删除已上传对象（合同#1 fail-closed）。</p>
 *
 * <p>定义在 ruoyi-system（发布方），dcs（监听方）依赖 system，方向合法（合同#5）。</p>
 *
 * @author smartdock
 */
@Getter
public class OssFileUploadedEvent {

    /** 关联 sys_oss.ossId——发布时在事务内 buildResultEntity 插库后回填 */
    @Setter
    private Long ossId;

    /** 原始文件名 */
    private final String originalName;

    /** 文件后缀（含点，如 ".png"；无点为空串） */
    private final String suffix;

    /** 上传时浏览器提供的 Content-Type */
    private final String contentType;

    /** 文件字节数 */
    private final long fileSize;

    /** 上传来源埋点（仅展示，不作为免审依据，可伪造） */
    private final String bizSource;

    /** 关联任务ID（可空；Q2-A 任务审核人放行判定用） */
    private final Long missionId;

    /** 上传人 userId */
    private final Long uploaderId;

    /** 审核状态回写字段：监听器建单置 PENDING、直通置 SKIPPED；无监听器时保持 null（调用方按 SKIPPED 兜底） */
    @Setter
    private String reviewStatus;

    /**
     * file-guard 强制进审信号（批二，grill Q2=C 分层混合）：
     * 非空=深扫命中（宏/PDF JS/SVG 脚本）的风险说明，发布方 SysOssServiceImpl 从
     * FileGuardChecker.checkUpload 回填；监听器据此无视配置开关/管理员豁免/类目勾选直接建单。
     */
    @Setter
    private String guardReason;

    public OssFileUploadedEvent(String originalName, String suffix, String contentType,
                                long fileSize, String bizSource, Long missionId, Long uploaderId) {
        this.originalName = originalName;
        this.suffix = suffix;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.bizSource = bizSource;
        this.missionId = missionId;
        this.uploaderId = uploaderId;
    }
}
