package org.dromara.assistant.persist;

import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.core.AssistantInstanceContext;
import org.dromara.assistant.domain.DcsAssistantRun;
import org.dromara.assistant.mapper.AssistantRunMapper;
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
 * <p><b>多实例批 3 的收窄</b>：只救“确认已经死了”的会话——按 session_id 反解出
 * (租户,用户,thread)，查该 thread 最新 run：必须是 INTERRUPTED 且 owner_instance 非空、
 * 租约已过期。owner 还活着（另一台实例正在跑）或旧数据没有归属（无从判断）一律跳过。
 * 否则滚动重启时新实例会把另一台正在等用户确认的会话直接翻成 allowed，HITL 卡死。</p>
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
    /** 自愈：只翻指定会话的 asking→allowed（LIKE 双守卫=幂等，第二遍跑零行命中） */
    static final String SQL_RECOVER =
        "UPDATE agentscope.agentscope_sessions SET state_data ="
            + " replace(state_data, '\"state\":\"asking\"', '\"state\":\"allowed\"'),"
            + " updated_at = CURRENT_TIMESTAMP"
            + " WHERE session_id = ? AND state_key = 'agent_state'"
            + " AND state_data LIKE '%\"state\":\"asking\"%'";

    private final JdbcTemplate jdbcTemplate;
    private final AssistantRunMapper runMapper;
    private final AssistantInstanceContext instanceContext;

    public AssistantStaleAskingRecovery(JdbcTemplate jdbcTemplate,
                                        AssistantRunMapper runMapper,
                                        AssistantInstanceContext instanceContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.runMapper = runMapper;
        this.instanceContext = instanceContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        recoverGoneOwners();
    }

    /**
     * 扫描并翻转“死主”的 ASKING 会话。启动时跑一次；收尸 job 把 run 迁成
     * INTERRUPTED 后再调一次（否则启动时 run 还是 RUNNING 被跳过，之后没人再扫）。
     * 幂等：UPDATE 带 LIKE 双守卫，重复执行零行命中。
     */
    public void recoverGoneOwners() {
        try {
            List<String> stale = jdbcTemplate.queryForList(SQL_DETECT, String.class);
            if (stale.isEmpty()) {
                return;
            }
            int fixed = 0;
            for (String sessionId : stale) {
                SessionRef ref = parse(sessionId);
                if (ref == null) {
                    continue;
                }
                if (!ownerIsGone(ref)) {
                    continue;
                }
                fixed += jdbcTemplate.update(SQL_RECOVER, sessionId);
            }
            if (fixed > 0) {
                log.info("[dcs-assistant] 陈旧 ASKING 悬挂自愈：翻转 {} 个死主会话 asking→allowed（候选 {}）",
                    fixed, stale.size());
            } else {
                log.info("[dcs-assistant] 陈旧 ASKING 候选 {} 个，均为活主/无归属，跳过不碰", stale.size());
            }
        } catch (Exception e) {
            log.warn("[dcs-assistant] 陈旧 ASKING 悬挂自愈失败(不拦启动)：{} {}",
                e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 只有“最新 run 已 INTERRUPTED + 有归属 + 租约已死”才允许自愈。
     * 查不到 run、owner 为空、owner 还活着，一律不碰。
     */
    private boolean ownerIsGone(SessionRef ref) {
        List<DcsAssistantRun> latest =
            runMapper.selectByThread(ref.tenantId(), ref.threadId(), ref.userId(), 1);
        if (latest.isEmpty()) {
            return false;
        }
        DcsAssistantRun run = latest.get(0);
        // 三审回修（dspro-P2-3）：HITL 挂起期间流侧出错会落 FAILED，但持久态仍停在 asking
        // （错误分支明确不清缓存）——这种会话没有任何在飞工作，直接可救；否则重启也救不回来，
        // 之后每条消息都被框架「Agent is paused for human-in-the-loop」拒绝。
        if ("FAILED".equals(run.getStatus())) {
            return true;
        }
        if (!"INTERRUPTED".equals(run.getStatus())) {
            return false;
        }
        String owner = run.getOwnerInstance();
        if (owner == null || owner.isBlank()) {
            return false;
        }
        if (owner.equals(instanceContext.current())) {
            // 二审回修（dsflash-P2-8）：固定 instance.id 部署时，重启后同一 id 名下的旧进程
            // run 会被 isAlive 判成本机活着，ASKING 永不翻转。活动时间早于本进程启动=旧生命，
            // 按死主处理。
            java.util.Date last = run.getLastEventAt() != null ? run.getLastEventAt() : run.getCreateTime();
            return last != null && last.getTime() < instanceContext.startedAtMillis();
        }
        return !instanceContext.isAlive(owner);
    }

    /** session_id 形如 {@code t:{tenant}:{uid}:{threadId}}；解析失败返回 null（不碰） */
    static SessionRef parse(String sessionId) {
        if (sessionId == null || !sessionId.startsWith("t:")) {
            return null;
        }
        int p1 = sessionId.indexOf(':', 2);
        if (p1 <= 2) {
            return null;
        }
        int p2 = sessionId.indexOf(':', p1 + 1);
        if (p2 <= p1 + 1 || p2 == sessionId.length() - 1) {
            return null;
        }
        String tenantId = sessionId.substring(2, p1);
        Long userId;
        try {
            userId = Long.valueOf(sessionId.substring(p1 + 1, p2));
        } catch (NumberFormatException e) {
            return null;
        }
        return new SessionRef(tenantId, userId, sessionId.substring(p2 + 1));
    }

    /** 会话归属引用（纯解析结果，单测锚点） */
    record SessionRef(String tenantId, Long userId, String threadId) {
    }
}
