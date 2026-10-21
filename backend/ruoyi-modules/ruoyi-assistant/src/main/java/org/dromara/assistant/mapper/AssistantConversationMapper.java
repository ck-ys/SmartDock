package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.assistant.domain.DcsAssistantConversation;

/**
 * 助手会话 Mapper（批G G2）。显式租户谓词红线同 {@link AssistantConfigMapper}。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantConversationMapper extends BaseMapper<DcsAssistantConversation> {

    /** thread 归属首绑（并发下唯一索引吸收竞态，同键异主由 service 校验拒 403） */
    @Insert("INSERT INTO dcs_assistant_conversation (id, tenant_id, user_id, thread_id, title, create_time)"
        + " VALUES (#{c.id}, #{c.tenantId}, #{c.userId}, #{c.threadId}, #{c.title}, CURRENT_TIMESTAMP)"
        + " ON CONFLICT (tenant_id, thread_id) DO NOTHING")
    int insertIgnoreThread(@Param("c") DcsAssistantConversation conversation);

    /** 按 (tenant, thread) 取会话（显式谓词） */
    @Select("SELECT * FROM dcs_assistant_conversation WHERE tenant_id=#{tenantId} AND thread_id=#{threadId} AND del_flag='0'")
    DcsAssistantConversation selectByThread(@Param("tenantId") String tenantId, @Param("threadId") String threadId);


    /** 归属校验取会话（本人可见才返回行） */
    @Select("SELECT * FROM dcs_assistant_conversation WHERE tenant_id=#{tenantId} AND id=#{id} AND user_id=#{userId} AND del_flag='0'")
    DcsAssistantConversation selectOwned(@Param("tenantId") String tenantId, @Param("id") Long id, @Param("userId") Long userId);

    /** 消息到达即刷新列表排序键 */
    @Update("UPDATE dcs_assistant_conversation SET last_message_time=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP"
        + " WHERE tenant_id=#{tenantId} AND id=#{id}")
    int touch(@Param("tenantId") String tenantId, @Param("id") Long id);

    /** 标题侧车 CAS 写回（ZCode 对齐 Q1，批A 2026-10-10）：仅当标题仍是初始截断值时覆盖——
     *  用户已重命名或已被写过时 0 行命中，静默放弃（零加列实现 titleSource 语义） */
    @Update("UPDATE dcs_assistant_conversation SET title=#{newTitle}, update_time=CURRENT_TIMESTAMP"
        + " WHERE tenant_id=#{tenantId} AND thread_id=#{threadId} AND title=#{expectedTitle} AND del_flag='0'")
    int updateTitleIfMatch(@Param("tenantId") String tenantId, @Param("threadId") String threadId,
                           @Param("expectedTitle") String expectedTitle, @Param("newTitle") String newTitle);

    /** TODO 清单整表替换落库（ZCode 对齐 Q2，批A；JSON 数组字符串） */
    @Update("UPDATE dcs_assistant_conversation SET todo_items=#{todoJson}, update_time=CURRENT_TIMESTAMP"
        + " WHERE tenant_id=#{tenantId} AND thread_id=#{threadId} AND del_flag='0'")
    int updateTodo(@Param("tenantId") String tenantId, @Param("threadId") String threadId,
                   @Param("todoJson") String todoJson);

    /** 保留期清理：按上限分批物理删（conversation>180 天） */
    @Delete("DELETE FROM dcs_assistant_conversation WHERE id IN (SELECT id FROM dcs_assistant_conversation"
        + " WHERE COALESCE(last_message_time, create_time) < (CURRENT_TIMESTAMP - INTERVAL '180 days') LIMIT #{batch})")
    int deleteExpired(@Param("batch") int batch);

    /** 租户清除通道：按 (tenant,user) 物理删 */
    @Delete("DELETE FROM dcs_assistant_conversation WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    int purgeUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /** 080/ISSUE-R13-3 修配套：清人前取该用户全部 thread（含已归档/软删行——悬挂缓存
     *  按_thread_键驻留，与行可见性无关）；purgeUser 删行后据此逐 thread 清内存缓存 */
    @Select("SELECT thread_id FROM dcs_assistant_conversation WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    java.util.List<String> threadIdsOfUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);
}
