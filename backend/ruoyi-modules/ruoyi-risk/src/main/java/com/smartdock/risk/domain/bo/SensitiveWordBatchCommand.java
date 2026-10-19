package com.smartdock.risk.domain.bo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 敏感词批量导入命令 BO(2026-08-23)。
 *
 * <p>前端按行拆词后单请求提交; 每词复用 {@code SensitiveWordInputPolicy.prepareCreate}
 * 校验与归一化(分类默认 GENERAL / 动作白名单 MASK|BLOCK|REVIEW / 默认启用),
 * 批内与库内重复词跳过, 单事务原子提交。</p>
 *
 * @param words    词条列表(已去空白; 批内重复由服务端去重)
 * @param category 分类(可选, 默认 GENERAL)
 * @param action   处理方式(必填, MASK/BLOCK/REVIEW)
 * @param enabled  启用状态(可选, 默认 1)
 * @param remark   备注(可选)
 * @param scene    生效场景 CSV(可选, 默认 CHAT; 2026-09-22 词库场景化)
 * @author smartdock
 */
public record SensitiveWordBatchCommand(
    @NotEmpty(message = "导入词条不能为空")
    @Size(max = 500, message = "单次导入不能超过 500 条")
    List<String> words,
    String category,
    String action,
    String enabled,
    String remark,
    String scene
) {
}
