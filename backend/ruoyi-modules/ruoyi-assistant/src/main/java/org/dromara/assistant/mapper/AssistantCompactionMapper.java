package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.assistant.domain.DcsAssistantCompaction;

/**
 * 自动压缩记录 Mapper（批A-2）。表带 tenant_id，SQL 显式租户条件 + 关闭租户插件注入。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantCompactionMapper extends BaseMapper<DcsAssistantCompaction> {

    /** 上一条同会话压缩记录（边界链指针）。单实例同 thread 由框架串行化；
     *  多实例并发可能取到同值造成链分叉（三审 P2，防御缺口已注释不设锁） */
    @Select("SELECT MAX(id) FROM dcs_assistant_compaction WHERE tenant_id=#{tenantId}"
        + " AND conversation_id=#{conversationId}")
    Long selectPrevId(@Param("tenantId") String tenantId, @Param("conversationId") Long conversationId);

    /** GDPR 清人：物理删该用户全部压缩记录（三审 P1 回修——summary 含对话派生内容，不得残留） */
    @Delete("DELETE FROM dcs_assistant_compaction WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    int purgeUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /** 随会话保留期清理：按上限分批物理删（与 conversation 同 180 天口径，防审计行无界增长） */
    @Delete("DELETE FROM dcs_assistant_compaction WHERE id IN (SELECT id FROM dcs_assistant_compaction"
        + " WHERE create_time < (CURRENT_TIMESTAMP - INTERVAL '180 days') LIMIT #{batch})")
    int deleteExpired(@Param("batch") int batch);

    /** 状态卡压缩账单（题2 案1）：该会话最近一次压缩（LIMIT 1 按主键序=最新）。
     *  只取成功行（status='SUCCESS'）——FAILED 行上下文照样收缩但摘要不可信，不上账单。
     *  三审回修（席2/席1 P2）：改走已索引的 (tenant_id, conversation_id, id)，不用无索引的
     *  thread_id 前缀扫；显式列不回传 summary（6000 字大字段，账单只要 4 个数） */
    @Select("SELECT id, before_tokens, after_tokens, create_time FROM dcs_assistant_compaction"
        + " WHERE tenant_id=#{tenantId} AND conversation_id=#{conversationId}"
        + " AND status='SUCCESS' ORDER BY id DESC LIMIT 1")
    DcsAssistantCompaction selectLatestByConversation(@Param("tenantId") String tenantId,
                                                      @Param("conversationId") Long conversationId);

    /** 该会话累计成功压缩次数（账单行「共 N 次」；同上走已索引键） */
    @Select("SELECT COUNT(*) FROM dcs_assistant_compaction WHERE tenant_id=#{tenantId}"
        + " AND conversation_id=#{conversationId} AND status='SUCCESS'")
    int countOkByConversation(@Param("tenantId") String tenantId,
                              @Param("conversationId") Long conversationId);
}
