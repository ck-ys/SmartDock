package com.smartdock.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.smartdock.domain.DcsFileGuardLog;

import java.io.Serial;
import java.util.Date;

/**
 * 文件安全检测命中记录视图对象（「检测记录」页签 / 设置弹窗概览条，Q4=A）
 *
 * @author smartdock
 */
@Data
@AutoMapper(target = DcsFileGuardLog.class)
public class DcsFileGuardLogVo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 记录ID */
    private Long logId;

    /** 上传人 userId */
    private Long userId;

    /** 上传人姓名 */
    private String userName;

    /** 文件名 */
    private String fileName;

    /** 文件字节数 */
    private Long fileSize;

    /** 命中规则码 */
    private String ruleCode;

    /** 命中详情（人话说明） */
    private String detail;

    /** 命中时的检测模式 */
    private String mode;

    /** 处置结果：DENIED / REVIEWED / OBSERVED */
    private String disposition;

    /** 命中时间 */
    private Date createTime;
}
