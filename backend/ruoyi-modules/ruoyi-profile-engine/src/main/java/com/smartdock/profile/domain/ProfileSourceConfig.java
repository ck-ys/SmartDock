package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 数据源显示配置 profile_source_config(改名/停用)。
 *
 * <p>只做覆盖层: 未配置的源一律默认启用、显示名回退前端兜底标签——
 * 列源 SQL LEFT JOIN 本表过滤 enabled, 停用即从全部下拉消失(已入数据不动)。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_source_config")
public class ProfileSourceConfig extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "config_id", type = IdType.ASSIGN_ID)
    private Long configId;

    private String tenantId;

    private String source;

    /** 下拉里的显示名(空=回退默认标签); 更新必须能写回 null——清空改名即还原默认 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String displayName;

    /** 1=启用 0=停用(停用源不出现在任何下拉, 数据与配置保留) */
    private String enabled;

    /** 逻辑删除(三审 dspro P2: 与列源联表的 del_flag 条件对齐, 防未来加删除入口后出现"看得到读不到"死态) */
    @TableLogic
    private String delFlag;
}
