package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 分数快照 profile_score_snapshot(行式追加, 前链可追溯)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_score_snapshot")
public class ProfileScoreSnapshot extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "snapshot_id", type = IdType.ASSIGN_ID)
    private Long snapshotId;

    private String tenantId;

    private String source;

    private String subjectType;

    private String subjectId;

    private BigDecimal totalScore;

    /** {dimCode:{score,metric,sampleCount,evidence,weight,tags}} */
    private String dimensionDetail;

    private String ruleVersion;

    /** 前一条快照(链尾回溯) */
    private Long prevSnapshotId;

    private Date calcTime;

    /**
     * 是否「无有效维度」的占位分快照: 引擎之外的第二入口(复核 scan 手动扫描等)不依赖引擎
     * 内部分支也能识别, 占位 50 分不入复核。字符串包含法判键——免 JsonUtils 解析
     * (其类初始化依赖 Spring, 纯单测不可用), 键名即前端契约(_noDimensions)。
     */
    public boolean isPlaceholderScore() {
        return dimensionDetail != null
            && dimensionDetail.contains("\"" + ProfileScoreEngine.UNCONFIGURED_FLAG + "\"");
    }
}
