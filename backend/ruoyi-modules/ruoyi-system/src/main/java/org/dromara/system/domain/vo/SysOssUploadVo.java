package org.dromara.system.domain.vo;

import lombok.Data;

/**
 * 上传对象信息
 *
 * @author Michelle.Chung
 */
@Data
public class SysOssUploadVo {

    /**
     * URL地址
     */
    private String url;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 对象存储主键
     */
    private String ossId;

    /**
     * 审核状态（12b）：PENDING 待审 / SKIPPED 免审直通（关开关/管理员/类别未启用）
     */
    private String reviewStatus;

    /**
     * 是否待审（12b）：true=该文件已进入审核队列，前端据此打"审核中"标记
     */
    private Boolean needsReview;

}
