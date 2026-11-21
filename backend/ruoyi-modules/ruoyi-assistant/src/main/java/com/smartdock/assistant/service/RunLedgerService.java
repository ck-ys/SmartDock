package com.smartdock.assistant.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.smartdock.assistant.domain.gateway.InstanceIdentityGateway;
import com.smartdock.assistant.domain.model.AssistantConversation;
import com.smartdock.assistant.domain.model.AssistantRun;
import com.smartdock.assistant.domain.model.AssistantRunIssue;
import com.smartdock.assistant.mapper.AssistantConversationMapper;
import com.smartdock.assistant.mapper.AssistantMessageMapper;
import com.smartdock.assistant.mapper.AssistantRunIssueMapper;
import com.smartdock.assistant.mapper.AssistantRunMapper;
import com.smartdock.assistant.support.AssistantPiiFilter;
import com.smartdock.assistant.support.AssistantRunIssues;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * run 台账（批 7 · 7C-1，2026-11-20 拍板 Q1 ★ 案 1 + 批主审计修正 Q3）。
 *
 * <p><b>纯搬移</b>：16 个方法与 {@code AssistantConversationService} 原同名方法逐行一致
 * （SQL 全在 mapper 上，本类只做编排）。三处语义承重，改之前先看注释：
 * ① {@link #registerRun} 的归属校验（runId 异主 403）；
 * ② {@link #persistUserRound} 的三步同事务 + 线程发送锁顺序（先锁再插行再查在途，
 *    READ COMMITTED 下换顺序会让两个并发事务双双过门）；
 * ③ {@link #recordRunIssue} 自吞异常（问题行是锦上添花，绝不连坐终态链）。</p>
 *
 * <p><b>为什么 persistUserRound 在这里而不是 MessageWriteService</b>（批主审计 Q3 修正）：
 * 它做的是 thread 发送锁 → 首绑 → run 登记 → 在途计数 → 落 user 消息，五步里四步是台账；
 * 落在消息写服务会让「一轮的准入判定」与「一轮的三表写」分居两地，读代码的人要在两个类之间
 * 拼出并发语义。user 消息本身经 {@link MessageWriteService#appendUserMessage} 落库。</p>
 *
 * <p><b>跨线依赖</b>：归属实例 id 经 {@link InstanceIdentityGateway} 读（规则 8 禁 import legacy，
 * 实例身份整套仍在引擎侧）；PII 脱敏走新根 {@link AssistantPiiFilter}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunLedgerService {

    private final AssistantConversationMapper conversationMapper;
    private final AssistantMessageMapper messageMapper;
    private final AssistantRunMapper runMapper;
    private final AssistantRunIssueMapper issueMapper;
    private final AssistantPiiFilter piiFilter;
    private final InstanceIdentityGateway instanceIdentity;
    private final MessageWriteService messageWriteService;

    // ------------------------------------------------------------------ 首绑与登记

    /**
     * thread 归属首绑（G4 Ownership 处置链）：返回会话 id。
     * 同 thread 异主 → 403（不区分"不存在"与"无权"——防探测）。
     */
    public Long ensureConversation(String tenantId, Long userId, String threadId, String title) {
        AssistantConversation exists = conversationMapper.selectByThread(tenantId, threadId);
        if (exists == null) {
            AssistantConversation row = new AssistantConversation();
            row.setId(IdWorker.getId());
            row.setTenantId(tenantId);
            row.setUserId(userId);
            row.setThreadId(threadId);
            row.setTitle(title);
            row.setStatus("ACTIVE");
            row.setDelFlag("0");
            conversationMapper.insertIgnoreThread(row);
            exists = conversationMapper.selectByThread(tenantId, threadId);
        }
        if (exists == null || !exists.getUserId().equals(userId)) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        // 归档会话拒写（批H P1-2）：「只读回放」后端强制——ARCHIVED 会话发消息 403，恢复后放行
        if ("ARCHIVED".equals(exists.getStatus())) {
            throw new ServiceException("会话已归档（只读回放），请先恢复会话后再发消息", 403);
        }
        return exists.getId();
    }

    /**
     * run 登记制（S-04①）：首现落库绑定归属；重放同 runId 异主/异租户 → 403。
     *
     * @return 本次是否新建 run 行（false=同主重放，user 消息不重复落库）
     */
    public boolean registerRun(String tenantId, Long userId, String threadId, String runId) {
        if (runId == null || runId.isBlank()) {
            return true;
        }
        AssistantRun row = new AssistantRun();
        row.setId(IdWorker.getId());
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setThreadId(threadId);
        row.setRunId(runId);
        row.setOwnerInstance(instanceIdentity.currentInstanceId());
        int inserted = runMapper.insertIgnoreRun(row);
        AssistantRun bound = runMapper.selectByRunId(runId);
        if (bound != null && (!bound.getUserId().equals(userId) || !bound.getTenantId().equals(tenantId))) {
            throw new ServiceException("请求不合法（runId 归属校验失败）", 403);
        }
        // 二审回修（dspro-P2-1）：这里原本给重放的 RUNNING 行刷 owner，但 persistUserRound
        // 在 !freshRun 时立刻抛 409 → 事务回滚，刷了也白刷；runId 每轮新生成，正常路径不存在
        // 换主重放。删掉这次无效 UPDATE，别让维护者误以为故障转移已由这里兜住。
        return inserted > 0;
    }

    /**
     * user 侧一轮三步同事务（dspro P2-8 修）：首绑+run 登记+user 消息原子落库——
     * 各自 autocommit 会在 PG 闪断时留"空壳会话"（会话行在、消息与 run 不在）。
     * resolver 的 DataAccessException 降级捕获语义不变（异常整体回滚=零残留）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void persistUserRound(String tenantId, Long userId, String threadId, String runId,
                                 String title, String content) {
        // 题5 三审回修（dsflash-P1/glm53-P2 共识）：先取同 thread 发送串行锁再「插行+查在途」
        // ——否则 READ COMMITTED 下两个并发事务互看不见对方未提交的 RUNNING 行，双双过门
        // =双流并发写同一 AgentSession（recorder 以 threadId 为键交错+双扣费）。锁事务级自动释放
        runMapper.acquireThreadSendLock(tenantId, userId, threadId);
        Long conversationId = ensureConversation(tenantId, userId, threadId, title);
        boolean freshRun = registerRun(tenantId, userId, threadId, runId);
        // runId 重放幂等（dsflash P1-3 收尾）+ goal 加固 H1：同主重放=断线重试重发同 runId——
        // 此前只挡 user 消息双落，agent 照跑第二遍＝全额双扣费+assistant 消息可双落
        // （计费幂等键含实例段+seq，重跑即新键；消息表无 run 级唯一约束）。
        // run 表已有行=第一发确实到过后端（可能仍在跑或已被断连取消），一律拒绝重跑；
        // 框架把 resolver 异常转 RUN_ERROR 帧（javap 实证），前端按错误收口不再自动重试
        if (!freshRun) {
            throw new ServiceException("检测到重复请求（网络重连触发），已拦截以防重复扣费。"
                + "上一轮可能仍在处理，稍候刷新对话查看；若未收到回答请重新发送", 409);
        }
        // 题5 深审 P1-1 回修（dspro/glm53 共识，2026-10-28）：断连不拆链桥接上线后，框架
        // resume 闩会随 HTTP 断开提前释放（doFinally 挂在汇热流上）而原链仍在跑——此时同
        // thread 再发新消息=双流并发写同一 AgentSession（框架明确不支持态；recorder 步骤/
        // 思考缓冲也依赖同 thread 单活跃 run）。DB 真源兜底：同 (租户,用户,thread) 存在
        // 其它 RUNNING run 即 409 人话拒绝（新 run 行由 registerRun 刚插入，按 runId 排除）。
        // E-14（2026-11-06）：改成存在性查询——原「取最新 2 行再筛 RUNNING」在僵尸 RUNNING
        // 之后又跑过两轮终态时会漏检（僵尸被挤出取样窗），并发双流仍可能发生
        if (runMapper.countOtherRunning(tenantId, threadId, userId, runId) > 0) {
            throw new ServiceException("上一轮回答还在生成中，稍候再发；刷新页面即可接着看这一轮回答", 409);
        }
        if (content != null && !content.isBlank()) {
            messageWriteService.appendUserMessage(tenantId, conversationId, userId, content);
        }
    }

    // ------------------------------------------------------------------ 终态与心跳

    /** run 终态回写（enricher：COMPLETED/FAILED/INTERRUPTED）。返回实际更新行数——
     *  0 行=run 已被收割器/兜底 job 抢先终局化（迟到终态被条件丢弃），调用方 WARN 观测 */
    public int finishRun(String runId, String status, String endReason) {
        return runMapper.finish(runId, status, endReason);
    }

    public AssistantRun runByRunId(String runId) {
        return runMapper.selectByRunId(runId);
    }

    /** run 末次事件时间心跳（多实例收割判据；只续 RUNNING 行） */
    public void touchRun(String runId) {
        runMapper.touch(runId);
    }

    /** 悬挂 run 候选（retention job 每分钟调；调用方先过滤 owner 仍活着的行） */
    public List<AssistantRun> listStuckRuns(int minutes) {
        return runMapper.selectStuck(minutes);
    }

    /** 条件迁移悬挂 run（只迁 RUNNING；迁移成功的行才返回，供退回合） */
    public List<AssistantRun> finalizeStuckRun(String tenantId, String runId) {
        return runMapper.finalizeStuck(tenantId, runId);
    }

    /** 业务批10 #2：RUNNING 超时行扫描（收割器零事件二扫；调用方按 lastEventAt 差集过滤） */
    public List<AssistantRun> listRunningOlderThan(int seconds) {
        return runMapper.selectRunningOlderThan(seconds);
    }

    /** 批6-#2：失联收割——条件迁移 INTERRUPTED/'client-disconnect'，返回归属行供退回合
     *  （RETURNING 语义=与真实终态竞态时只有一方赢，防双退；m3 P1-1 回修补租户谓词——
     *  雪花 runId 全局唯一本无越权面，纵深防御与同 mapper 风格对齐） */
    public List<AssistantRun> finalizeStalledRun(String tenantId, String runId) {
        return runMapper.finalizeStalled(tenantId, runId);
    }

    /**
     * 同 thread 最新一条 RUNNING run 的 runId（2026-11-11 拍板 Q2-2 补丁：AgentScope
     * 2.1.0-BETA1 的 ReActAgent 会把模型错误吞成无终态收流——RUN_ERROR 帧对服务商故障
     * 永远不出，问题行只能改挂在计费装饰器的错误侧）。thread 发送串行保证至多一条
     * RUNNING；非 RUNNING 返回 null（调用方跳过，不叠问题行）。
     */
    public String latestRunningRunIdOfThread(String tenantId, Long userId, String threadId) {
        List<AssistantRun> runs = runMapper.selectByThread(tenantId, threadId, userId, 1);
        if (runs.isEmpty() || !"RUNNING".equals(runs.get(0).getStatus())) {
            return null;
        }
        return runs.get(0).getRunId();
    }

    // ------------------------------------------------------------------ 问题行

    /**
     * 落一条「这轮为什么没回答」问题行（2026-11-11 拍板 Q1-3/Q2-2）。终态写成功后由
     * 记录器异步调用；code 为 null 直接跳过（正常有内容的轮次）。归属按 run 登记行
     * 回查（流线程无登录上下文，与消息落库同口径）；detail 先过纯函数脱敏再按租户
     * PII 掩码。异常自吞 WARN——问题行是锦上添花，绝不连坐终态链。
     */
    public void recordRunIssue(String runId, String code, String rawDetail) {
        if (runId == null || code == null || code.isBlank()) {
            return;
        }
        try {
            AssistantRun run = runMapper.selectByRunId(runId);
            if (run == null) {
                return;
            }
            doInsertIssue(run, code, rawDetail);
        } catch (Exception e) {
            log.warn("[AssistantPersist] 问题行落库失败(不影响终态) runId={} code={}: {}", runId, code, e.getMessage());
        }
    }

    /**
     * 模型失败捕获路径专用（终审 P2-1）：只挂「此刻仍在跑」的 run。
     * 为什么不能无脑挂线程最新 RUNNING：收割器把停滞轮终局化后用户可合法重发新轮，
     * 旧轮的迟到错误（重试链可超收割窗）若此刻才落库，会把 A 轮的限流/鉴权原因
     * 错挂到正常完成的 B 轮上（幽灵问题行）。此处回查状态把错挂窗口压到一次 DB 写的毫秒级。
     * 终态路径（finishRun/finalizeBuildFailure）不走此门——那时 run 已终态，属正常落点。
     */
    public void recordRunIssueForRunningRun(String runId, String code, String rawDetail) {
        if (runId == null || code == null || code.isBlank()) {
            return;
        }
        try {
            AssistantRun run = runMapper.selectByRunId(runId);
            if (run == null || !"RUNNING".equals(run.getStatus())) {
                return;
            }
            doInsertIssue(run, code, rawDetail);
        } catch (Exception e) {
            log.warn("[AssistantPersist] 问题行落库失败(不影响主流) runId={} code={}: {}", runId, code, e.getMessage());
        }
    }

    private void doInsertIssue(AssistantRun run, String code, String rawDetail) {
        AssistantRunIssue row = new AssistantRunIssue();
        row.setId(IdWorker.getId());
        row.setTenantId(run.getTenantId());
        row.setUserId(run.getUserId());
        row.setThreadId(run.getThreadId());
        row.setRunId(run.getRunId());
        row.setCode(code);
        String detail = AssistantRunIssues.sanitizeDetail(rawDetail);
        if (detail != null) {
            detail = piiFilter.mask(detail, run.getTenantId());
        }
        row.setDetail(detail);
        AssistantConversation conv = conversationMapper.selectByThread(run.getTenantId(), run.getThreadId());
        if (conv != null) {
            row.setConversationId(conv.getId());
            row.setMessageId(messageMapper.selectLatestUserMessageId(run.getTenantId(), conv.getId(), run.getUserId()));
        }
        issueMapper.insertIssue(row);
    }

    /** 历史回放：本会话全部问题行（按落库序；前端按 messageId 挂回对应回合） */
    public List<AssistantRunIssue> listRunIssues(String tenantId, Long userId, Long conversationId) {
        return issueMapper.selectByConversation(tenantId, userId, conversationId);
    }

    /** 活体：按 runId 取最新问题行（空回答卡延时拉取；归属由控制器校验） */
    public AssistantRunIssue latestRunIssue(String tenantId, Long userId, String runId) {
        AssistantRunIssue row = issueMapper.selectLatestByRunId(runId);
        if (row == null || !tenantId.equals(row.getTenantId()) || !userId.equals(row.getUserId())) {
            return null;
        }
        return row;
    }
}
