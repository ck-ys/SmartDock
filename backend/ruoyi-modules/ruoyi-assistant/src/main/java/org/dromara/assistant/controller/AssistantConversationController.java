package org.dromara.assistant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantAction;
import org.dromara.assistant.domain.DcsAssistantConversation;
import org.dromara.assistant.domain.DcsAssistantMessage;
import org.dromara.assistant.service.AssistantActionService;
import org.dromara.assistant.service.AssistantConversationService;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 助手会话/消息/动作账本端点（批G G2/G3，spec §18）。
 * 独立会话页（前端 E2 承接）与断线重连恢复（X-09/E5）的数据源。
 */
@Tag(name = "助手会话", description = "会话/消息/动作账本端点：独立会话页与断线重连恢复的数据源")
@Slf4j
@RestController
@RequestMapping("/dcs/assistant")
@RequiredArgsConstructor
public class AssistantConversationController extends BaseController {
    /** G1 乙类#5：停止生成走框架 ThreadSessionManager 定向 interrupt */
    private final io.agentscope.spring.boot.agui.common.ThreadSessionManager threadSessionManager;


    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private final AssistantConversationService conversationService;
    private final AssistantActionService actionService;
    /** 业务批4 Q1：@ 候选读链（批3 微核心化：mentionCandidates 显式 userId, 无 Sa-Token 依赖） */
    private final org.springframework.beans.factory.ObjectProvider<com.smartdock.dcs.service.DcsTaskService> dcsTaskServiceProvider;
    /** 080/ISSUE-R13-3 修：清人后按 thread 清悬挂缓存/步骤缓冲（控制器收口避
     *  ConversationService↔RunRecorder 循环依赖） */
    private final org.dromara.assistant.persist.AssistantRunRecorder runRecorder;
    private final org.dromara.assistant.mapper.AssistantRunMapper runMapper;
    /** B 批题5「续看」：进行中 run 事件广播汇 */
    private final org.dromara.assistant.core.AssistantRunStreamHub runStreamHub;
    /** 题12 案1：结构化反问桥——停止生成时释放该线程挂起卡（工具线程不空等 240s） */
    private final org.dromara.assistant.core.AssistantAskUserBridge askUserBridge;
    /** ②（2026-10-31）：计划卡取消立即解锁——点「取消」当场清闸的信号仓 */
    private final org.dromara.assistant.core.AssistantPlanSignal planSignal;
    /** 多实例批 2：判断这轮 run 是不是落在别的活实例上（别再盲打本机内存） */
    private final org.dromara.assistant.core.AssistantInstanceContext instanceContext;
    /** 二审回修：停止端点条件收尾 run 时退回合，口径与收割器一致 */
    private final org.dromara.assistant.limiter.AssistantDailyQuota dailyQuota;
    /** 四审回修（dsflash-P2-7）：直接注入，不再 SpringUtils.getBean + 失败回落钱包 */
    private final org.dromara.assistant.service.AssistantUserQuotaService quotaLookup;

    /** 退款口径（三审回修 dspro-P2-1）：credits 订阅租户建侧 tryTurn 本就跳过，退回合须同口径
     *  跳过（同 AssistantRunRecorder#refundIfWallet）；查不到模式按钱包口径退（fail-open）。 */
    private boolean creditsMode(String tenantId, Long userId) {
        try {
            return quotaLookup.billingAwareLimits(tenantId, userId).creditsMode();
        } catch (Exception ignore) {
            return false;
        }
    }

