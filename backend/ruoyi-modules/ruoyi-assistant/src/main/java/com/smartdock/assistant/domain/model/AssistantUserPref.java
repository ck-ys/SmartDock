package com.smartdock.assistant.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 助手「常规偏好」按人入库（批C Q8 案1，2026-11-15；2026-11-13 待办板已拍）。
 *
 * <p>这七行以前只存浏览器 localStorage（键带 userId），换电脑、换浏览器就回默认。
 * 本表是同一份偏好的账号层；localStorage 降级成离线兜底。缺行=全部默认值，不预插行。</p>
 *
 * <p>注意：租户级「默认模型 / 允不允许自选 / 自选清单」在 {@code assistant_model_setting}，
 * 那是管理员口径，不是个人偏好——两者不要混。</p>
 */
@Data
@TableName("assistant_user_pref")
public class AssistantUserPref implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 用户 */
    private Long userId;

    /** enter / ctrl-enter */
    private String sendKey;

    /** 1=边生成边显示 0=等这一轮结束一次性出 */
    private String streamOutput;

    /** 1=工具明细默认展开 */
    private String toolsOpen;

    /** 1=失败自动重试（最多 1 次） */
    private String autoRetry;

    /** system / full / reduced */
    private String motion;

    /** standard / large */
    private String answerSize;

    /** 1=提问 5 分钟没答自动继续 */
    private String askAutoContinue;

    private Date createTime;

    private Date updateTime;
}
