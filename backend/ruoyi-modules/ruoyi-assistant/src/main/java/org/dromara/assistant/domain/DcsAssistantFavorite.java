package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 助手常用问句收藏（C2 收藏跨设备，2026-10-07）。
 *
 * <p>原 favStore 一期 localStorage 不跨设备（登记二站项）；本表按用户级落库，
 * 上限 6 条由服务层校验（与前端 chips 上限一致）。前端存量 localStorage 首启
 * 合并上传后迁移完成。</p>
 */
@Data
@TableName("dcs_assistant_favorite")
public class DcsAssistantFavorite implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 收藏人（用户级私有，查询必带谓词） */
    private Long userId;

    /** 问句内容（上限 200 字符） */
    private String content;

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

    /** 删除标志（0=存在 1=删除） */
    private String delFlag;
}
