package com.smartdock.risk;

import com.smartdock.risk.domain.DcsSensitiveWord;
import com.smartdock.risk.domain.bo.DcsSensitiveWordBo;
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
        DcsSensitiveWordBo bo = word("  test word  ", " mask ");
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
        DcsSensitiveWordBo bo = word("system word", "BLOCK");
        bo.setBuiltin("0");
        DcsSensitiveWord existing = new DcsSensitiveWord();
        existing.setBuiltin("1");

        policy.prepareUpdate(bo, "240152", existing);

        assertThat(bo.getTenantId()).isEqualTo("240152");
        assertThat(bo.getBuiltin()).isEqualTo("1");
    }

    @Test
    void rejectsInvalidActionEnabledAndOversizedFields() {
        assertThatThrownBy(() -> policy.prepareCreate(word("word", "ALLOW"), "240152"))
            .hasMessageContaining("MASK/BLOCK/REVIEW");

        DcsSensitiveWordBo invalidEnabled = word("word", "MASK");
        invalidEnabled.setEnabled("yes");
        assertThatThrownBy(() -> policy.prepareCreate(invalidEnabled, "240152"))
            .hasMessageContaining("0 或 1");

        assertThatThrownBy(() -> policy.prepareCreate(word("x".repeat(101), "MASK"), "240152"))
            .hasMessageContaining("100");
    }

    @Test
    void rejectsDeletingBuiltinWords() {
        DcsSensitiveWord builtin = new DcsSensitiveWord();
        builtin.setBuiltin("1");

        assertThatThrownBy(() -> policy.assertDeletable(List.of(builtin)))
            .hasMessageContaining("内置敏感词不允许删除");
    }

    private DcsSensitiveWordBo word(String text, String action) {
        DcsSensitiveWordBo bo = new DcsSensitiveWordBo();
        bo.setWordText(text);
        bo.setCategory(" GENERAL ");
        bo.setAction(action);
        bo.setEnabled("1");
        bo.setRemark(" note ");
        return bo;
    }
}
