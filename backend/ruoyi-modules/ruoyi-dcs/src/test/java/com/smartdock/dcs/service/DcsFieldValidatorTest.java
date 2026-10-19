package com.smartdock.dcs.service;

import com.smartdock.dcs.domain.DcsTaskField;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 提交数据校验器守卫(必填/类型/枚举)。
 *
 * @author smartdock
 */
@Tag("local")
class DcsFieldValidatorTest {

    private final DcsTaskFieldValidator validator = new DcsTaskFieldValidator();

    private DcsTaskField field(String code, String name, String type, boolean required, List<String> options) {
        DcsTaskField f = new DcsTaskField();
        f.setFieldCode(code);
        f.setFieldName(name);
        f.setFieldType(type);
        f.setRequired(required ? "1" : "0");
        f.setOptions(options == null ? "[]" : com.smartdock.dcs.support.DcsJsons.toJson(options));
        return f;
    }

    @Test
    void requiredMissingRejected() {
        assertThatThrownBy(() -> validator.validate(
            List.of(field("content", "内容", "text", true, null)), Map.of()))
            .isInstanceOf(ServiceException.class).hasMessageContaining("必填");
    }

    @Test
    void optionalMissingPasses() {
        assertThatCode(() -> validator.validate(
            List.of(field("note", "备注", "text", false, null)), Map.of()))
            .doesNotThrowAnyException();
    }

    @Test
    void numberAndDateTypes() {
        var fields = List.of(field("amount", "金额", "number", true, null),
            field("day", "日期", "date", true, null));
        assertThatCode(() -> validator.validate(fields, Map.of("amount", "12.5", "day", "2026-10-18")))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(fields, Map.of("amount", "约2小时", "day", "2026-10-18")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("数值");
        assertThatThrownBy(() -> validator.validate(fields, Map.of("amount", "1", "day", "10/18")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("日期");
    }

    @Test
    void enumOptionsEnforced() {
        var select = List.of(field("level", "档位", "select", true, List.of("low", "high")));
        assertThatCode(() -> validator.validate(select, Map.of("level", "high"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(select, Map.of("level", "mid")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("候选外");

        var multi = List.of(field("tags", "标签", "multi_select", true, List.of("a", "b")));
        assertThatCode(() -> validator.validate(multi, Map.of("tags", List.of("a", "b")))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(multi, Map.of("tags", List.of("a", "x"))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("候选外");
        assertThatThrownBy(() -> validator.validate(multi, Map.of("tags", "a")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("多选数组");
    }
}
