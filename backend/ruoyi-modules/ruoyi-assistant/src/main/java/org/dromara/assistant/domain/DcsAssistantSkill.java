package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 技能在线层（5b 批，2026-10-28 拍板 题1 甲/题2 甲）：技能内容的数据库行。
 *
 * <p>层序语义：jar 内置（classpath /skills）→ 部署机目录（config/skills）→ <b>本表（最高）</b>，
 * 同 skill_id 后层胜。{@code tenant_id='000000'} 行=全局基线；租户行覆盖同 skill_id 全局行
 * （A 单位改的 B 单位看不见）。管理台删掉在线行=自动露出下层（内置=恢复出厂）。</p>
 *
 * <p>无触发词列（题7 甲）：{@code description}（一句话说明）是模型判断什么时候用这个
 * 技能的唯一依据。租户管理员维护，显式保存 tenantId，不继承 TenantEntity（与公告表同款）。</p>
 *
 * @author smartdock
 */
@Data
@TableName("dcs_assistant_skill")
public class DcsAssistantSkill implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花） */
    @TableId(value = "id")
    private Long id;

    /** 租户编号（000000=全局基线行） */
    private String tenantId;

    /** 技能标识（与 jar 内置同名=覆写；租户+技能 唯一索引） */
    private String skillId;

    /** 显示名（空=用 skillId） */
    private String displayName;

    /** 一句话说明（模型触发唯一依据；上限 1024，超长保存拦） */
    private String description;

    /** 最低角色：member/publisher/admin（对内置覆写只升不降，题5 防呆①） */
    private String minRole;

    /** 正文 Markdown（题5 防呆③：上限 2 万字） */
    private String body;

    /** 停用：1=本行存在但不参与装载（内置技能「彻底不要」的形态） */
    private Integer disabled;

    /** 创建人（userId） */
    private Long createBy;

    /** 创建时间 */
    private java.util.Date createTime;

    /** 最后修改人（审计位） */
    private Long updateBy;

    /** 最后修改时间 */
    private java.util.Date updateTime;
}
