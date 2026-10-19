package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * 画像引擎运行配置 profile_engine_config(自动重算/复核阈值)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_engine_config")
public class ProfileEngineConfig extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "config_id", type = IdType.ASSIGN_ID)
    private Long configId;

    private String tenantId;

    private String source;

    /** 1 = 事件写入后自动重算该主体 */
    private String autoRecalc;

    /** 1 = 低分自动进复核队列 */
    private String reviewEnabled;

    /** 低于该分数入复核队列 */
    private BigDecimal reviewThreshold;

    /** 自动重算合并窗口(毫秒) */
    private Integer debounceMs;

    private String remark;
}
