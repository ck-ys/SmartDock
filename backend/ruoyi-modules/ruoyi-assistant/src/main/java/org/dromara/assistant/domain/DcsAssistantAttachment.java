package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 助手会话附件（附件分析批 ATTACH-1，2026-10-07）。
 *
 * <p>链路：用户在助手输入区上传（通用 OSS 接口，bizSource=ASSISTANT 走 12b
 * EXEMPT 免审留痕单）→ 登记本表（thread 绑定+属主校验+每会话≤3 个）→
 * 同步解析（OssTextExtractor 截 8000 行落 parsed_text）。表行=上传人私有：
 * 查询一律 user_id 谓词，模型工具读文再过 PII 掩码出网。</p>
 */
@Data
@TableName("dcs_assistant_attachment")
public class DcsAssistantAttachment implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** AGUI 会话标识（会话内多轮追问不重传） */
    private String threadId;

    /** 上传人=属主（私有附件，查询必带） */
    private Long userId;

    /** sys_oss 主键 */
    private Long ossId;

    /** 原始文件名 */
    private String fileName;

    /** 字节数（登记时快照，超限拒绝依据） */
    private Long fileSize;

    /** 规范化后缀（docx/xlsx/pptx/pdf/txt 白名单） */
    private String suffix;

    /** PENDING(排队)/PARSING(解析中,G4 批G 1008)/PARSED/TRUNCATED/FAILED */
    private String parseStatus;

    /** FAILED 时人话原因（前端提示） */
    private String parseError;

    /** 提取文本（截 8000 行；模型注入前再截 ~12k 字符+PII 掩码） */
    private String parsedText;

    /** 截断后行数（TRUNCATED 提示用） */
    private Integer lineCount;

    /** 删除标志（0=存在 1=删除；随会话保留期清理） */
    private String delFlag;

    /** 创建部门 */
    private Long createDept;

    /** 创建者 */
    private Long createBy;

    /** 创建时间 */
    private Date createTime;

    /** 更新者 */
    private Long updateBy;

    /** 更新时间 */
    private Date updateTime;
}
