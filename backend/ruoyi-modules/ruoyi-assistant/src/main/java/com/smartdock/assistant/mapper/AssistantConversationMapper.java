package com.smartdock.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.smartdock.assistant.domain.model.AssistantConversation;

import java.util.List;

/**
 * 助手会话 Mapper（批G G2）。显式租户谓词红线同 {@code AssistantConfigMapper}。
 *
 * <p>本接口 2026-11-20 由 legacy.mapper 原样迁入（批 7 · 7B，Q2 ★ 按用途三分）：新根
 * query / meta 服务直接用它，规则 8 禁止新根 service/query import legacy。legacy 侧
 * 各服务改为 import 本接口（legacy→新根是合法方向）。</p>
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantConversationMapper extends BaseMapper<AssistantConversation> {

    /** thread 归属首绑（并发下唯一索引吸收竞态，同键异主由 service 校验拒 403） */
    @Insert("INSERT INTO assistant_conversation (id, tenant_id, user_id, thread_id, title, create_time)"
        + " VALUES (#{c.id}, #{c.tenantId}, #{c.userId}, #{c.threadId}, #{c.title}, CURRENT_TIMESTAMP)"
        + " ON CONFLICT (tenant_id, thread_id) DO NOTHING")
    int insertIgnoreThread(@Param("c") AssistantConversation conversation);

    /** 按 (tenant, thread) 取会话（显式谓词） */
    @Select("SELECT * FROM assistant_conversation WHERE tenant_id=#{tenantId} AND thread_id=#{threadId} AND del_flag='0'")
    AssistantConversation selectByThread(@Param("tenantId") String tenantId, @Param("threadId") String threadId);


    /** 归属校验取会话（本人可见才返回行） */
    @Select("SELECT * FROM assistant_conversation WHERE tenant_id=#{tenantId} AND id=#{id} AND user_id=#{userId} AND del_flag='0'")
    AssistantConversation selectOwned(@Param("tenantId") String tenantId, @Param("id") Long id, @Param("userId") Long userId);

    /**
     * 消息到达即刷新列表排序键。
     *
     * <p>批 10（2026-11-22）补 {@code user_id} 谓词：原先只有 tenant+id。核实过三条生产调用链
     * （{@code AssistantRunRecorder} 的两处、{@code AssistantCompactionMiddleware} 的
     * {@code resolve}、{@code AutomationRunner}），conversationId 一律来自 run 登记行或
     * {@code conversationByThread} 的服务端查询，**没有一条来自客户端入参**——所以这是
     * <b>纵深防御缺失，不是可利用的越权</b>。补谓词的理由是别让它成为将来某条新调用链的陷阱：
     * 排序键被异主刷新本身不泄数据，但「按 id 就能改别人的行」这个形态不该留在 mapper 上。</p>
     *
     * <p>批 10 多审 SQL 席 P2（2026-11-22）：补 {@code del_flag='0'} 与同文件
     * {@link #selectByThread} / {@link #selectOwned} / {@link #updateTitleIfMatch} /
     * {@link #updateTodo} 的谓词形态对齐。当前能到达本方法的链路其会话行必然未删
     * （上游查询全部带该谓词），故无行为变化；补齐是为了消除「同文件五种写法」的形态不一致——
     * 软删行被迟到消息刷新排序键还会推迟 {@link #deleteExpired} 的物理清理。</p>
     */
    @Update("UPDATE assistant_conversation SET last_message_time=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP"
        + " WHERE tenant_id=#{tenantId} AND id=#{id} AND user_id=#{userId} AND del_flag='0'")
    int touch(@Param("tenantId") String tenantId, @Param("id") Long id, @Param("userId") Long userId);

    /** 标题侧车 CAS 写回（ZCode 对齐 Q1，批A 2026-10-10）：仅当标题仍是初始截断值时覆盖——
     *  用户已重命名或已被写过时 0 行命中，静默放弃（零加列实现 titleSource 语义） */
    @Update("UPDATE assistant_conversation SET title=#{newTitle}, update_time=CURRENT_TIMESTAMP"
        + " WHERE tenant_id=#{tenantId} AND thread_id=#{threadId} AND title=#{expectedTitle} AND del_flag='0'")
    int updateTitleIfMatch(@Param("tenantId") String tenantId, @Param("threadId") String threadId,
                           @Param("expectedTitle") String expectedTitle, @Param("newTitle") String newTitle);

    /** TODO 清单整表替换落库（ZCode 对齐 Q2，批A；JSON 数组字符串） */
    @Update("UPDATE assistant_conversation SET todo_items=#{todoJson}, update_time=CURRENT_TIMESTAMP"
        + " WHERE tenant_id=#{tenantId} AND thread_id=#{threadId} AND del_flag='0'")
    int updateTodo(@Param("tenantId") String tenantId, @Param("threadId") String threadId,
                   @Param("todoJson") String todoJson);

    /** 保留期清理：按上限分批物理删（conversation>180 天） */
    @Delete("DELETE FROM assistant_conversation WHERE id IN (SELECT id FROM assistant_conversation"
        + " WHERE COALESCE(last_message_time, create_time) < (CURRENT_TIMESTAMP - INTERVAL '180 days') LIMIT #{batch})")
    int deleteExpired(@Param("batch") int batch);

    /** 租户清除通道：按 (tenant,user) 物理删 */
    @Delete("DELETE FROM assistant_conversation WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    int purgeUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /** 080/ISSUE-R13-3 修配套：清人前取该用户全部 thread（含已归档/软删行——悬挂缓存
     *  按_thread_键驻留，与行可见性无关）；purgeUser 删行后据此逐 thread 清内存缓存 */
    @Select("SELECT thread_id FROM assistant_conversation WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    List<String> threadIdsOfUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);
}
