package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 助手公告（AI 缺口批 Q11，2026-10-14 拍板全A）：管理台发布/撤回，用户侧助手顶部
 * 横幅，每用户本地可关闭（localStorage 按 id 记忆，新公告 id 变化自然重现）。
 *
 * <p>该表由租户管理员维护，显式保存 tenantId，不继承 TenantEntity（与会话表同款）。</p>
 *
 * @author smartdock
 */
@Data
@TableName("dcs_assistant_announcement")
public class DcsAssistantAnnouncement implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花） */
    @TableId(value = "id")
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 公告标题（横幅主文案） */
    private String title;

    /** 公告正文（可空=纯标题横幅） */
    private String content;

    /** 样式：NORMAL=普通公告（蓝）/ MAINTENANCE=维护提示（橙警示） */
    private String style;

    /** 状态：1=生效（用户可见）/ 0=已撤回（保留历史） */
    private String status;

    /** 发布人（userId） */
    private Long publishBy;

    /** 发布/最近编辑时间（用户侧取最新一条生效公告） */
    private Date publishTime;

    private Date createTime;

    private Date updateTime;
}
