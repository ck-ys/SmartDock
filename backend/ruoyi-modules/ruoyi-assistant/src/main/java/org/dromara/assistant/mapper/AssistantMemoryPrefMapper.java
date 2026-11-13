package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.assistant.domain.DcsAssistantMemoryPref;

/**
 * 记忆个人开关 Mapper（ckys3 解锁板题10 案1）。
 *
 * <p>读写都带 tenant_id+user_id 双谓词；缺行=默认开启。走 UPSERT（ON CONFLICT）保证
 * 并发首写不撞唯一键。</p>
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantMemoryPrefMapper extends BaseMapper<DcsAssistantMemoryPref> {

    /** 读本人开关；无行返回 null（服务层按默认开启处理） */
    @Select("SELECT * FROM dcs_assistant_memory_pref WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    DcsAssistantMemoryPref selectOwn(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /** 写本人开关（并发安全 UPSERT） */
    @Update("INSERT INTO dcs_assistant_memory_pref (id, tenant_id, user_id, enabled, create_time, update_time)"
        + " VALUES (#{id}, #{tenantId}, #{userId}, #{enabled}, #{now}, #{now})"
        + " ON CONFLICT (tenant_id, user_id)"
        + " DO UPDATE SET enabled=EXCLUDED.enabled, update_time=EXCLUDED.update_time")
    int upsertOwn(@Param("id") Long id, @Param("tenantId") String tenantId,
                  @Param("userId") Long userId, @Param("enabled") String enabled,
                  @Param("now") java.util.Date now);
}
