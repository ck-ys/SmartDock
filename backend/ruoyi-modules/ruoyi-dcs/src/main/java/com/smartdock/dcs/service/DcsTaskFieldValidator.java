package com.smartdock.dcs.service;

import com.smartdock.dcs.domain.DcsTaskField;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 提交数据校验器: 按 dcs_task_field 定义校验 data Map
 * (必填/类型/枚举)。类型口径: number 要求数值形态字符串或 Number;
 * date 要求 yyyy-MM-dd; multi_select 值为候选子集数组。
 *
 * @author smartdock
 */
@Component
public class DcsTaskFieldValidator {

    /**
     * @throws ServiceException 任一字段不合法(消息含 fieldCode, 用户可读)
     */
    public void validate(List<DcsTaskField> fields, Map<String, Object> data) {
        for (DcsTaskField f : fields) {
            Object v = data.get(f.getFieldCode());
            if (v == null || (v instanceof String s && s.isBlank())) {
                if (f.requiredFlag()) {
                    throw new ServiceException("字段 [" + f.getFieldName() + "] 必填");
                }
                continue;
            }
            switch (f.getFieldType() == null ? DcsTaskField.TYPE_TEXT : f.getFieldType()) {
                case DcsTaskField.TYPE_NUMBER -> requireNumber(f, v);
                case DcsTaskField.TYPE_DATE -> requireDate(f, v);
                case DcsTaskField.TYPE_SELECT -> requireInOptions(f, v, false);
                case DcsTaskField.TYPE_MULTI_SELECT -> requireInOptions(f, v, true);
                default -> {
                    // text/attachment: 仅长度护栏
                    if (String.valueOf(v).length() > 2000) {
                        throw new ServiceException("字段 [" + f.getFieldName() + "] 超长(≤2000)");
                    }
                }
            }
        }
    }

    private void requireNumber(DcsTaskField f, Object v) {
        try {
            new java.math.BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new ServiceException("字段 [" + f.getFieldName() + "] 要求数值");
        }
    }

    private void requireDate(DcsTaskField f, Object v) {
        try {
            java.time.LocalDate.parse(String.valueOf(v));
        } catch (Exception e) {
            throw new ServiceException("字段 [" + f.getFieldName() + "] 要求日期(yyyy-MM-dd)");
        }
    }

    private void requireInOptions(DcsTaskField f, Object v, boolean multi) {
        List<String> options = f.optionList();
        if (multi) {
            if (!(v instanceof List<?> vals)) {
                throw new ServiceException("字段 [" + f.getFieldName() + "] 要求多选数组");
            }
            for (Object one : vals) {
                if (!options.contains(String.valueOf(one))) {
                    throw new ServiceException("字段 [" + f.getFieldName() + "] 候选外取值: " + one);
                }
            }
        } else if (!options.contains(String.valueOf(v))) {
            throw new ServiceException("字段 [" + f.getFieldName() + "] 候选外取值: " + v);
        }
    }
}
