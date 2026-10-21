package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 模型配置版本存档（批9 Q6-A）：发布时把生效配置留档。
 * 历史可 diff、可一键回滚（重激活旧版 = 把存档配置写回模型再发布）。
 *
 * @author smartdock
 */
@Data
@TableName("profile_model_config_snapshot")
public class ProfileModelConfigSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "config_snapshot_id", type = IdType.ASSIGN_ID)
    private Long configSnapshotId;

    private String tenantId;

    private String source;

    private Long modelId;

    private String modelCode;

    private String modelVersion;

    /** 批10 R5: 模型类型(RFM/GENERIC)随档保存, 回滚时恢复——否则回滚后类型跟当前态走 */
    private String modelType;

    /** jsonb 字符串, 写入走 ::jsonb */
    private String config;

    private BigDecimal scoreMax;

    private Long publishedBy;

    private Date publishedAt;

    private Date createTime;

    private String delFlag;
}
