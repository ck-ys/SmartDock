package org.dromara.system.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.dromara.common.tenant.core.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;


/**
 * 通知公告表 sys_notice
 *
 * @author Lion Li
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_notice")
public class SysNotice extends TenantEntity {

    /**
     * 公告ID
     */
    @TableId(value = "notice_id")
    private Long noticeId;

    /**
     * 公告标题
     */
    private String noticeTitle;

    /**
     * 公告类型（1通知 2公告）
     */
    private String noticeType;

    /**
     * 公告内容
     */
    private String noticeContent;

    /**
     * 是否置顶（0否 1是）
     */
    private String isTop;

    /**
     * 发布时间（空=立即发布）
     *
     * <p>编辑时不传=沿用旧值（默认 NOT_NULL 策略）：要立刻发布就把时间改成当前。</p>
     */
    private java.util.Date publishTime;

    /**
     * 过期时间（空=长期有效）
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private java.util.Date expireTime;

    /**
     * 公告状态（0正常 1关闭）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 当前用户是否已读（非库字段，公告门户回填）
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String readFlag;

}
