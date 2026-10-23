package com.smartdock.dcs.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.util.Date;
import java.util.List;

/**
 * 产品反馈（N3 2026-10-23）。
 *
 * <p>单表设计：不建回复子表，{@code handleNote} 保存最近一次处理意见（覆盖式），
 * 与流程「四态流转 + 一次处理结论」匹配；需要多轮对话时再拆子表。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_feedback")
public class DcsFeedback extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_CLOSED = "CLOSED";

    public static final String TYPE_BUG = "BUG";
    public static final String TYPE_EXPERIENCE = "EXPERIENCE";
    public static final String TYPE_SUGGEST = "SUGGEST";
    public static final String TYPE_OTHER = "OTHER";

    @TableId
    private Long feedbackId;

    private String title;

    private String content;

    private String feedbackType;

    /** 截图 ossId 逗号串（最多 3 张） */
    private String ossIds;

    private String contact;

    /** 提交时自动采集的页面/浏览器/版本上下文（JSON 文本） */
    private String contextJson;

    private String status;

    private Long handlerId;

    private String handlerName;

    private String handleNote;

    private Date handleTime;

    private String delFlag;

    /** 列表回填：提交人昵称（非库字段） */
    @TableField(exist = false)
    private String createByName;

    /** 详情回填：截图 ossId 列表（非库字段） */
    @TableField(exist = false)
    private List<String> ossIdList;
}
