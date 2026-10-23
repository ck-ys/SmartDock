package com.smartdock.dcs.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;
import java.util.List;

/**
 * 更新日志（全局表，不参与租户拦截；可见性由 targetTenants 名单控制）。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_changelog")
public class DcsChangelog extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long logId;

    /** 版本号，如 v1.10.0（写入前校验 v?数字.数字） */
    private String version;

    private String title;

    /** 条目 JSON：[{"type":"new|optimize|fix","text":"..."}] */
    private String items;

    /** 推送租户名单 JSON：["000000",...] */
    private String targetTenants;

    /** 0=草稿 1=已发布 */
    private String status;

    private Date publishTime;

    private String delFlag;

    /** 解析后的条目（非库字段） */
    @TableField(exist = false)
    private List<ChangelogItem> itemList;

    /** 解析后的租户名单（非库字段） */
    @TableField(exist = false)
    private List<String> targetTenantList;
}
