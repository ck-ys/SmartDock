package com.smartdock.mission.service;

import com.smartdock.mission.domain.TaskField;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 提交数据校验器: 按 task_field 定义校验 data Map
 * (必填/类型/枚举)。类型口径: number 要求数值形态字符串或 Number;
 * date 要求 yyyy-MM-dd; multi_select 值为候选子集数组。
 *
 * @author smartdock
 */
@Component
public class TaskFieldValidator {

    /**
     * @throws ServiceException 任一字段不合法(消息含 fieldCode, 用户可读)
     */
    public void validate(List<TaskField> fields, Map<String, Object> data) {
        for (TaskField f : fields) {
            Object v = data.get(f.getFieldCode());
            if (v == null || (v instanceof String s && s.isBlank())) {
                if (f.requiredFlag()) {
                    throw new ServiceException("字段 [" + f.getFieldName() + "] 必填");
                }
                continue;
            }
            switch (f.getFieldType() == null ? TaskField.TYPE_TEXT : f.getFieldType()) {
                case TaskField.TYPE_NUMBER -> requireNumber(f, v);
                case TaskField.TYPE_DATE -> requireDate(f, v);
                case TaskField.TYPE_SELECT -> requireInOptions(f, v, false);
                case TaskField.TYPE_MULTI_SELECT -> requireInOptions(f, v, true);
                default -> {
                    // text/attachment: 仅长度护栏
                    if (String.valueOf(v).length() > 2000) {
                        throw new ServiceException("字段 [" + f.getFieldName() + "] 超长(≤2000)");
                    }
                }
            }
        }
    }

    private void requireNumber(TaskField f, Object v) {
        try {
            new java.math.BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new ServiceException("字段 [" + f.getFieldName() + "] 要求数值");
        }
    }

    private void requireDate(TaskField f, Object v) {
        try {
            java.time.LocalDate.parse(String.valueOf(v));
        } catch (Exception e) {
            throw new ServiceException("字段 [" + f.getFieldName() + "] 要求日期(yyyy-MM-dd)");
        }
    }

    private void requireInOptions(TaskField f, Object v, boolean multi) {
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
