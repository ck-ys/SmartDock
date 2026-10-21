package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 批2 Q2=A（2026-10-21）：MCP 挂载/调用审计行。
 * action 口径：MOUNT_OK/MOUNT_ERROR（建会话挂载结果）+ CALL_OK/CALL_ERROR/RATE_LIMITED
 * （GatedMcpClient 装饰层逐次真实调用记录）。status：1=成功 0=失败。
 */
@Data
@TableName("dcs_mcp_call_log")
public class DcsMcpCallLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private Long userId;

    private String serverName;

    private String toolName;

    private String action;

    private String status;

    private Integer durationMs;

    private String errorMsg;

    private java.util.Date createTime;
}
