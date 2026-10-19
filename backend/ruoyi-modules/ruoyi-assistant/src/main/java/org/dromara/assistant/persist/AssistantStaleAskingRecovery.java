package org.dromara.assistant.persist;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 持久态陈旧 ASKING 悬挂自愈（业务批7 Q16-①，2026-10-14）。
 *
 * <p><b>病灶</b>（javap 反编译 io.agentscope 2.0.3 实证）：联网确认挂起 = 持久态
 * tool_use 块 {@code "state":"asking"}；新消息进入 ReActAgent$CallExecution.doCallInner
 * 时 ASKING 分支在 pending 恢复开关之前硬抛「Agent is paused for human-in-the-loop…」
 * 英文错。进程内悬挂由 {@link AssistantResumeUnlockFilter} 注入 resume 解；但<b>重启后</b>
 * 框架 AguiResumeCoordinator（进程内存）与悬挂缓存双空、持久态还在——旧 interruptId
 * 注入反撞「does not match any open interrupt」，resume 路线整个走不通。</p>
 *
 * <p><b>解法</b>：启动期（web 端口放行前，SmartInitializingSingleton 先于 finishRefresh
 * 的服务器启动）把持久态 asking 翻成 allowed——恢复出的 pending 随即落入
 * {@code enablePendingToolRecovery} 官方恢复分支（AgentFactory 已开）：合成工具失败
 * 结果→会话自愈，模型自行决定重试或直接作答。</p>
 *
 * <p><b>JSON 结构级安全</b>：字符串值内引号必转义为 {@code \"}，字面量
 * {@code "state":"asking"} 只可能出现在结构层，replace 不会误伤用户/思考文本；
 * version 列不动（JdbcAgentStateStore updateIfVersion 按现值匹配）。幂等：重复执行
 * 零行命中。失败仅告警不拦启动（毒化会话退化为旧行为=英文错，不比没有本类更糟）。</p>
 */
@Slf4j
@Component
public class AssistantStaleAskingRecovery implements SmartInitializingSingleton {

    /** 检出：含陈旧 asking 块的会话（报读 session_id 供日志溯源） */
    static final String SQL_DETECT =
        "SELECT session_id FROM agentscope.agentscope_sessions"
            + " WHERE state_key = 'agent_state' AND state_data LIKE '%\"state\":\"asking\"%'";
    /** 自愈：asking→allowed（LIKE 双守卫=幂等，第二遍跑零行命中） */
    static final String SQL_RECOVER =
        "UPDATE agentscope.agentscope_sessions SET state_data ="
            + " replace(state_data, '\"state\":\"asking\"', '\"state\":\"allowed\"'),"
            + " updated_at = CURRENT_TIMESTAMP"
            + " WHERE state_key = 'agent_state' AND state_data LIKE '%\"state\":\"asking\"%'";

    private final JdbcTemplate jdbcTemplate;

    public AssistantStaleAskingRecovery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            List<String> stale = jdbcTemplate.queryForList(SQL_DETECT, String.class);
            if (stale.isEmpty()) {
                return;
            }
            int fixed = jdbcTemplate.update(SQL_RECOVER);
            log.info("[dcs-assistant] 陈旧 ASKING 悬挂自愈：翻转 {} 个会话 asking→allowed {}",
                fixed, stale);
        } catch (Exception e) {
            log.warn("[dcs-assistant] 陈旧 ASKING 悬挂自愈失败(不拦启动)：{} {}",
                e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
