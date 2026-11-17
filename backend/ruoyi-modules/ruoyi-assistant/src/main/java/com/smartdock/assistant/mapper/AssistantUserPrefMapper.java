package com.smartdock.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.smartdock.assistant.domain.model.DcsAssistantUserPref;

/**
 * 常规偏好 Mapper（批C Q8 案1，2026-11-15）。
 *
 * <p>读写都带 tenant_id + user_id 双谓词；缺行=全部默认值。UPSERT 走唯一键
 * {@code uk_dcs_asst_user_pref (tenant_id, user_id)}，首写并发不撞键。</p>
 *
 * <p>注意：{@code @InterceptorIgnore} 在接口级生效，连 BaseMapper 的 insert/selectById 也绕过租户插件——
 * 本接口只允许调下面两个手写方法，禁止直接用 BaseMapper 方法（否则租户条件会丢）。</p>
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantUserPrefMapper extends BaseMapper<DcsAssistantUserPref> {

    /** 读本人偏好；无行返回 null（服务层填默认值） */
    @Select("SELECT * FROM dcs_assistant_user_pref WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    DcsAssistantUserPref selectOwn(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /** 全量覆盖写（并发安全 UPSERT） */
    @Update("INSERT INTO dcs_assistant_user_pref (id, tenant_id, user_id, send_key, stream_output, tools_open,"
        + " auto_retry, motion, answer_size, ask_auto_continue, create_time, update_time)"
        + " VALUES (#{id}, #{tenantId}, #{userId}, #{sendKey}, #{streamOutput}, #{toolsOpen},"
        + " #{autoRetry}, #{motion}, #{answerSize}, #{askAutoContinue}, #{now}, #{now})"
        + " ON CONFLICT (tenant_id, user_id) DO UPDATE SET"
        + " send_key=EXCLUDED.send_key, stream_output=EXCLUDED.stream_output, tools_open=EXCLUDED.tools_open,"
        + " auto_retry=EXCLUDED.auto_retry, motion=EXCLUDED.motion, answer_size=EXCLUDED.answer_size,"
        + " ask_auto_continue=EXCLUDED.ask_auto_continue, update_time=EXCLUDED.update_time")
    int upsertOwn(@Param("id") Long id, @Param("tenantId") String tenantId, @Param("userId") Long userId,
                  @Param("sendKey") String sendKey, @Param("streamOutput") String streamOutput,
                  @Param("toolsOpen") String toolsOpen, @Param("autoRetry") String autoRetry,
                  @Param("motion") String motion, @Param("answerSize") String answerSize,
                  @Param("askAutoContinue") String askAutoContinue, @Param("now") java.util.Date now);
}
