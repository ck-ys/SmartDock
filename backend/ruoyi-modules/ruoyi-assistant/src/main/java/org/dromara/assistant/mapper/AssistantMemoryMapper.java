package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.assistant.domain.DcsAssistantMemory;

import java.util.List;

/**
 * 助手长期记忆 Mapper（二站④ AU-53）。显式租户谓词红线同
 * {@link AssistantFavoriteMapper}（@InterceptorIgnore + SQL 显式 tenant_id）——
 * 助手链路在流线程裸租户上下文跑（PD-2 契约），禁依赖行级插件自动拼租户。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantMemoryMapper extends BaseMapper<DcsAssistantMemory> {

    /** 用户记忆清单（新→旧；属主+租户双谓词） */
    @Select("SELECT * FROM dcs_assistant_memory WHERE tenant_id=#{tenantId}"
        + " AND user_id=#{userId} AND del_flag='0' ORDER BY id DESC")
    List<DcsAssistantMemory> listByUser(@Param("tenantId") String tenantId,
                                        @Param("userId") Long userId);

    /** 记忆条数（服务层容量闸用） */
    @Select("SELECT COUNT(*) FROM dcs_assistant_memory WHERE tenant_id=#{tenantId}"
        + " AND user_id=#{userId} AND del_flag='0'")
    int countByUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /** 按内容精确查重（同内容幂等不双落） */
    @Select("SELECT COUNT(*) FROM dcs_assistant_memory WHERE tenant_id=#{tenantId}"
        + " AND user_id=#{userId} AND content=#{content} AND del_flag='0'")
    int countByContent(@Param("tenantId") String tenantId, @Param("userId") Long userId,
                       @Param("content") String content);

    /** 本人删单条（属主+租户双谓词；物理删——记忆无审计追责诉求，量级小） */
    @Delete("DELETE FROM dcs_assistant_memory WHERE tenant_id=#{tenantId}"
        + " AND user_id=#{userId} AND id=#{id}")
    int deleteOwn(@Param("tenantId") String tenantId, @Param("userId") Long userId,
                  @Param("id") Long id);
}
