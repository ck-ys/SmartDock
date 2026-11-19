package com.smartdock.assistant.legacy.service;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.assistant.domain.model.DcsMcpCallLog;
import com.smartdock.assistant.domain.model.DcsMcpServer;
import com.smartdock.assistant.mapper.DcsMcpCallLogMapper;
import com.smartdock.assistant.mapper.DcsMcpServerMapper;
import com.smartdock.assistant.support.McpHeaderCipher;
import com.smartdock.assistant.support.McpToolFacts;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 批2 Q2=A（2026-10-21）：MCP 外部工具试点。
 *
 * <p>批 5 题 2 案 1（2026-11-19）按职责拆开：管理面（注册表 CRUD/清单）迁
 * {@code service/McpServerConfigService}，纯函数（args/headers 解析）迁
 * {@code support/McpToolFacts}，请求头加解密迁 {@code support/McpHeaderCipher}，
 * 审计保留期清理 job 迁 {@code service/McpCallLogRetentionJob}。本类只剩运行面：
 * 挂载、工具闸、限频、双态审计、孤儿客户端清扫。</p>
 *
 * <p>安全闸（dcs biz22 A4 对照吸收，数字照抄）：①STDIO 命令白名单只允许 node，
 * 脚本路径必须落在配置允许目录内（canonical 前缀比对防穿越）；②HTTP 传输 host
 * 必须在白名单（默认仅 localhost/127.0.0.1，可配置追加）；③试点只收只读服务（DDL
 * 钉死 read_only='1'；G4-#1 起 HTTP 行可配鉴权头——值 AES 加密落库+键名回显，
 * 见 support 的 McpToolFacts / McpHeaderCipher）；④挂载失败/服务下线只记审计+warn，绝不连坐助手会话；
 * ⑤调用级限频 30 次/分/租户（GatedMcpClient 装饰层，超限调用报错给模型人话）；
 * ⑥挂载与每次真实调用双态审计落 dcs_mcp_call_log。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpPilotService {

    /** dcs 对照吸收：调用限频 30 次/分/租户 */
    static final int RATE_LIMIT_PER_MIN = 30;

    private final DcsMcpServerMapper serverMapper;
    private final DcsMcpCallLogMapper callLogMapper;
    /** 请求头解密（HTTP 行挂载用；加解密/键名回显实现已迁 support.McpHeaderCipher） */
    private final McpHeaderCipher headerCipher;
    private final io.agentscope.spring.boot.agui.common.ThreadSessionManager threadSessionManager;

    /**
     * 三审回修（三席共识 P0/进程泄漏）：(rcUserId, threadId) → 本会话 attach 的 MCP 客户端。
     * 框架的会话淘汰（容量/失效重建）只删 map 不 close，子进程会无限累积；
     * 清扫任务（60s）对已不在 SessionManager 的 key 统一 close——不动框架代码。
     * 键必须与 AssistantRuntimeContextResolver 同款复合（rcUserId="t:租户:用户" + aguiThreadId）：
     * 单参 getSession(String) 语义是 (null, threadId)，永远查空——E2E 实证会误杀活客户端。
     */
    private final java.util.concurrent.ConcurrentHashMap<AttachKey, java.util.Map<String, McpClientWrapper>> attachedBySession =
        new java.util.concurrent.ConcurrentHashMap<>();

    private record AttachKey(String rcUserId, String threadId) {}

    /** 清扫：会话已淘汰（不在管理器里）→ close 其全部 MCP 客户端（node 子进程/HTTP 连接）。
     *  G4 三审回修（dspro P2-6）：先 remove 再 close——原 isEmpty 判空与 remove 之间
     *  会话若被重建，会把新会话刚挂的客户端误关/失跟踪 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    void sweepOrphanMcpClients() {
        attachedBySession.forEach((key, perServer) -> {
            if (threadSessionManager.getSession(key.rcUserId(), key.threadId()).isEmpty()) {
                java.util.Map<String, McpClientWrapper> cur = attachedBySession.remove(key);
                if (cur == null) {
                    return;
                }
                for (McpClientWrapper c : cur.values()) {
                    try {
                        c.close();
                    } catch (Exception e) {
                        log.warn("[McpPilot] 清扫关闭 {} 失败（忽略）：{}", key, e.getMessage());
                    }
                }
                log.info("[McpPilot] 会话 {}|{} 已淘汰，回收其 {} 个 MCP 客户端",
                    key.rcUserId(), key.threadId(), cur.size());
            }
        });
    }

    /** G3-A1：elicitation 反问桥——STDIO 行注册 handler（挂起等用户作答），HTTP 行暂不开（sync handler 阻塞入站线程的滞留风险待真机观察） */
    private final com.smartdock.assistant.legacy.core.AssistantElicitationBridge elicitationBridge;

    /** G3-A1 三审回修：STDIO 工具调用自身预算（不含答题）——反问前后处理+收尾的余量 */
    @Value("${smartdock.mcp.tool-budget-seconds:120}")
    private int toolBudgetSeconds;

    /* ============================ 管理面 ============================ */

    public List<DcsMcpServer> listEnabledForPlatform() {
        return serverMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DcsMcpServer>()
            .eq(DcsMcpServer::getEnabled, "1"));
    }

    /* ============================ 挂载（建会话链路） ============================ */

    /**
     * G4-#3 写类判定（纯函数，单测直测；glm53 终审 P2 抽出）：
     * 注解显式优先——readOnlyHint=FALSE 一律写类（ASK 确认卡）；TRUE 一律只读放行；
     * null（未声明）兜底跟服务级 read_only：'1' 视为只读（试点零行为变化——服务
     * DDL 钉死只读，信任服务声明），'0' fail-safe 视为写类。已知边界（登记池）：
     * 注解是服务自声明，写工具谎标 TRUE 即绕闸——read_only 放开前加"不信注解"总闸。
     */
    public static boolean isWriteTool(Boolean readOnlyHint, boolean serverReadOnly) {
        if (Boolean.FALSE.equals(readOnlyHint)) {
            return true;
        }
        if (Boolean.TRUE.equals(readOnlyHint)) {
            return false;
        }
        return !serverReadOnly;
    }

    /**
     * 无人值守（自动化）挂载判据（三审 P1，纯函数直测）：只认显式 {@code readOnlyHint=TRUE}。
     * 未声明 / 声明可写一律不挂——交互会话里写类还能弹确认卡，无人值守没人点确认。
     * 注解是第三方服务自声明，没声明就按写类处理，宁可不给能力。
     */
    public static boolean mountInAutomation(Boolean readOnlyHint) {
        return Boolean.TRUE.equals(readOnlyHint);
    }

    /**
     * 把平台级启用的 MCP 服务挂进助手工具面（AgentFactory 在平台/业务工具之后调用）。
     * 单服务失败=审计 MOUNT_ERROR + warn 后继续——服务下线绝不连坐助手会话。
     *
     * @return 本次实际新增的工具名（并入注册面返回值，供调用方统计/日志）
     */
    public Set<String> attachEnabled(Toolkit toolkit, String tenantId, Long userId, String aguiThreadId,
                                     io.agentscope.core.permission.PermissionContextState.Builder pb,
                                     java.util.Map<String, String> toolGate, int roleLevel) {
        return attachEnabled(toolkit, tenantId, userId, aguiThreadId, pb, toolGate, roleLevel, null);
    }

    /**
     * @param allowedServers null=平台启用的全挂（对话侧原行为）；非 null=只挂名单内的服务，
     *                       且**只挂 read_only='1' 的行**——自动化无人值守，可写服务永不挂
     *                       （旧板题 3 乙 三条硬规矩：默认关 / 没勾不挂 / 可写不挂）。
     */
    public Set<String> attachEnabled(Toolkit toolkit, String tenantId, Long userId, String aguiThreadId,
                                     io.agentscope.core.permission.PermissionContextState.Builder pb,
                                     java.util.Map<String, String> toolGate, int roleLevel,
                                     java.util.Set<String> allowedServers) {
        Set<String> added = new LinkedHashSet<>();
        List<DcsMcpServer> rows;
        try {
            rows = TenantHelper.ignore(this::listEnabledForPlatform);
        } catch (Exception e) {
            log.warn("[McpPilot] 查询启用服务失败（跳过挂载不连坐）：{}", e.getMessage());
            return added;
        }
        // 三审回修（dsflash/dspro P1）：装配热路径总预算 5s——N 个慢/下线服务不再
        // 串行叠加 20s×N（60s 收割线会误杀建会话中的 run）；超预算跳过记审计
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        AttachKey sessionKey = new AttachKey("t:" + tenantId + ":" + userId, aguiThreadId);
        for (DcsMcpServer row : rows) {
            if (allowedServers != null) {
                if (!"1".equals(row.getReadOnly())) {
                    log.info("[McpPilot] {} 不是只读服务，自动化不挂载", row.getServerName());
                    continue;
                }
                if (!allowedServers.contains(row.getServerName())) {
                    continue;
                }
            }
            // G4-#4：MCP 纳入工具闸（server 粒度键 mcp:<server>，与平台工具同份 tool_gate JSON，
            // 生效时机同款=下一轮建会话）。off=整租户关闭（跳过挂载）；admin=仅管理员角色；
            // member/未配=全员（MCP 内置档=member）。工具级粒度需挂载后才知工具名，本期不做。
            // G4 三审回修（dsflash P2-4）：tool_gate JSON 被污染时 fail-closed——哨兵键在即
            // 全部跳过（否则 mcp: 键不在坏配置兜底表里，已关服务会静默复活）
            String g = toolGate == null ? null : toolGate.get("mcp:" + row.getServerName());
            if (toolGate != null && toolGate.containsKey(AssistantConfigService.POISONED_GATE_SENTINEL)) {
                log.warn("[McpPilot] 租户 {} tool_gate 配置损坏，本轮跳过全部 MCP 挂载（fail-closed）", tenantId);
                return added;
            }
            if ("off".equals(g)) {
                log.info("[McpPilot] {} 被租户 {} 工具闸关闭，跳过挂载", row.getServerName(), tenantId);
                continue;
            }
            if ("admin".equals(g) && roleLevel < com.smartdock.assistant.legacy.tools.ToolIdentity.LEVEL_ADMIN) {
                log.info("[McpPilot] {} 对租户 {} 配置为仅管理员，当前用户角色不足，跳过挂载",
                    row.getServerName(), tenantId);
                continue;
            }
            long remainMs = (deadline - System.nanoTime()) / 1_000_000;
            if (remainMs <= 0) {
                audit(row.getServerName(), tenantId, userId, null, "MOUNT_ERROR", "0",
                    "挂载预算（5s）耗尽，本轮跳过", null);
                log.warn("[McpPilot] 挂载 {} 跳过：预算耗尽", row.getServerName());
                continue;
            }
            Set<String> before = new LinkedHashSet<>(toolkit.getToolNames());
            McpClientWrapper raw = null;
            try {
                // G3-A1：STDIO 行开 elicitation 反问桥。坑（调研实证）：requestTimeout
                // 覆盖 tools/call 全程含等用户答题的时长——10s 不调大则用户答得再快也常超时。
                // 注册 handler 才会向 server 声明 ClientCapabilities.elicitation（天然 opt-in，
                // 不注册的行行为零变化）。
                // 三审回修（dspro P1-3）：预算显式拆分——requestTimeout=答题上限+工具预算
                // （反问前后处理/收尾），固定 30s 余量在"server 先干活再反问/答完还要收尾/
                // 单调用多轮反问"时必超；工具预算可配，默认 120s。总额钳 595s 保恒等式
                // （答题+handler 余量+工具预算 < 600s 纯心跳收割线——elicit 顶配 540 时
                // 120s 预算会被压到 55s，宁可工具预算缩水也不能让 run 被收割）。注：单次
                // 调用多轮反问仍共享同一预算（框架 requestTimeout 单值），v1 已知边界
                boolean stdio = "STDIO".equals(row.getTransportType());
                McpClientBuilder builder = McpClientBuilder.create(row.getServerName())
                    .timeout(Duration.ofSeconds(stdio
                        ? Math.min(595L, elicitationBridge.elicitTimeoutSeconds() + 5L + toolBudgetSeconds) : 10))
                    .initializationTimeout(Duration.ofSeconds(10));
                if (stdio) {
                    builder.stdioTransport(row.getCommand(), McpToolFacts.parseArgs(row.getArgs()), Map.of())
                        .syncElicitation(req -> elicitationBridge.handle(
                            row.getServerName(), tenantId, userId, aguiThreadId, req));
                } else if ("STREAMABLE_HTTP".equals(row.getTransportType())) {
                    // G4-#1：HTTP 行带鉴权头——落库密文先解密再喂 builder（2.0.4 javap 实证 headers(Map)）
                    builder.headers(McpToolFacts.parseHeaders(headerCipher.decrypt(row.getHeaders())));
                    builder.streamableHttpTransport(row.getUrl());
                } else {
                    builder.headers(McpToolFacts.parseHeaders(headerCipher.decrypt(row.getHeaders())));
                    builder.sseTransport(row.getUrl());
                }
                raw = builder.buildSync();
                // 预检前置 initialize：listTools 在未初始化时直接抛 IllegalStateException；
                // initialize() 有标志位幂等，后续 registerMcpClient 内部的重复 initialize 会 no-op
                raw.initialize().block(Duration.ofMillis(remainMs));
                long remainAfterInit = (deadline - System.nanoTime()) / 1_000_000;
                if (remainAfterInit <= 0) {
                    throw new ServiceException("挂载预算（5s）耗尽（initialize 后）");
                }
                // 撞名预检（三审回修 dspro P1-5）+ 工具级只读注解收集（G4-#3）：
                // 撞名直接拒绝整服务（框架同名 Map.put 静默覆盖，与 ProviderToolRegistry 对齐）；
                // 写类判定=注解显式优先，无注解兜底跟服务级 read_only（试点恒 '1' 行为零变化）：
                // readOnlyHint=TRUE→只读放行；=FALSE→写类进确认闸（与 G1 平台写类同款 ASK）；
                // =null→服务 read_only='1' 视为只读，'0'（放开前 ASK 兜底，fail-safe）
                List<io.modelcontextprotocol.spec.McpSchema.Tool> tools =
                    raw.listTools().block(Duration.ofMillis(remainAfterInit));
                java.util.Set<String> writeTools = new java.util.HashSet<>();
                // 三审 P1：自动化（allowedServers != null）是无人值守，只挂**显式声明只读**的工具。
                // 注解是第三方服务自声明，没声明就当写类——宁可不给能力，也不让半夜自己写数据。
                // 交互会话保持旧口径（无注解且服务只读 → 放行），否则现网工具会大面积消失。
                java.util.Set<String> explicitReadOnly = new java.util.HashSet<>();
                boolean automationMode = allowedServers != null;
                boolean serverReadOnly = !"0".equals(row.getReadOnly());
                for (io.modelcontextprotocol.spec.McpSchema.Tool t : tools == null ? List.<io.modelcontextprotocol.spec.McpSchema.Tool>of() : tools) {
                    if (before.contains(t.name())) {
                        throw new ServiceException("MCP 工具名与现有工具冲突（" + t.name() + "），已拒绝挂载避免静默覆盖");
                    }
                    Boolean hint = t.annotations() == null ? null : t.annotations().readOnlyHint();
                    if (isWriteTool(hint, serverReadOnly)) {
                        writeTools.add(t.name());
                    } else if (mountInAutomation(hint)) {
                        explicitReadOnly.add(t.name());
                    }
                }
                GatedMcpClient gated = new GatedMcpClient(raw, tenantId, userId, row.getServerName(),
                    callLogMapper, writeTools);
                // 装饰层：调用级限频 + 双态审计（工具列表/初始化直接委托）
                toolkit.registerMcpClient(gated).block(Duration.ofMillis(Math.max(remainMs, 1_000)));
                // F8（2026-10-31 复审回修）：remark 的「N 个工具」按本服务自己的清单计数——
                // 跨服务累计的 added 会拼出「1 个工具 [a工具, b工具]」的自相矛盾文案
                java.util.Set<String> mountedNames = new java.util.LinkedHashSet<>();
                for (String name : toolkit.getToolNames()) {
                    if (!before.contains(name)) {
                        // 无人值守模式：没显式 readOnlyHint 的工具不进白名单（不进 added），
                        // AgentFactory 的只读裁剪会把它们从 toolkit 摘掉；连规则都不必加
                        if (automationMode && !explicitReadOnly.contains(name)) {
                            continue;
                        }
                        // G4-#3：MCP 工具不再无条件 ALLOW——写类（注解 FALSE 或写类服务无注解）
                        // 进 ASK 确认卡，堵「放开 read_only 后写类 MCP 绕确认闸」的 G1 同款事故面
                        boolean write = writeTools.contains(name);
                        if (write) {
                            pb.addAskRule(name, new PermissionRule(name, "", PermissionBehavior.ASK, "dcs-assistant-mcp"));
                        } else {
                            pb.addAllowRule(name, new PermissionRule(name, "", PermissionBehavior.ALLOW, "dcs-assistant-mcp"));
                        }
                        added.add(name);
                        mountedNames.add(name);
                    }
                }
                // 同会话同服务重挂（会话失效重建）先关旧客户端——旧 toolkit 已随上轮 run 丢弃，
                // 不关就是同 key 下越积越多的泄漏；等清扫兜底会多活一轮（60s）
                McpClientWrapper stale = attachedBySession
                    .computeIfAbsent(sessionKey, k -> new java.util.concurrent.ConcurrentHashMap<>())
                    .put(row.getServerName(), gated);
                if (stale != null && stale != gated) {
                    try {
                        stale.close();
                    } catch (Exception ignore) {
                        // 旧客户端关闭失败：进程随 JVM 生命周期，不连坐本次挂载
                    }
                }
                audit(row.getServerName(), tenantId, userId, null, "MOUNT_OK", "1",
                    null, mountedNames.size() + " 个工具 " + mountedNames);
                raw = null;
            } catch (Exception e) {
                audit(row.getServerName(), tenantId, userId, null, "MOUNT_ERROR", "0",
                    truncate(e.getMessage()), null);
                log.warn("[McpPilot] 挂载 {} 失败（跳过不连坐）：{}", row.getServerName(), e.getMessage());
            } finally {
                // 三审回修（三席共识）：失败/超时路径必须 close——block 取消不会触发框架
                // 内部的 doOnError close，不关=子进程泄漏
                if (raw != null) {
                    try {
                        raw.close();
                    } catch (Exception ignore) {
                        // 关闭失败只能放弃：进程随 JVM 生命周期
                    }
                }
            }
        }
        return added;
    }

    public static String truncate(String msg) {
        if (msg == null) {
            return "";
        }
        return msg.length() > 480 ? msg.substring(0, 480) : msg;
    }

    /** 耗时尾缀（"4ms" / "write 123ms"）：写类调用带 write 前缀，取尾部数字才不丢审计行 */
    private static final Pattern DURATION_MS = Pattern.compile("(\\d+)ms$");

    /**
     * 调用审计行的落库口径（纯函数，供单测直接断言）：成功行「详情/错误」只留真错误，
     * 耗时与写标记一律只进 duration_ms。
     *
     * <p>2026-10-30 题6 之前这里把 remark（成功路径传的是 "4ms"）兜底写进 error_msg——
     * 前端「详情/错误」列与「耗时」列逐行同值，导出与接口消费方也拿到错口径。
     * 历史行不做数据清洗（前端成功行已兜成「—」），仅新数据归位。</p>
     *
     * <p>口径只覆盖工具调用行（CALL_* / RATE_LIMITED）。挂载行（MOUNT_*，走实例 audit()）与
     * 反问行（ELICIT_*，走 AssistantElicitationBridge）的 error_msg 列承载结果详情
     * （挂载工具清单 / 确认文案），成功行也保留——这是该列对非调用行的既定语义，
     * 勿按本函数「成功置空」口径去纠正。</p>
     */
    public static DcsMcpCallLog buildCallLog(String serverName, String tenantId, Long userId, String toolName,
                                             String action, String status, String errorMsg, String remark) {
        DcsMcpCallLog l = new DcsMcpCallLog();
        l.setTenantId(tenantId);
        l.setUserId(userId);
        l.setServerName(serverName);
        l.setToolName(toolName);
        l.setAction(action);
        l.setStatus(status);
        // 成功行（status=1）的「详情/错误」保持空；失败行保留真错误文本
        l.setErrorMsg("1".equals(status) ? null : errorMsg);
        l.setDurationMs(parseDurationMs(remark));
        l.setCreateTime(new java.util.Date());
        return l;
    }

    /** F9（2026-10-31 复审回修）：尾缀数字超 int（或非数字）一律置 null——解析失败不能丢整条审计行 */
    private static Integer parseDurationMs(String remark) {
        if (remark == null) return null;
        java.util.regex.Matcher dm = DURATION_MS.matcher(remark);
        if (!dm.find()) return null;
        try {
            long v = Long.parseLong(dm.group(1));
            return v > Integer.MAX_VALUE ? null : (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 挂载行（MOUNT_*）专用：error_msg 写挂载结果详情（如「3 个工具 [a, b]」），成功行也保留；
     *  不走 buildCallLog 的「成功置空」口径——那是工具调用行的语义，详见其 javadoc。 */
    void audit(String serverName, String tenantId, Long userId, String toolName,
               String action, String status, String errorMsg, String remark) {
        try {
            DcsMcpCallLog l = new DcsMcpCallLog();
            l.setTenantId(tenantId);
            l.setUserId(userId);
            l.setServerName(serverName);
            l.setToolName(toolName);
            l.setAction(action);
            l.setStatus(status);
            l.setErrorMsg(errorMsg != null ? errorMsg : remark);
            l.setCreateTime(new java.util.Date());
            callLogMapper.insert(l);
        } catch (Exception e) {
            log.warn("[McpPilot] 审计写入失败（不连坐）：{}", e.getMessage());
        }
    }

    /**
     * 调用级限频+审计装饰器：包住真实 wrapper，callTool 前计数（30/分/租户·服务），
     * 超限返回错误（模型可见人话）；成功/失败双态落审计。其余全部委托。
     */
    static class GatedMcpClient extends McpClientWrapper {

        private final McpClientWrapper delegate;
        private final String tenantId;
        private final Long userId;
        private final String serverName;
        private final DcsMcpCallLogMapper callLogMapper;
        /** G4-#3：写类工具名集合（挂载时按 readOnlyHint 注解/服务级判定）——审计 remark 标 write */
        private final java.util.Set<String> writeTools;

        GatedMcpClient(McpClientWrapper delegate, String tenantId, Long userId,
                       String serverName, DcsMcpCallLogMapper callLogMapper,
                       java.util.Set<String> writeTools) {
            super(delegate.getName());
            this.delegate = delegate;
            this.tenantId = tenantId;
            this.userId = userId;
            this.serverName = serverName;
            this.callLogMapper = callLogMapper;
            this.writeTools = writeTools == null ? java.util.Set.of() : writeTools;
        }

        @Override
        public reactor.core.publisher.Mono<Void> initialize() {
            return delegate.initialize();
        }

        @Override
        public reactor.core.publisher.Mono<List<io.modelcontextprotocol.spec.McpSchema.Tool>> listTools() {
            return delegate.listTools();
        }

        @Override
        public reactor.core.publisher.Mono<io.modelcontextprotocol.spec.McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments) {
            return gated(toolName, () -> delegate.callTool(toolName, arguments));
        }

        @Override
        public reactor.core.publisher.Mono<io.modelcontextprotocol.spec.McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
            return gated(toolName, () -> delegate.callTool(toolName, arguments, meta));
        }

        private reactor.core.publisher.Mono<io.modelcontextprotocol.spec.McpSchema.CallToolResult> gated(
            String toolName, java.util.function.Supplier<reactor.core.publisher.Mono<io.modelcontextprotocol.spec.McpSchema.CallToolResult>> call) {
            String key = "mcp:rl:" + tenantId + ":" + serverName;
            long n;
            try {
                // 三审回修（dspro P1-6）：INCR+EXPIRE 原子版（Lua）——两步法在 incr 后
                // 崩溃会留下永生 key，30 次后该租户永久限频；原子版每刷 TTL=滑动窗口更严
                n = RedisUtils.incrAtomicValue(key, Duration.ofSeconds(60).toMillis());
            } catch (Exception e) {
                log.warn("[McpPilot] 限频计数不可用（放行本次调用）：{}", e.getMessage());
                n = 0;
            }
            if (n > RATE_LIMIT_PER_MIN) {
                audit(callLogMapper, serverName, tenantId, userId, toolName, "RATE_LIMITED", "0", null, null);
                return reactor.core.publisher.Mono.error(new IllegalStateException(
                    "外部工具调用触发限频（" + RATE_LIMIT_PER_MIN + " 次/分/租户），请稍后再试"));
            }
            long t0 = System.currentTimeMillis();
            // G4-#3：写类调用 remark 前缀 write——闸矩阵/审计侧可区分读写（防注解注册后被换的观测兜底）
            String wmark = writeTools.contains(toolName) ? "write " : "";
            return call.get()
                // 三审回修（dsflash/dspro P2）：MCP 侧 isError=true 也算失败（工具级报错不再记成功）
                .doOnSuccess(r -> {
                    if (r != null && Boolean.TRUE.equals(r.isError())) {
                        audit(callLogMapper, serverName, tenantId, userId, toolName, "CALL_ERROR", "0",
                            wmark + truncate(String.valueOf(r.content())), null);
                    } else {
                        audit(callLogMapper, serverName, tenantId, userId, toolName, "CALL_OK", "1", null,
                            wmark + (System.currentTimeMillis() - t0) + "ms");
                    }
                })
                .doOnError(e -> audit(callLogMapper, serverName, tenantId, userId, toolName, "CALL_ERROR", "0",
                    wmark + truncate(e.getMessage()), null));
        }

        @Override
        public void close() {
            delegate.close();
        }

        static void audit(DcsMcpCallLogMapper mapper, String serverName, String tenantId, Long userId,
                          String toolName, String action, String status, String errorMsg, String remark) {
            try {
                // 落库口径统一收在 buildCallLog（题6：成功行不再把耗时当错误信息写进 error_msg）
                mapper.insert(McpPilotService.buildCallLog(serverName, tenantId, userId, toolName,
                    action, status, errorMsg, remark));
            } catch (Exception e) {
                log.warn("[McpPilot] 调用审计写入失败（不连坐）：{}", e.getMessage());
            }
        }
    }

    /* 供 controller 分页审计用 */
}

