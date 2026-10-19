package com.smartdock.risk.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import com.smartdock.risk.domain.DcsSensitiveWord;

/**
 * 敏感词配置业务对象(新增/编辑接口入参), 继承 BaseEntity。
 *
 * <p><b>主要字段</b>:</p>
 * <ul>
 *   <li>wordId: 敏感词 ID, EditGroup 必填、AddGroup 为空(新增由 DB 生成)</li>
 *   <li>wordText: 敏感词文本</li>
 *   <li>category: 分类, 如 色情 / 政治 / 广告</li>
 *   <li>action: 命中后的处理方式, BLOCK 拒绝 / WARN 警告 / MASK 脱敏</li>
 *   <li>enabled: 是否启用, 1 启用 / 0 禁用</li>
 *   <li>builtin: 是否系统内置, 1 系统内置(不可删) / 0 自定义</li>
 * </ul>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = DcsSensitiveWord.class, reverseConvertGenerate = false)
public class DcsSensitiveWordBo extends BaseEntity {

    @NotNull(message = "敏感词ID不能为空", groups = {EditGroup.class})
    private Long wordId;

    private String tenantId;

    @NotBlank(message = "敏感词不能为空", groups = {AddGroup.class, EditGroup.class})
    private String wordText;

    private String category;

    @NotBlank(message = "处理方式不能为空", groups = {AddGroup.class, EditGroup.class})
    private String action;

    private String enabled;

    private String builtin;

    private String remark;

    /**
     * 生效场景 CSV(2026-09-22 词库场景化): CHAT/FORM/FILE 逗号组合, 空默认 CHAT;
     * 由 SensitiveWordInputPolicy.normalizeScene 归一化校验
     */
    private String scene;
}
