package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.assistant.domain.DcsAssistantRunIssue;

/**
 * 助手「这轮为什么没回答」问题表 Mapper（2026-11-11 拍板 Q1-3）。
 * 与 run/message 同款：@InterceptorIgnore + SQL 自带租户谓词（流线程无登录上下文）。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantRunIssueMapper extends BaseMapper<DcsAssistantRunIssue> {

    /** 终态写成功后落问题行（id 雪花由调用方生成）。
     * ON CONFLICT 兜底「同 run 只落一条」（终审 P1）：两条写入路当前互斥靠的是
     * BETA1 不发 RUN_ERROR 这一框架行为，升级后可能双写，靠 run_id 唯一索引锁死。 */
    @Insert("INSERT INTO dcs_assistant_run_issue (id, tenant_id, user_id, conversation_id, thread_id, run_id,"
        + " message_id, code, detail, create_time) VALUES (#{i.id}, #{i.tenantId}, #{i.userId}, #{i.conversationId},"
        + " #{i.threadId}, #{i.runId}, #{i.messageId}, #{i.code}, #{i.detail}, CURRENT_TIMESTAMP)"
        + " ON CONFLICT (run_id) DO NOTHING")
    int insertIssue(@Param("i") DcsAssistantRunIssue issue);

    /** 按 runId 取最新一条（活体：空回答卡延时拉取） */
    @Select("SELECT * FROM dcs_assistant_run_issue WHERE run_id=#{runId} ORDER BY id DESC LIMIT 1")
    DcsAssistantRunIssue selectLatestByRunId(@Param("runId") String runId);

    /** 按会话取全部问题行（历史回放，按落库序） */
    @Select("SELECT * FROM dcs_assistant_run_issue WHERE tenant_id=#{tenantId} AND user_id=#{userId}"
        + " AND conversation_id=#{conversationId} ORDER BY id ASC")
    java.util.List<DcsAssistantRunIssue> selectByConversation(@Param("tenantId") String tenantId,
        @Param("userId") Long userId, @Param("conversationId") Long conversationId);

    /** 保留期清理：90 天口径（消息表为 180 天，本表更短），分批物理删 */
    @Delete("DELETE FROM dcs_assistant_run_issue WHERE id IN (SELECT id FROM dcs_assistant_run_issue"
        + " WHERE create_time < (CURRENT_TIMESTAMP - INTERVAL '90 days') LIMIT #{batch})")
    int deleteExpired(@Param("batch") int batch);

    /** 租户清除通道成员 */
    @Delete("DELETE FROM dcs_assistant_run_issue WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    int purgeUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);
}
