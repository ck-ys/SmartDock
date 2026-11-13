package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 助手长期记忆的个人开关（ckys3 解锁板题10 案1，2026-11-12）。
 *
 * <p>租户级 {@code dcs_assistant_config.memory_enabled} 仍是总闸；本表是用户级开关，
 * 两者与运算（租户关=全关）。缺行=默认开启（enabled='1'），不预插行。</p>
 */
@Data
@TableName("dcs_assistant_memory_pref")
public class DcsAssistantMemoryPref implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 用户 */
    private Long userId;

    /** 1=开启 0=关闭 */
    private String enabled;

    private Date createTime;

    private Date updateTime;
}
