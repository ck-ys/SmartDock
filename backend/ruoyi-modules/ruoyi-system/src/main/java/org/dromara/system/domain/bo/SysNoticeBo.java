package org.dromara.system.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.xss.Xss;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.system.domain.SysNotice;

/**
 * 通知公告业务对象 sys_notice
 *
 * @author Michelle.Chung
 */

@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = SysNotice.class, reverseConvertGenerate = false)
public class SysNoticeBo extends BaseEntity {

    /**
     * 公告ID
     */
    private Long noticeId;

    /**
     * 公告标题
     */
    @Xss(message = "公告标题不能包含脚本字符")
    @NotBlank(message = "公告标题不能为空")
    @Size(min = 0, max = 50, message = "公告标题不能超过{max}个字符")
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
     * 公告状态（0正常 1关闭）
     */
    private String status;

    /**
     * 是否置顶（0否 1是）
     */
    @jakarta.validation.constraints.Pattern(regexp = "[01]", message = "置顶标记只能是 0 或 1")
    private String isTop;

    /**
     * 发布时间（空=立即发布）
     */
    private java.util.Date publishTime;

    /**
     * 过期时间（空=长期有效）
     */
    private java.util.Date expireTime;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建人名称
     */
    private String createByName;

}
