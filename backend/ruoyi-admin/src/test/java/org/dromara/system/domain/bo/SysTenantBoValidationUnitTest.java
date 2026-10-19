package org.dromara.system.domain.bo;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.dromara.common.core.validate.AddGroup;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class SysTenantBoValidationUnitTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void addRejectsCompanyNameLongerThanDatabaseColumn() {
        SysTenantBo bo = new SysTenantBo();
        bo.setCompanyName("DCS-PROD-VALIDATION-20260724-312887");
        bo.setContactUserName("Codex Validation");
        bo.setContactPhone("13900000000");
        bo.setUsername("validation-admin");
        bo.setPassword("validation-password");
        bo.setPackageId(1L);

        Set<ConstraintViolation<SysTenantBo>> violations = validator.validate(bo, AddGroup.class);

        assertThat(violations)
            .anySatisfy(violation -> {
                assertThat(violation.getPropertyPath().toString()).isEqualTo("companyName");
                assertThat(violation.getMessage()).isEqualTo("企业名称长度不能超过30个字符");
            });
    }
}