    /**
     * B 批题5「续看」（2026-10-27 拍板 甲案）：刷新/掉线后重新挂上进行中的回答流。
     *
     * <p>机制：该 thread 最新 run 仍 RUNNING 时，订阅 {@link org.dromara.assistant.core.AssistantRunStreamHub}
     * 的 replay 汇——先重放保留窗内全部已出网事件（重建进行中回答），再续播实时流直到终局帧。
     * 帧格式与 /agui/run 完全同源（同一出网最终视图、同一 JSON 形态），前端复用同一帧分发器。</p>
     *
     * <p>回退：run 不在跑/汇已逐出（终局超 5 分钟/进程重启过）→ 返回 JSON 业务错——
     * 前端回退到既有「轮询等终态再重放历史」路径，能力不倒退。保留窗溢出（超长单轮）
     * 时首帧补 dcs-resume-truncated 提示帧（头部内容稍后从历史消息看全文）。</p>
     */
    @SaCheckPermission("dcs:assistant:use")
    @PostMapping(value = "/conversation/attach")
    public org.springframework.http.ResponseEntity<?> attach(@RequestParam String threadId) {
        String tenantId = TenantHelper.getTenantId();
        Long userId = LoginHelper.getUserId();
        java.util.List<org.dromara.assistant.domain.DcsAssistantRun> latest =
            runMapper.selectByThread(tenantId, threadId, userId, 1);
        boolean running = !latest.isEmpty() && "RUNNING".equals(latest.get(0).getStatus());
        if (!running) {
            return org.springframework.http.ResponseEntity.ok(
                R.fail("本轮已结束，刷新消息列表即可看到全文"));
        }
        String runId = latest.get(0).getRunId();
        var fluxOpt = runStreamHub.attach(runId);
        if (fluxOpt.isEmpty()) {
            return org.springframework.http.ResponseEntity.ok(
                R.fail("本轮不可续看（可能不在当前服务上、服务重启过或已超保留窗），刷新页面后重试"));
        }
        final org.dromara.assistant.domain.DcsAssistantRun run = latest.get(0);
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
            new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L);
        // 三审回修（dsflash-P2-4）：原子引用替代裸数组——订阅线程/容器回调线程/控制器线程
        // 三处读写需 happens-before；统一停看入口
        java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable> watch =
            new java.util.concurrent.atomic.AtomicReference<>();
        Runnable stopWatch = () -> {
            reactor.core.Disposable d = watch.get();
            if (d != null) {
                d.dispose();
            }
        };
        emitter.onCompletion(stopWatch);
        emitter.onTimeout(() -> {
            stopWatch.run();
            emitter.complete();
        });
        // 深审回修：send 之外的应用层异常（序列化/容器错）也 dispose——漏挂会让观众计数
        // 不减、孤儿判定失真（viewers 永不为零，弃链兜底被废）
        emitter.onError(ex -> stopWatch.run());
        try {
            reactor.core.publisher.Flux<io.agentscope.core.agui.event.AguiEvent> flux = fluxOpt.get();
            if (runStreamHub.fedCount(runId) > org.dromara.assistant.core.AssistantRunStreamHub.RING) {
                flux = flux.startWith(new io.agentscope.core.agui.event.AguiEvent.Custom(
                    threadId, runId,
                    org.dromara.assistant.core.AssistantRunStreamHub.TRUNCATED_FRAME,
                    java.util.Map.of("hint", "本回合内容较长，续看从中间开始；完整回答稍后在历史消息里")));
            }
            // 三审回修（dsflash-P2-4）：replay 汇订阅即同步重放≤4096 帧（逐帧 Jackson+写响应）
            // ——移出控制器线程；重放期断连时 subscribe 尚未返回、旧写法引用未就位无法自停
            flux = flux.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
            watch.set(flux.subscribe(
                event -> {
                    try {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                            .event().data(OBJECT_MAPPER.writeValueAsString(event)));
                    } catch (Exception e) {
                        // 客户端已断开——停订阅并收口响应（汇继续留驻给下一次 attach）；
                        // Spring sendFailed 后后续 send 静默短路不再抛，必须在此主动收
                        stopWatch.run();
                        emitter.complete();
                    }
                },
                err -> {
                    log.warn("[AssistantAttach] run {} 续看流异常: {}", runId, err.getMessage());
                    emitter.completeWithError(err);
                },
                emitter::complete));
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.ok(R.fail("续看通道建立失败：" + e.getMessage()));
        }
        log.info("[AssistantAttach] run {} 续看挂载 tenant={} user={}", runId, tenantId, userId);
        // 显式 text/event-stream：ResponseEntity 裸包 SseEmitter 时 Spring 会落到默认
        // application/json 头（8090 实测），前端按 content-type 分流会把真流误判成 JSON 降级
        return org.springframework.http.ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
            .body(emitter);
    }

    /** 会话列表（180 天语义=清理 job 保证；perm=use；status=ACTIVE/ARCHIVED/ALL，默认 ACTIVE） */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/conversation/list")
    public TableDataInfo<DcsAssistantConversation> conversations(
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String status) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        String safeStatus = clampStatus(status);
        List<DcsAssistantConversation> rows = conversationService.listConversations(
            TenantHelper.getTenantId(), LoginHelper.getUserId(), safeSize, (safePage - 1) * safeSize, safeStatus);
        // 业务批4 Q4：回填 running（在途轮的会话刷新后仍标"生成中"）
        conversationService.markRunning(rows, TenantHelper.getTenantId(), LoginHelper.getUserId());
        TableDataInfo<DcsAssistantConversation> data = TableDataInfo.build(rows);
        data.setTotal(conversationService.countConversations(
            TenantHelper.getTenantId(), LoginHelper.getUserId(), safeStatus));
        return data;
    }

    /** 按 threadId 取本人会话（自动化通知深链：不在首屏 50 条里也能打开） */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/conversation/by-thread")
    public R<DcsAssistantConversation> byThread(@RequestParam String threadId) {
        DcsAssistantConversation row = conversationService.conversationByThread(
            TenantHelper.getTenantId(), threadId);
        if (row == null || !LoginHelper.getUserId().equals(row.getUserId())) {
            return R.fail("会话不存在或已清理");
        }
        return R.ok(row);
    }

    /** status 合法值收敛（批H P1-1）：ACTIVE/ARCHIVED/ALL 之外的输入一律按 ACTIVE */
    private static String clampStatus(String status) {
        if (status == null) {
            return "ACTIVE";
        }
        return switch (status.toUpperCase()) {
            case "ARCHIVED", "ALL" -> status.toUpperCase();
            default -> "ACTIVE";
        };
    }

    /**
     * @ 引用候选任务（业务批4 Q1，2026-10-12；批3 微核心化）：输入条 @ 拉「我相关的
     * 收集中任务」前 100 条（我发布的或指派给我的），行带 relation 关系标注——
     * 前端弹层同屏标注，治「@ 只认我发布的」歧义。
     */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/mention-tasks")
    public R<List<Map<String, String>>> mentionTasks(
        @RequestParam(required = false) String keyword) {
        String tenantId = LoginHelper.getTenantId();
        Long meId = LoginHelper.getUserId();
        List<Map<String, String>> rows = new java.util.ArrayList<>();
        com.smartdock.dcs.service.DcsTaskService dcsTaskService = dcsTaskServiceProvider.getIfAvailable();
        if (dcsTaskService == null) {
            // 三审 dsflash P2: 业务模块缺席时助手照常应答(空候选), 对齐"零编译强依赖"口径
            return R.ok(rows);
        }
        try {
            for (com.smartdock.dcs.domain.DcsTask t : dcsTaskService.mentionCandidates(
                keyword == null ? null : keyword.trim(), 100, meId)) {
                Map<String, String> row = new java.util.LinkedHashMap<>();
                row.put("missionId", String.valueOf(t.getId()));
                row.put("missionName", t.getTitle() == null ? "" : t.getTitle());
                row.put("relation", meId.equals(t.getPublisherUserId()) ? "我发布的" : "与我相关");
                rows.add(row);
            }
        } catch (Exception e) {
            log.warn("[mention-tasks] 候选拉取失败 tenant={} kw={}: {}", tenantId, keyword, e.getMessage());
        }
        return R.ok(rows);
    }

    /** 历史消息（本人会话；管理端看原文走审批通道=P2） */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/conversation/{id}/messages")
    public R<List<DcsAssistantMessage>> messages(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "50") int limit,
                                                  @RequestParam(defaultValue = "1") int pageNum,
                                                  @RequestParam(defaultValue = "false") boolean tail) {
        // AU-39(1008)：limit/pageNum 夹紧同 list 端点口径——0/负数直传会让 offset 为负，
        // PG LIMIT..OFFSET 语法错 500（用户看不出原因）
        int safeLimit = Math.min(Math.max(1, limit), 100);
        int safePage = Math.max(1, pageNum);
        // 批A 二审 P1：tail=true 取最新 N 条（压缩重载/长会话打开——ASC 首页会截掉最新回答）
        return R.ok(tail
            ? conversationService.listMessagesTail(TenantHelper.getTenantId(), LoginHelper.getUserId(), id, safeLimit)
            : conversationService.listMessages(TenantHelper.getTenantId(),
                LoginHelper.getUserId(), id, safeLimit, (safePage - 1) * safeLimit));
    }

    /** 本人归档会话（软通道 status=ARCHIVED）；铁律#21：DELETE→POST 保持路径（2026-10-16 批17 Q4，前端已同步） */
    @SaCheckPermission("dcs:assistant:use")
    @Log(title = "AI助手-归档会话", businessType = BusinessType.DELETE)
    @PostMapping("/conversation/{id}")
    public R<Void> archive(@PathVariable Long id) {
        conversationService.archiveConversation(TenantHelper.getTenantId(), LoginHelper.getUserId(), id);
        return R.ok();
    }

    /**
     * 会话问题行（2026-11-11 拍板 Q1-3/Q3-2）：「这轮为什么没回答」的历史还原源——
     * 刷新/换设备后按 messageId 把原因挂回对应回合。detail 已脱敏，普通用户看
     * code 映射人话，原文是否展开由前端按角色控制。
     */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/conversation/{id}/issues")
    public R<List<org.dromara.assistant.domain.DcsAssistantRunIssue>> issues(@PathVariable Long id) {
        return R.ok(conversationService.listRunIssues(TenantHelper.getTenantId(), LoginHelper.getUserId(), id));
    }

    /**
     * 活体：按 runId 取最新问题行（空回答卡延时拉取，同追问侧车节奏）。run 归属
     * 与当前登录不符时按「无记录」返回（不区分不存在与无权——防探测，同 thread 首绑口径）。
     */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/run/{runId}/issue")
    public R<org.dromara.assistant.domain.DcsAssistantRunIssue> runIssue(@PathVariable String runId) {
        String tenantId = TenantHelper.getTenantId();
        Long userId = LoginHelper.getUserId();
        org.dromara.assistant.domain.DcsAssistantRun run = runMapper.selectByRunId(runId);
        if (run == null || !tenantId.equals(run.getTenantId()) || !userId.equals(run.getUserId())) {
            return R.ok(null);
        }
        return R.ok(conversationService.latestRunIssue(tenantId, userId, runId));
    }

    /**
     * G1 乙类#5（Q4=A 查证成立即接）：停止生成=服务端真取消。框架 interrupt(userId, threadId)
     * 定向打断在跑 run（sessionId 维度，AGUI threadId 即 sessionId），终态秒级落 INTERRUPTED
     * 而非等 60s stall 收割。注意：断连仍不打断（interrupt-on-disconnect=false 是 HITL
     * 恢复链前提，P0 T4 实证）——只有用户显式点停止才走本端点。幂等：会话不在=无事发生。
     */
    @SaCheckPermission("dcs:assistant:use")
    @RepeatSubmit(interval = 1000)
    @Log(title = "AI助手-停止生成", businessType = BusinessType.OTHER)
    @PostMapping("/conversation/stop")
    public R<Void> stop(@RequestParam String threadId) {
        // 三审回修（dsflash/dspro）：租户口径与本文件其它端点同源（TenantHelper 动态优先），
        // 原用 LoginHelper 登录快照租户，超管切租户后 getSession 恒 miss=停止静默失效
        String tenantId = TenantHelper.getTenantId();
        Long userId = LoginHelper.getUserId();
        String rcUserId = "t:" + tenantId + ":" + userId;
        // 三审回修（三席共识 P1）：只对该 thread 最新 run 仍在 RUNNING 时打标+打断——
        // 盲打会把标记留给一个已终局的 run（残留=同 thread 下一轮被误判 user-stop）
        java.util.List<org.dromara.assistant.domain.DcsAssistantRun> latest =
            runMapper.selectByThread(tenantId, threadId, userId, 1);
        boolean running = !latest.isEmpty() && "RUNNING".equals(latest.get(0).getStatus());
        if (!running) {
            askUserBridge.cancelThread(bridgeTenant(tenantId), userId, threadId);
            return R.ok();
        }
        org.dromara.assistant.domain.DcsAssistantRun run = latest.get(0);
        String owner = run.getOwnerInstance();
        boolean ownedHere = owner == null || owner.isBlank() || owner.equals(instanceContext.current());
        // 三审回修（dspro-P1-2 / dsflash-P2-2）：owner 为空是升级期旧版本实例写的行，旧进程
        // 可能还在跑。收割器对这类行刻意留 30 分钟保护（AssistantRetentionJob），停止端点
        // 不能反着来——否则滚动升级时点停止会「谎报成功」：旧实例继续出字烧钱，本机还白退额度，
        // 随后 finish 条件落空留下 run=INTERRUPTED 却有完整回答的自相矛盾态。
        if (owner == null || owner.isBlank()) {
            java.util.Date ref = run.getLastEventAt() != null ? run.getLastEventAt() : run.getCreateTime();
            if (ref != null && System.currentTimeMillis() - ref.getTime() < 30L * 60 * 1000) {
                throw new org.dromara.common.core.exception.ServiceException(
                    "这轮可能还在旧版本服务上，最多 30 分钟内会自动收尾；请稍后刷新再试", 409);
            }
        }
        // 多实例批 2：这轮在另一台活实例上——本机没有它的 AgentSession，盲打就是假成功，
        // 必须回人话让用户刷新（LB 按 X-Thread-Id 粘回去后就能真的停）
        if (!ownedHere && instanceContext.isAlive(owner)) {
            throw new org.dromara.common.core.exception.ServiceException(
                "这轮回答在另一台服务上，刷新页面后再点停止", 409);
        }
        var session = threadSessionManager.getSession(rcUserId, threadId);
        // 二审回修（glm53-P1 / dspro-P2-7 / dsflash-P2-7）：走到这里只有两种「停不了」：
        // ①owner 已死（实例下线/重启，含固定 instance.id 重启后自己名下的旧进程）；
        // ②owner 是自己但本机会话没了（进程重启/会话被清）。两种情况 run 都真的断了，
        // 正确动作是条件迁移收尾 + 退回合 + 释放挂起卡，而不是假装 interrupt 成功、
        // 也不是回 409 把 RUNNING 幽灵行留给收割器（期间同 thread 新消息会被双 RUNNING 门挡）。
        if (!ownedHere || session.isEmpty()) {
            java.util.List<org.dromara.assistant.domain.DcsAssistantRun> closed =
                conversationService.finalizeStalledRun(tenantId, run.getRunId());
            if (!creditsMode(tenantId, userId)) {
                for (org.dromara.assistant.domain.DcsAssistantRun row : closed) {
                    dailyQuota.refundTurn(row.getTenantId(), row.getUserId());
                }
            }
            if (!closed.isEmpty()) {
                log.info("[AssistantStop] 本机已无在跑会话，条件收尾 run thread={} run={} 已退回合",
                    threadId, run.getRunId());
            }
            askUserBridge.cancelThread(bridgeTenant(tenantId), userId, threadId);
            return R.ok();
        }
        if (session.get().getAgent() instanceof io.agentscope.core.ReActAgent agent) {
            // 先打 run 级停止标记再 interrupt——优雅收尾事件流与自然完成无异，终局分类靠标记覆写
            org.dromara.assistant.persist.AssistantRunRecorder.markUserStop(run.getRunId());
            agent.interrupt(rcUserId, threadId);
            log.info("[AssistantStop] 服务端打断在跑 run thread={} run={} user={}",
                threadId, run.getRunId(), rcUserId);
        }
        // 题12 案1：停止后挂起的结构化反问已不可能被模型用上——立即按取消收尾释放工具线程。
        // 不释放的话：卡片留在屏上、作答端点还能「成功」，但 run 已终局=答案被静默吞掉
        askUserBridge.cancelThread(bridgeTenant(tenantId), userId, threadId);
        return R.ok();
    }

    /**
     * 四审回修（dspro-P2-6）：桥接的挂起卡按 ToolIdentity.from(user) 的登录快照租户登记，
     * 而 stop 的会话/run 行按动态租户查。超管切租户时两者不一致，按动态租户 cancelThread
     * 匹配不上，工具线程要等 240s 超时才释放。桥接调用统一回登录快照租户（查不到才回落）。
     */
    private String bridgeTenant(String fallback) {
        try {
            var user = LoginHelper.getLoginUser();
            return user != null && user.getTenantId() != null && !user.getTenantId().isBlank()
                ? user.getTenantId() : fallback;
        } catch (Exception ignore) {
            return fallback;
        }
    }

    /**
     * ②（2026-10-31 拍板）：计划卡取消立即解锁。原先点「取消」只收卡，服务端清闸要等
     * 下一条消息带 planDecision=cancel——用户取消后立刻让助手做写操作会被硬拒到下一条消息
     * 才放开。本端点点「取消」当场清闸；幂等（无 pending=无事发生恒 R.ok），失败兜底链路
     * （下一条消息 planDecision）原样保留。
     *
     * <p>归属三路：他租户/不存在=no-op R.ok（不暴露会话存在性）；同租户异主=403 人话；
     * 本人=清闸。会话行查找用动态优先租户（与 stop 同口径——超管切租户后会话行落动态租户）；
     * 清闸 owner 的租户=登录快照（与 PlanTools submit 的 ToolIdentity.from 同源，超管
     * 切换态下动态 B≠快照 A，用动态值 owner 匹配不上=清不掉）。</p>
     */
    @SaCheckPermission("dcs:assistant:use")
    @RepeatSubmit(interval = 1000)
    @Log(title = "AI助手-取消计划", businessType = BusinessType.OTHER)
    @PostMapping("/plan/cancel")
    public R<Void> cancelPlan(@RequestParam String threadId,
                              @RequestParam(required = false) String planId) {
        DcsAssistantConversation conv = conversationService.conversationByThread(
            TenantHelper.getTenantId(), threadId);
        Long me = LoginHelper.getUserId();
        PlanCancelAction action = planCancelAction(conv == null ? null : conv.getUserId(), me);
        if (action == PlanCancelAction.FORBIDDEN) {
            throw new org.dromara.common.core.exception.ServiceException("这个会话不是你的，不能操作", 403);
        }
        if (action == PlanCancelAction.CLEAR) {
            // 清闸租户=登录快照（owner 三元组与 submit 同源，见方法 javadoc）；
            // planId 可选：带则只清这张卡，不带则兼容旧前端按 thread 清
            planSignal.decide(threadId, LoginHelper.getLoginUser().getTenantId(), me, planId, "cancelled");
        }
        // NOOP（他租户 threadId / 不存在）与 CLEAR 都恒 R.ok——端点幂等，无 pending 也无事发生
        return R.ok();
    }

    /**
     * 计划卡状态查询（多实例批 4）：前端另一标签页/另一实例轮询用。
     * 只返回 status + planId，不泄露计划正文；非本人会话一律 none（不暴露存在性）。
     */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/plan/state")
    public R<org.dromara.assistant.core.AssistantPlanSignal.PlanState> planState(
        @RequestParam String threadId) {
        DcsAssistantConversation conv = conversationService.conversationByThread(
            TenantHelper.getTenantId(), threadId);
        if (conv == null || !conv.getUserId().equals(LoginHelper.getUserId())) {
            return R.ok(new org.dromara.assistant.core.AssistantPlanSignal.PlanState("none", null));
        }
        return R.ok(planSignal.state(threadId));
    }

    /** 取消计划归属决策三态（纯函数，单测锚点） */
    public enum PlanCancelAction {
        /** 会话行不存在（他租户 threadId / 未创建）——幂等 no-op，不清闸 */
        NOOP,
        /** 会话属于别人——403 拒绝 */
        FORBIDDEN,
        /** 本人会话——执行清闸 */
        CLEAR
    }

    /** 归属决策纯函数：convOwner=null→NOOP；convOwner≠me→FORBIDDEN；本人→CLEAR */
    public static PlanCancelAction planCancelAction(Long convOwner, Long me) {
        if (convOwner == null) {
            return PlanCancelAction.NOOP;
        }
        return convOwner.equals(me) ? PlanCancelAction.CLEAR : PlanCancelAction.FORBIDDEN;
    }

    /** 当前会话 TODO 清单（ZCode 对齐 Q2，批A：前端打开会话时恢复状态面板分区） */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/conversation/todo")
    public R<List<org.dromara.assistant.core.AssistantTodoSignal.TodoItem>> todo(@RequestParam String threadId) {
        return R.ok(conversationService.todoOf(TenantHelper.getTenantId(), LoginHelper.getUserId(), threadId));
    }

    /**
     * 本会话最新一条回答的追问建议（批5 Q7-3）：前端 done 后延时拉一次，
     * 有生成结果替换静态池；messageId 为字符串（雪花 19 位，BigNumberSerializer 序列化），
     * 空=回退静态池。
     */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/conversation/followups")
    public R<Map<String, Object>> followups(@RequestParam String threadId) {
        DcsAssistantConversation conv = conversationService.conversationByThread(TenantHelper.getTenantId(), threadId);
        // 归属校验（七席审查 P2 回修）：与 todoOf 同口径显式拒异主——latestAssistant 的
        // user_id 过滤只是兜底，纵深防御不留「复用去参即越权」的口
        if (conv == null || !conv.getUserId().equals(LoginHelper.getUserId())) {
            return R.ok(Map.of("messageId", "0", "followups", List.of()));
        }
        DcsAssistantMessage msg = conversationService.latestAssistant(
            TenantHelper.getTenantId(), LoginHelper.getUserId(), conv.getId());
        List<String> items = List.of();
        if (msg != null && msg.getFollowupSuggestions() != null && !msg.getFollowupSuggestions().isBlank()) {
            try {
                List<String> parsed = OBJECT_MAPPER.readValue(msg.getFollowupSuggestions(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                // 判空防 Map.of NPE（审查回修：readValue 对字面量 null 返回 null）
                if (parsed != null) {
                    items = parsed;
                }
            } catch (Exception e) {
                // 脏数据回退静态池——warn 留痕（审查回修：纯静默会让「库里有但前端不显示」取证瞎眼）
                log.warn("[AssistantFollowup] 追问列 JSON 脏数据回退静态池 msg={} raw={}",
                    msg.getId(), msg.getFollowupSuggestions(), e);
            }
        }
        return R.ok(Map.of("messageId", msg == null ? "0" : String.valueOf(msg.getId()), "followups", items));
    }

    /** 本人重命名会话（批D P2-7：title，1-60 字符；铁律#21：PATCH→POST 2026-10-16 批17 回修） */
    @SaCheckPermission("dcs:assistant:use")
    @Log(title = "AI助手-重命名会话", businessType = BusinessType.UPDATE)
    @PostMapping("/conversation/{id}/title")
    public R<Void> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        conversationService.renameConversation(TenantHelper.getTenantId(), LoginHelper.getUserId(), id, body.get("title"));
        return R.ok();
    }

    /** 恢复已归档会话（批D：ARCHIVED→ACTIVE） */
    @SaCheckPermission("dcs:assistant:use")
    @Log(title = "AI助手-恢复会话", businessType = BusinessType.UPDATE)
    @PostMapping("/conversation/{id}/restore")
    public R<Void> restore(@PathVariable Long id) {
        conversationService.restoreConversation(TenantHelper.getTenantId(), LoginHelper.getUserId(), id);
        return R.ok();
    }

    /** 动作账本进度（断线重连恢复数据源，spec §18/X-09） */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/action/list")
    public R<List<DcsAssistantAction>> actions(@RequestParam(required = false) String runId,
                                                @RequestParam(required = false) String threadId) {
        return R.ok(actionService.listForUser(TenantHelper.getTenantId(),
            LoginHelper.getUserId(), runId, threadId));
    }

    /** 管理端清除用户会话（GDPR 式五表事务+agentscope 框架表；perm=session:manage，S-19）；铁律#21：DELETE→POST（2026-10-16 批17 Q4） */
    @SaCheckPermission("dcs:assistant:session:manage")
    @Log(title = "AI助手-清除用户会话", businessType = BusinessType.DELETE)
    @PostMapping("/admin/session/purge")
    public R<Integer> purge(@RequestParam Long userId) {
        String tenantId = TenantHelper.getTenantId();
        // 080 修：先取 thread 清单（purgeUser 删行后就查不到了），事务提交后按清单清内存缓存
        List<String> threads = conversationService.threadIdsOfUser(tenantId, userId);
        int n = conversationService.purgeUser(tenantId, userId);
        // 框架表清理必须在五表事务提交之后（事务内失败语句会把 PG 事务打 aborted，
        // COMMIT 静默回滚四表删除——G2 实证），缺失环境仅日志
        conversationService.purgeAgentscopeSessions(tenantId, userId);
        // 080 修：悬挂确认卡缓存（含 argsJson）+步骤缓冲按 thread 配对清理——被清用户
        // 数据不再驻留内存至进程重启
        runRecorder.evictPendingInterrupts(threads);
        runRecorder.evictStepBuffers(threads);
        return R.ok(n);
    }
}
