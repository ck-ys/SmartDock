package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 导出审计行（批11 Q5）：每次分群成员 CSV 导出留一行，合规页展示。
 *
 * @author smartdock
 */
@Data
@TableName("profile_export_log")
public class ProfileExportLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "log_id", type = IdType.ASSIGN_ID)
    private Long logId;

    private String tenantId;

    private String source;

    private Long segmentId;

    private String segmentName;

    /** 导出行数(成员数) */
    private Long rowCount;

    /** 1=脱敏导出 0=明文导出(合规页高亮) */
    private String masked;

    /** CSV 内容 SHA-256(前 16 位), 事后对账"导出的到底是哪份文件" */
    private String fileHash;

    /** 关联触达审计行(profile_reach_batch.reach_id, channel=export) */
    private Long reachId;

    private Long exportBy;

    private Date exportAt;
}
