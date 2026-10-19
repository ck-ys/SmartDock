package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.assistant.domain.DcsAssistantMessage;

/**
 * 助手对话原文 Mapper（批G G2）。保留期 90 天；purge 一条龙成员之一。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantMessageMapper extends BaseMapper<DcsAssistantMessage> {

    /** 历史消息分页读取（本人会话；id 升序=时间序） */
    @Select("SELECT * FROM dcs_assistant_message WHERE tenant_id=#{tenantId} AND conversation_id=#{conversationId}"
        + " AND user_id=#{userId} ORDER BY id ASC LIMIT #{limit} OFFSET #{offset}")
    java.util.List<DcsAssistantMessage> selectPageOwned(@Param("tenantId") String tenantId,
        @Param("conversationId") Long conversationId, @Param("userId") Long userId,
        @Param("limit") int limit, @Param("offset") int offset);

    /** 保留期清理：message>90 天分批物理删（X-04：副本同批删由 job 编排） */
    @Delete("DELETE FROM dcs_assistant_message WHERE id IN (SELECT id FROM dcs_assistant_message"
        + " WHERE create_time < (CURRENT_TIMESTAMP - INTERVAL '90 days') LIMIT #{batch})")
    int deleteExpired(@Param("batch") int batch);

    /** 租户清除通道成员：按会话集删（先查会话归属再删，service 编排） */
    @Delete("DELETE FROM dcs_assistant_message WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    int purgeUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /** 本会话最新一条 assistant 消息（批5 Q7-3：前端 done 后拉追问建议，messageId+followups；
     *  批B B2：补 total_tokens 一列——status 端点带 threadId 时透出上下文占用（环数据源）；
     *  三席审查：仍只取小列，避免每次拉取全量 content/tool_steps 大字段 */
    @Select("SELECT id, followup_suggestions, total_tokens FROM dcs_assistant_message WHERE tenant_id=#{tenantId}"
        + " AND conversation_id=#{conversationId} AND user_id=#{userId} AND role='assistant'"
        + " ORDER BY id DESC LIMIT 1")
    DcsAssistantMessage selectLatestAssistant(@Param("tenantId") String tenantId,
        @Param("conversationId") Long conversationId, @Param("userId") Long userId);

    /** 批B B1 修：环专用——最新一条 total_tokens 非空的 assistant 行（单行单列）。
     *  与 selectLatestAssistant 刻意不同口径：truncated/中断行 tokens 恒空，若取「最新行」
     *  会被空值行遮蔽，环从有效读数跌回「待测」；此处取「最新有值行」=cline 语义
     *  （最近一次可测量的上下文占用），全空会话返回 null 走环空态 */
    @Select("SELECT total_tokens FROM dcs_assistant_message WHERE tenant_id=#{tenantId}"
        + " AND conversation_id=#{conversationId} AND user_id=#{userId} AND role='assistant'"
        + " AND total_tokens IS NOT NULL ORDER BY id DESC LIMIT 1")
    Long selectLatestContextTokens(@Param("tenantId") String tenantId,
        @Param("conversationId") Long conversationId, @Param("userId") Long userId);

    /** 本会话最新一条 user 提问（批5 Q7-3 回修：DESC LIMIT 1 单行单列——七席共识 P1，
     *  原实现借 selectPageOwned ASC 拉最旧 500 行倒扫，>500 条会话会取到远古问题） */
    @Select("SELECT content FROM dcs_assistant_message WHERE tenant_id=#{tenantId}"
        + " AND conversation_id=#{conversationId} AND user_id=#{userId} AND role='user'"
        + " ORDER BY id DESC LIMIT 1")
    String selectLatestUserContent(@Param("tenantId") String tenantId,
        @Param("conversationId") Long conversationId, @Param("userId") Long userId);

    /** 侧车回写追问建议（批5 Q7-3：回答完成后异步 UPDATE，失败=保留空列回退静态池；
     *  tenant_id 谓词=三席共识纵深防御——雪花全局唯一本无越权面，与同 mapper 风格对齐） */
    @Update("UPDATE dcs_assistant_message SET followup_suggestions=#{suggestions}"
        + " WHERE id=#{id} AND tenant_id=#{tenantId}")
    int updateFollowupSuggestions(@Param("id") Long id, @Param("tenantId") String tenantId,
        @Param("suggestions") String suggestions);

    /** 批6-#2：partial 行原地更新/终局化（content/toolSteps 过 PII 掩码后由调用方传入；
     *  COALESCE 空值=保留原值——收割竞态下缓冲已清时仅转 end_reason 不抹内容） */
    @Update("UPDATE dcs_assistant_message SET content=COALESCE(#{content}, content),"
        + " tool_steps=COALESCE(#{toolSteps}, tool_steps), end_reason=#{endReason}"
        + " WHERE id=#{id} AND tenant_id=#{tenantId} AND role='assistant' AND end_reason='partial'")
    int updatePartial(@Param("id") Long id, @Param("tenantId") String tenantId,
        @Param("content") String content, @Param("toolSteps") String toolSteps,
        @Param("endReason") String endReason);

    /** 批6-#2：终道路径删本 run 的 partial 行（真实段落行落库前清重叠内容；
     *  条件 end_reason='partial' 防误删已终局化行 */
    @Delete("DELETE FROM dcs_assistant_message WHERE id=#{id} AND tenant_id=#{tenantId}"
        + " AND role='assistant' AND end_reason='partial'")
    int deletePartial(@Param("id") Long id, @Param("tenantId") String tenantId);
}
