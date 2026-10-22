package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.dromara.common.encrypt.annotation.EncryptField;
import org.dromara.common.encrypt.enumd.AlgorithmType;

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
    /** HTTP 行请求头 JSON（键值对）。G4-#1：可存鉴权头——值经 MyBatis 加密插件
     *  AES+专用密钥落库（照抄 DcsAiConfig 先例，显式算法+密钥不依赖全局默认 BASE64；
     *  坑：AES 密钥必须 16/24/32 位，首版 "DcsMcpEncKey16" 是 14 位——拦截器把
     *  IllegalArgument 吞掉后明文落库，服务层同款显式路径会如实报错，双保险），
     *  回显只出键名不出值（headerKeysOnly），save 端点不落请求体日志 */
    @EncryptField(algorithm = AlgorithmType.AES, password = "DcsMcpHeadersK16")
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
