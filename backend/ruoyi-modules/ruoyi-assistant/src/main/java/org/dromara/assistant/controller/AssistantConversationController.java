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
                R.fail("本轮不可续看（服务重启过或已超保留窗），稍后自动接终态"));
        }
        final org.dromara.assistant.domain.DcsAssistantRun run = latest.get(0);
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
            new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L);
        reactor.core.Disposable[] disposable = new reactor.core.Disposable[1];
        emitter.onCompletion(() -> { if (disposable[0] != null) disposable[0].dispose(); });
        emitter.onTimeout(() -> { if (disposable[0] != null) disposable[0].dispose(); emitter.complete(); });
        try {
            reactor.core.publisher.Flux<io.agentscope.core.agui.event.AguiEvent> flux = fluxOpt.get();
            if (runStreamHub.fedCount(runId) > org.dromara.assistant.core.AssistantRunStreamHub.RING) {
                flux = flux.startWith(new io.agentscope.core.agui.event.AguiEvent.Custom(
                    threadId, runId,
                    org.dromara.assistant.core.AssistantRunStreamHub.TRUNCATED_FRAME,
                    java.util.Map.of("hint", "本回合内容较长，续看从中间开始；完整回答稍后在历史消息里")));
            }
            disposable[0] = flux.subscribe(
                event -> {
                    try {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                            .event().data(OBJECT_MAPPER.writeValueAsString(event)));
                    } catch (Exception e) {
                        // 客户端已断开——停订阅（汇继续留驻给下一次 attach）
                        if (disposable[0] != null) {
                            disposable[0].dispose();
                        }
                    }
                },
                err -> {
                    log.warn("[AssistantAttach] run {} 续看流异常: {}", runId, err.getMessage());
                    emitter.completeWithError(err);
                },
                emitter::complete);
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
        threadSessionManager.getSession(rcUserId, threadId).ifPresent(session -> {
            if (running && session.getAgent() instanceof io.agentscope.core.ReActAgent agent) {
                // 先打 run 级停止标记再 interrupt——优雅收尾事件流与自然完成无异，终局分类靠标记覆写
                org.dromara.assistant.persist.AssistantRunRecorder.markUserStop(latest.get(0).getRunId());
                agent.interrupt(rcUserId, threadId);
                log.info("[AssistantStop] 服务端打断在跑 run thread={} run={} user={}",
                    threadId, latest.get(0).getRunId(), rcUserId);
            }
        });
        return R.ok();
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
