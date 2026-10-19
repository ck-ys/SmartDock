package com.smartdock.risk.service;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;
import com.smartdock.risk.domain.DcsSensitiveWord;
import com.smartdock.risk.domain.bo.DcsSensitiveWordBo;
import com.smartdock.risk.support.SensitiveWordMatcher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Normalizes sensitive-word writes and protects system-controlled fields.
 */
@Component
public class SensitiveWordInputPolicy {

    public void prepareCreate(DcsSensitiveWordBo bo, String tenantId) {
        bo.setWordId(null);
        bo.setTenantId(tenantId);
        bo.setBuiltin("0");
        clearAuditFields(bo);
        normalizeAndValidate(bo);
    }

    public void prepareUpdate(DcsSensitiveWordBo bo, String tenantId, DcsSensitiveWord existing) {
        bo.setTenantId(tenantId);
        bo.setBuiltin(existing.getBuiltin());
        clearAuditFields(bo);
        normalizeAndValidate(bo);
    }

    public void assertDeletable(Collection<DcsSensitiveWord> rows) {
        if (rows.stream().anyMatch(row -> "1".equals(row.getBuiltin()))) {
            throw new ServiceException("内置敏感词不允许删除");
        }
    }

    private void normalizeAndValidate(DcsSensitiveWordBo bo) {
        bo.setWordText(StrUtil.trim(bo.getWordText()));
        bo.setCategory(StrUtil.blankToDefault(StrUtil.trim(bo.getCategory()), "GENERAL"));
        bo.setAction(StrUtil.blankToDefault(StrUtil.trim(bo.getAction()), SensitiveWordMatcher.ACTION_MASK).toUpperCase(Locale.ROOT));
        bo.setEnabled(StrUtil.blankToDefault(bo.getEnabled(), "1"));
        bo.setRemark(StrUtil.trim(bo.getRemark()));
        bo.setScene(normalizeScene(bo.getScene()));

        if (StrUtil.isBlank(bo.getWordText()) || bo.getWordText().length() > 100) {
            throw new ServiceException("敏感词不能为空且不能超过 100 个字符");
        }
        if (bo.getCategory().length() > 50) {
            throw new ServiceException("敏感词分类不能超过 50 个字符");
        }
        if (bo.getRemark() != null && bo.getRemark().length() > 200) {
            throw new ServiceException("敏感词备注不能超过 200 个字符");
        }
        if (!List.of(SensitiveWordMatcher.ACTION_MASK, SensitiveWordMatcher.ACTION_BLOCK, SensitiveWordMatcher.ACTION_REVIEW).contains(bo.getAction())) {
            throw new ServiceException("处理方式仅支持 MASK/BLOCK/REVIEW");
        }
        if (!"0".equals(bo.getEnabled()) && !"1".equals(bo.getEnabled())) {
            throw new ServiceException("启用状态只能是 0 或 1");
        }
    }

    /**
     * 场景归一化(2026-09-22 词库场景化): 逗号分隔的 CHAT/FORM/FILE 组合, 拆分去重保序重新拼接。
     * <p>空=CHAT(存量口径); ALL 仅是装载层内部标记(系统兜底词), 不允许入库——
     * 过滤装载按 scene CSV LIKE 匹配显式场景, ALL 行任何场景都匹配不到。</p>
     */
    private String normalizeScene(String scene) {
        List<String> scenes = StrUtil.split(scene, ',', true, true).stream()
            .map(s -> s.toUpperCase(Locale.ROOT))
            .distinct()
            .toList();
        if (scenes.isEmpty()) {
            return "CHAT";
        }
        for (String s : scenes) {
            if (!List.of("CHAT", "FORM", "FILE").contains(s)) {
                throw new ServiceException("生效场景仅支持 CHAT/FORM/FILE");
            }
        }
        return String.join(",", scenes);
    }

    private void clearAuditFields(DcsSensitiveWordBo bo) {
        bo.setCreateDept(null);
        bo.setCreateBy(null);
        bo.setCreateTime(null);
        bo.setUpdateBy(null);
        bo.setUpdateTime(null);
    }
}
