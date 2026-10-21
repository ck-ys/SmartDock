package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 批2 Q2=A（2026-10-21）：MCP 外部服务注册行（管理台「外部工具」页签维护）。
 * 试点=平台级共享：tenant_id 预留恒 '000000'；read_only 恒 '1'（试点只收只读服务）。
 * args/headers 存 JSON 字符串（text 列，service 层序列化；headers 敏感值加密后存）。
 */
@Data
@TableName("dcs_mcp_server")
public class DcsMcpServer implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    /** 服务唯一名（MCP client 命名空间；小写字母数字下划线，3-30 位） */
    private String serverName;

    private String displayName;

    /** STDIO / STREAMABLE_HTTP / SSE */
    private String transportType;

    /** STDIO：可执行命令（白名单只允许 node） */
    private String command;

    /** STDIO：参数数组 JSON 字符串（脚本路径必须在允许目录内） */
    private String args;

    /** HTTP 类传输：服务地址（host 必须在白名单） */
    private String url;

    /** 额外请求头 JSON 字符串（含密钥的值加密存储） */
    private String headers;

    private String readOnly;

    /** 1=挂载进助手工具面；0=停用（服务下线演练口） */
    private String enabled;

    private String remark;

    private Long createBy;

    private java.util.Date createTime;

    private Long updateBy;

    private java.util.Date updateTime;
}
