package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 助手 run 登记（批G G3，2026-10-06）。runId 服务端登记制（S-04①）：
 * 首次出现即落库绑定 (tenant,user,thread) 归属，后续同 runId 复访校验归属（重放旧
 * runId 换人=403）；纯查询回合也登记。断线重连恢复链的数据源之一（X-09/E5）。
 */
@Data
@TableName("dcs_assistant_run")
public class DcsAssistantRun implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 行ID（雪花） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 归属用户 */
    private Long userId;

    /** AGUI threadId */
    private String threadId;

    /** AGUI runId（全站唯一，归属首绑键） */
    private String runId;

    /** RUNNING/COMPLETED/FAILED/INTERRUPTED */
    private String status;

    /** 终态原因（interrupt/error/normal） */
    private String endReason;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}
