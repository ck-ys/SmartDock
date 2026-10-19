package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.assistant.domain.DcsAssistantFavorite;

import java.util.List;

/**
 * 助手常用问句收藏 Mapper（C2 收藏跨设备，2026-10-07）。显式租户谓词红线同
 * {@link AssistantConversationMapper}（@InterceptorIgnore + SQL 显式 tenant_id）。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantFavoriteMapper extends BaseMapper<DcsAssistantFavorite> {

    /** 用户收藏清单（新→旧；属主+租户双谓词） */
    @Select("SELECT * FROM dcs_assistant_favorite WHERE tenant_id=#{tenantId}"
        + " AND user_id=#{userId} AND del_flag='0' ORDER BY id DESC")
    List<DcsAssistantFavorite> listByUser(@Param("tenantId") String tenantId,
                                          @Param("userId") Long userId);

    /** 按内容精确删（chips ✕ 删；唯一索引 (tenant,user,content) 兜底） */
    @Delete("DELETE FROM dcs_assistant_favorite WHERE tenant_id=#{tenantId}"
        + " AND user_id=#{userId} AND content=#{content}")
    int deleteByContent(@Param("tenantId") String tenantId,
                        @Param("userId") Long userId,
                        @Param("content") String content);
}
