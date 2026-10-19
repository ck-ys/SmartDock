package com.smartdock.risk.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * DCS chat sensitive word rule.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_sensitive_word")
public class DcsSensitiveWord extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "word_id")
    private Long wordId;

    private String wordText;

    private String category;

    /** MASK / BLOCK / REVIEW */
    private String action;

    private String enabled;

    private String builtin;

    /**
     * 生效场景 CSV: CHAT=聊天 / FORM=表单(含追加/在线批量/Excel导入) / FILE=文件。
     * 存量行默认 CHAT(2026-09-22 词库场景化), 表单场景词库由管理员按需配置, 初始为空防误伤。
     */
    private String scene;

    private String remark;

    @TableLogic(value = "0", delval = "1")
    private String delFlag;
}
