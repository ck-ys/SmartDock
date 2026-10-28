package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 场景模板(我的模板) profile_scenario_template——导入的模板落这里, 内置模板是代码常量不落库。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_scenario_template")
public class ProfileScenarioTemplate extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "template_id", type = IdType.ASSIGN_ID)
    private Long templateId;

    private String tenantId;

    private String templateCode;

    private String templateName;

    /** 场景标签, 如「零售商城」。 */
    private String scenario;

    private String description;

    /** ScenarioTemplateDef 形状的完整定义(jsonb)。 */
    private String definition;

    /** 预留位: 当前只有导入模板(0), 与其它表的 builtin 列口径一致。 */
    private String builtin;

    /** 逻辑删除(与表 del_flag 对齐; 软删后编码可重新导入)。 */
    private String delFlag;
}
