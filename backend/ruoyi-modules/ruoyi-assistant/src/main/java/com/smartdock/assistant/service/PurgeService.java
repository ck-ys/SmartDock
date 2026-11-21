package com.smartdock.assistant.service;

import com.smartdock.assistant.mapper.AssistantActionMapper;
import com.smartdock.assistant.mapper.AssistantAttachmentMapper;
import com.smartdock.assistant.mapper.AssistantCompactionMapper;
import com.smartdock.assistant.mapper.AssistantConversationMapper;
import com.smartdock.assistant.mapper.AssistantMessageMapper;
import com.smartdock.assistant.mapper.AssistantRunIssueMapper;
import com.smartdock.assistant.mapper.AssistantRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.redisson.client.codec.StringCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 助手数据清除与保留期清理（批 7 · 7C-2，2026-11-20 拍板 Q5 ★ 案 1）。
 *
 * <p><b>纯搬移</b>：方法与 {@code legacy.service.AssistantConversationService} 原同名方法逐行一致，
 * 只是换了包与依赖（三个实体三个 mapper 已随本刀搬进新四层）。</p>
 *
 * <p><b>两块内容</b>：</p>
 * <ol>
 *   <li><b>清人</b>（GDPR 式，{@link #purgeUser}）：一次事务删 7 张业务表；框架表
 *       {@code agentscope.agentscope_sessions} 必须<b>提交后</b>单独清（事务红线见方法注释），
 *       失败进 Redis 补偿队列由 {@code AssistantRetentionJob} 每分钟重试。</li>
 *   <li><b>保留期清理</b>（{@link #retentionSweep}）：message/issue 90 天，
 *       conversation/run/action/attachment/compaction 180 天，分批物理删。</li>
 * </ol>
 *
 * <p><b>事务红线（G2 实证，照旧）</b>：agentscope 清理绝不能进 {@code purgeUser} 的事务——
 * 该表缺失的环境上 DELETE 报 bad grammar，虽可被 try-catch 吞掉，但失败语句已把 PG 事务
 * 打成 aborted，COMMIT 静默变 ROLLBACK（PG 对 aborted 事务的 COMMIT 不抛错，只返回
 * ROLLBACK tag），七表删除全部无声回滚。故拆两步：事务内只做七表，提交后由编排方
 * （controller / job）调 {@link #purgeAgentscopeSessions}。</p>
 *
 * <p><b>不在本类</b>：会话/消息/run 的读写（→ {@link RunLedgerService} /
 * {@link MessageWriteService} / {@code query.ConversationQueryService}）；
 * 压缩审计行的写入（→ {@link MessageWriteService#appendCompactionRecord}）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurgeService {

    private final AssistantMessageMapper messageMapper;
    private final AssistantConversationMapper conversationMapper;
    private final AssistantRunMapper runMapper;
    private final AssistantActionMapper actionMapper;
    private final AssistantAttachmentMapper attachmentMapper;
    private final AssistantCompactionMapper compactionMapper;
    private final AssistantRunIssueMapper issueMapper;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 租户清除七表（spec §4.4/§15.7，GDPR 式），同事务原子。
     *
     * <p>agentscope 框架表不在本事务内，理由见类注释「事务红线」。提交后由编排方调
     * {@link #purgeAgentscopeSessions}。快照归档 7 天=P2 冷备（登记）；操作留痕走端点 @Log。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public int purgeUser(String tenantId, Long userId) {
        int msgs = messageMapper.purgeUser(tenantId, userId);
        int convs = conversationMapper.purgeUser(tenantId, userId);
        int runs = runMapper.purgeUser(tenantId, userId);
        int acts = actionMapper.purgeUser(tenantId, userId);
        // 083/ISSUE-F14-02 修：清人补附件行（原四表缺附件=GDPR 半清除态；OSS 对象
        // 物理删除涉及文件中心全局行为，另立登记不并入本刀）
        int atts = attachmentMapper.purgeUser(tenantId, userId);
        // 批A 回修（三审 P1）：压缩记录同批清除——summary 列含对话派生内容，清人不得残留。
        // 二审 P1 回修：先探表再删——「先部署后跑 migration」的窗口里表不存在时，
        // BadSqlGrammar 会把整个清人事务打回滚（五表一条不删、接口 500），必须防御
        int compacts = compactionTableExists() ? compactionMapper.purgeUser(tenantId, userId) : 0;
        // 2026-11-11 Q1-3：问题行同批清除（detail 含服务商报错派生内容）；同款先探表防御
        int issues = issueTableExists() ? issueMapper.purgeUser(tenantId, userId) : 0;
        log.info("[AssistantPersist] 租户清除 tenant={} user={}: message={} conversation={} run={} action={} attachment={} compaction={} issue={}",
            tenantId, userId, msgs, convs, runs, acts, atts, compacts, issues);
        return msgs + convs + runs + acts + atts + compacts + issues;
    }

    /** assistant_compaction 是否已建表（同事务只读探测，异常按不存在处理） */
    private boolean compactionTableExists() {
        try {
            Boolean ok = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.assistant_compaction') IS NOT NULL", Boolean.class);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            return false;
        }
    }

    /** assistant_run_issue 是否已建表（同款先探表防御，见 compactionTableExists 注释） */
    private boolean issueTableExists() {
        try {
            Boolean ok = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.assistant_run_issue') IS NOT NULL", Boolean.class);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * agentscope 官方会话清理（purgeUser 提交后调用；表缺失环境仅日志不阻塞）。
     * session_id = "userId:sessionId" 拼接（§4.4 P0 实证），userId 本身是
     * "t:{tenantId}:{uid}" 前缀形态 → LIKE 前缀精确圈定本人。
     *
     * <p>二站② AU-42：失败不再只留日志——登记进 Redis 补偿集合，由
     * {@code legacy.persist.AssistantRetentionJob} 每分钟重试，
     * 防「七表已删+框架表残留」的 GDPR 半清除态复活旧历史。重试成功即出队。</p>
     */
    public void purgeAgentscopeSessions(String tenantId, Long userId) {
        try {
            jdbcTemplate.update("DELETE FROM agentscope.agentscope_sessions WHERE session_id LIKE ?",
                "t:" + tenantId + ":" + userId + ":%");
        } catch (Exception e) {
            log.warn("[AssistantPersist] agentscope 会话清理失败(表缺失或仅剩框架表残留), 已入补偿队列: {}", e.getMessage());
            enqueuePurgeCompensation(tenantId, userId);
        }
    }

    /** 补偿队列键（裸键，PD-2 契约：与配额键同走 ignore） */
    static final String PURGE_COMPENSATE_KEY = "assistant:purge:compensate";

    private void enqueuePurgeCompensation(String tenantId, Long userId) {
        try {
            String entry = tenantId + ":" + userId;
            TenantHelper.ignore(() -> {
                org.redisson.api.RSet<String> queue = RedisUtils.getClient()
                    .getSet(PURGE_COMPENSATE_KEY, StringCodec.INSTANCE);
                return queue.add(entry);
            });
        } catch (Exception redisDown) {
            log.error("[AssistantPersist] 补偿登记也失败(Redis 不可用), 残留待人工核查 tenant={} user={}",
                tenantId, userId, redisDown);
        }
    }

    /**
     * 二站② AU-42：补偿重试单条（RetentionJob 每分钟调）——重跑 agentscope 清理，
     * 成功出队；仍失败留队下轮再来（表缺失环境=永久留队无害，量级=失败次数）。
     *
     * @return true=本条已清干净
     */
    public boolean retryPurgeAgentscopeSessions(String tenantId, Long userId) {
        try {
            jdbcTemplate.update("DELETE FROM agentscope.agentscope_sessions WHERE session_id LIKE ?",
                "t:" + tenantId + ":" + userId + ":%");
            return true;
        } catch (Exception e) {
            log.warn("[AssistantPersist] agentscope 补偿重试仍失败: {}", e.getMessage());
            return false;
        }
    }

    /** 二站② AU-42：补偿队列快照（RetentionJob 读；["tenantId:userId"]） */
    public Set<String> purgeCompensationQueue() {
        try {
            return TenantHelper.ignore(() -> {
                org.redisson.api.RSet<String> queue = RedisUtils.getClient()
                    .getSet(PURGE_COMPENSATE_KEY, StringCodec.INSTANCE);
                return queue.readAll();
            });
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** 二站② AU-42：补偿出队（单条成功后调） */
    public void dequeuePurgeCompensation(String entry) {
        try {
            TenantHelper.ignore(() -> {
                org.redisson.api.RSet<String> queue = RedisUtils.getClient()
                    .getSet(PURGE_COMPENSATE_KEY, StringCodec.INSTANCE);
                return queue.remove(entry);
            });
        } catch (Exception ignored) {
            // 出队失败=下轮重试幂等无害
        }
    }

    /** 保留期清理（message/issue 90 天；conversation·run·action·attachment·compaction 180 天），分批物理删 */
    public void retentionSweep(int batch) {
        int total = 0;
        int n;
        do {
            n = messageMapper.deleteExpired(batch);
            total += n;
        } while (n >= batch);
        log.info("[AssistantPersist] 保留期清理 message 删除 {} 行", total);
        int convs = 0;
        do {
            n = conversationMapper.deleteExpired(batch);
            convs += n;
        } while (n >= batch);
        int runs = 0;
        do {
            n = runMapper.deleteExpired(batch);
            runs += n;
        } while (n >= batch);
        int acts = 0;
        do {
            n = actionMapper.deleteExpired(batch);
            acts += n;
        } while (n >= batch);
        // 083/ISSUE-F14-02 修：附件行纳入保留期清理（原版 deleteExpired 是死代码零调用方，
        // 表+307KB 级 parsed_text 永久累积）；与 conversation 同 180 天口径
        int atts = 0;
        do {
            n = attachmentMapper.deleteExpired(batch);
            atts += n;
        } while (n >= batch);
        // 批A 回修（三审 P1）：压缩记录纳入保留期清理（180 天口径，防审计行无界增长）。
        // 二审 P1 回修：表缺失环境（先部署后迁移）不让每日 job 整体报错
        int compacts = 0;
        if (compactionTableExists()) {
            do {
                n = compactionMapper.deleteExpired(batch);
                compacts += n;
            } while (n >= batch);
        }
        // 2026-11-11 Q1-3：问题表 90 天清理（拍板原文「加个定时清理」）——与消息表同期，
        // 先探表防「先部署后迁移」窗口炸每日 job
        int issues = 0;
        if (issueTableExists()) {
            do {
                n = issueMapper.deleteExpired(batch);
                issues += n;
            } while (n >= batch);
        }
        log.info("[AssistantPersist] 保留期清理 conversation={} run={} action={} attachment={} compaction={} 行(180 天) issue={} 行(90 天)",
            convs, runs, acts, atts, compacts, issues);
    }

    /** 080/ISSUE-R13-3 修配套：清人前的该用户 thread 清单（悬挂缓存/步骤缓冲按 thread
     *  键驻留内存，purgeUser 删行后控制器据此逐 thread 清缓存） */
    public List<String> threadIdsOfUser(String tenantId, Long userId) {
        return conversationMapper.threadIdsOfUser(tenantId, userId);
    }
}
