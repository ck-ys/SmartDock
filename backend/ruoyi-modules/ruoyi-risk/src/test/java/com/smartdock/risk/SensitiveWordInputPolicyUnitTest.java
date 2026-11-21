package com.smartdock.risk;

import com.smartdock.risk.domain.SensitiveWord;
import com.smartdock.risk.domain.bo.SensitiveWordBo;
import com.smartdock.risk.service.SensitiveWordInputPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("dev")
class SensitiveWordInputPolicyUnitTest {

    private final SensitiveWordInputPolicy policy = new SensitiveWordInputPolicy();

    @Test
    void preparesCustomWordCreateWithoutTrustingIdentityOrAuditFields() {
        SensitiveWordBo bo = word("  test word  ", " mask ");
        bo.setWordId(99L);
        bo.setTenantId("forged");
        bo.setBuiltin("1");
        bo.setCreateBy(91L);
        bo.setCreateTime(new Date());

        policy.prepareCreate(bo, "240152");

        assertThat(bo.getWordId()).isNull();
        assertThat(bo.getTenantId()).isEqualTo("240152");
        assertThat(bo.getBuiltin()).isEqualTo("0");
        assertThat(bo.getWordText()).isEqualTo("test word");
        assertThat(bo.getAction()).isEqualTo("MASK");
        assertThat(bo.getCreateBy()).isNull();
        assertThat(bo.getCreateTime()).isNull();
    }

    @Test
    void preservesBuiltinIdentityOnUpdate() {
        SensitiveWordBo bo = word("system word", "BLOCK");
        bo.setBuiltin("0");
        SensitiveWord existing = new SensitiveWord();
        existing.setBuiltin("1");

        policy.prepareUpdate(bo, "240152", existing);

        assertThat(bo.getTenantId()).isEqualTo("240152");
        assertThat(bo.getBuiltin()).isEqualTo("1");
    }

    @Test
    void rejectsInvalidActionEnabledAndOversizedFields() {
        assertThatThrownBy(() -> policy.prepareCreate(word("word", "ALLOW"), "240152"))
            .hasMessageContaining("MASK/BLOCK/REVIEW");

        SensitiveWordBo invalidEnabled = word("word", "MASK");
        invalidEnabled.setEnabled("yes");
        assertThatThrownBy(() -> policy.prepareCreate(invalidEnabled, "240152"))
            .hasMessageContaining("0 或 1");

        assertThatThrownBy(() -> policy.prepareCreate(word("x".repeat(101), "MASK"), "240152"))
            .hasMessageContaining("100");
    }

    @Test
    void rejectsDeletingBuiltinWords() {
        SensitiveWord builtin = new SensitiveWord();
        builtin.setBuiltin("1");

        assertThatThrownBy(() -> policy.assertDeletable(List.of(builtin)))
            .hasMessageContaining("内置敏感词不允许删除");
    }

    private SensitiveWordBo word(String text, String action) {
        SensitiveWordBo bo = new SensitiveWordBo();
        bo.setWordText(text);
        bo.setCategory(" GENERAL ");
        bo.setAction(action);
        bo.setEnabled("1");
        bo.setRemark(" note ");
        return bo;
    }
}
