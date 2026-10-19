package com.smartdock.dcs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdock.dcs.domain.DcsTaskField;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 微核心: 任务字段 Mapper。
 *
 * <p>字段定义整表替换式保存(deleteByTask + batch insert), 不做逐字段 diff——
 * 教科书取舍: 提交数据按 fieldCode 存值, 字段演进天然向前兼容。</p>
 *
 * @author smartdock
 */
@Mapper
public interface DcsTaskFieldMapper extends BaseMapper<DcsTaskField> {

    @Insert("""
        INSERT INTO dcs_task_field
            (id, tenant_id, task_id, field_code, field_name, field_type, required, options, order_num, create_time)
        VALUES
            (#{f.id}, #{f.tenantId}, #{f.taskId}, #{f.fieldCode}, #{f.fieldName}, #{f.fieldType},
             #{f.required}, #{f.options}::jsonb, #{f.orderNum}, CURRENT_TIMESTAMP)
        """)
    int insertJsonb(@Param("f") DcsTaskField f);

    @Delete("DELETE FROM dcs_task_field WHERE task_id = #{taskId}")
    int deleteByTask(@Param("taskId") Long taskId);
}
