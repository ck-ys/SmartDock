package org.dromara.assistant.service;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsMcpCallLog;
import org.dromara.assistant.domain.DcsMcpServer;
import org.dromara.assistant.mapper.DcsMcpCallLogMapper;
import org.dromara.assistant.mapper.DcsMcpServerMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 批2 Q2=A（2026-10-21）：MCP 外部工具试点。
 *
 * <p>安全闸（dcs biz22 A4 对照吸收，数字照抄）：①STDIO 命令白名单只允许 node，
 * 脚本路径必须落在配置允许目录内（canonical 前缀比对防穿越）；②HTTP 传输 host
 * 必须在白名单（默认仅 localhost/127.0.0.1，可配置追加）；③试点只收只读服务且
 * <b>免密钥</b>（headers 含 Authorization/token/secret 的直接拒存——真要密钥时
 * B+1 做加密存储）；④挂载失败/服务下线只记审计+warn，绝不连坐助手会话；
 * ⑤调用级限频 30 次/分/租户（GatedMcpClient 装饰层，超限调用报错给模型人话）；
 * ⑥挂载与每次真实调用双态审计落 dcs_mcp_call_log。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpPilotService {

    /** 试点=平台级共享（预留租户级） */
    public static final String PLATFORM_TENANT = "000000";

    /** dcs 对照吸收：调用限频 30 次/分/租户 */
    static final int RATE_LIMIT_PER_MIN = 30;

    private static final Set<String> ALLOWED_COMMANDS = Set.of("node", "node.exe");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,29}$");

    /** 独立 mapper（纯函数单测可调——JsonUtils 静态块依赖 Spring 容器，批1 旧坑） */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private final DcsMcpServerMapper serverMapper;
    private final DcsMcpCallLogMapper callLogMapper;
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

    /** 清扫：会话已淘汰（不在管理器里）→ close 其全部 MCP 客户端（node 子进程/HTTP 连接） */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    void sweepOrphanMcpClients() {
        attachedBySession.forEach((key, perServer) -> {
            if (threadSessionManager.getSession(key.rcUserId(), key.threadId()).isEmpty()) {
                attachedBySession.remove(key);
                for (McpClientWrapper c : perServer.values()) {
                    try {
                        c.close();
                    } catch (Exception e) {
                        log.warn("[McpPilot] 清扫关闭 {} 失败（忽略）：{}", key, e.getMessage());
                    }
                }
                log.info("[McpPilot] 会话 {}|{} 已淘汰，回收其 {} 个 MCP 客户端",
                    key.rcUserId(), key.threadId(), perServer.size());
            }
        });
    }

    /** STDIO 脚本允许目录（绝对路径，逗号分隔；空=禁用 STDIO 注册） */
    @Value("${smartdock.mcp.allowed-dirs:}")
    private String allowedDirs;

    /** HTTP 传输 host 白名单（逗号分隔；默认只放行本机） */
    @Value("${smartdock.mcp.allowed-http-hosts:localhost,127.0.0.1}")
    private String allowedHttpHosts;

    /* ============================ 管理面 ============================ */

    public List<DcsMcpServer> listEnabledForPlatform() {
        return serverMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DcsMcpServer>()
            .eq(DcsMcpServer::getEnabled, "1"));
    }

    public List<DcsMcpServer> listAll() {
        return serverMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DcsMcpServer>()
            .orderByDesc(DcsMcpServer::getUpdateTime));
    }

    /** 新增/编辑：白名单校验（命令/脚本目录/host/危险头）全在这——controller 只管鉴权与审计 */
    public void save(DcsMcpServer row) {
        if (row.getServerName() == null || !NAME_PATTERN.matcher(row.getServerName()).matches()) {
            throw new ServiceException("服务名限 3-30 位小写字母/数字/下划线，且以字母开头");
        }
        normalizeAndValidate(row);
        java.util.Date now = new java.util.Date();
        if (row.getId() == null) {
            row.setTenantId(PLATFORM_TENANT);
            row.setReadOnly("1");
            row.setCreateTime(now);
            row.setUpdateTime(now);
            try {
                serverMapper.insert(row);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new ServiceException("服务名已存在：" + row.getServerName());
            }
        } else {
            row.setTenantId(null);
            row.setReadOnly(null);
            row.setUpdateTime(now);
            serverMapper.updateById(row);
        }
    }

    public void toggle(Long id, String enabled) {
        if (!"0".equals(enabled) && !"1".equals(enabled)) {
            throw new ServiceException("enabled 只支持 0/1");
        }
        DcsMcpServer patch = new DcsMcpServer();
        patch.setId(id);
        patch.setEnabled(enabled);
        patch.setUpdateTime(new java.util.Date());
        if (serverMapper.updateById(patch) == 0) {
            throw new ServiceException("记录不存在或已删除");
        }
    }

    /** 试点物理删（审计表留痕），无外键依赖 */
    public void delete(Long id) {
        serverMapper.deleteById(id);
    }

    /** 白名单校验单点（save 与测试共用） */
    void normalizeAndValidate(DcsMcpServer row) {
        String t = row.getTransportType();
        if ("STDIO".equals(t)) {
            if (!ALLOWED_COMMANDS.contains(row.getCommand() == null ? "" : row.getCommand())) {
                throw new ServiceException("STDIO 试点只允许 node 命令（要接其它运行时请在白名单配置中评估后开放）");
            }
            List<String> args = parseArgs(row.getArgs());
            List<String> dirs = splitCsv(allowedDirs);
            if (dirs.isEmpty()) {
                throw new ServiceException("STDIO 未配置脚本允许目录（smartdock.mcp.allowed-dirs），暂不可注册");
            }
            for (String a : args) {
                if (a == null || a.isBlank()) {
                    throw new ServiceException("args 须是非空字符串数组（元素 " + a + "）");
                }
                java.nio.file.Path canonical;
                try {
                    canonical = Path.of(a).toRealPath();
                } catch (java.io.IOException e) {
                    throw new ServiceException("脚本路径不存在：" + a);
                } catch (java.nio.file.InvalidPathException e) {
                    throw new ServiceException("脚本路径非法：" + a);
                }
                boolean inside = false;
                for (String d : dirs) {
                    try {
                        // 三审回修（三席共识 P1）：Path.startsWith 按路径段比对——
                        // 字符串前缀会让同级兄弟目录 mcp-demo-evil 绕过白名单
                        if (canonical.startsWith(Path.of(d).toRealPath())) {
                            inside = true;
                            break;
                        }
                    } catch (java.io.IOException e) {
                        // 白名单目录本身不存在：跳过该目录
                    }
                }
                if (!inside) {
                    throw new ServiceException("脚本路径不在允许目录内：" + a + "（允许：" + String.join("; ", dirs) + "）");
                }
            }
            row.setUrl(null);
        } else if ("STREAMABLE_HTTP".equals(t) || "SSE".equals(t)) {
            URI uri;
            try {
                uri = URI.create(row.getUrl() == null ? "" : row.getUrl());
            } catch (IllegalArgumentException e) {
                throw new ServiceException("服务地址格式不正确");
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new ServiceException("服务地址只支持 http/https");
            }
            if (uri.getRawUserInfo() != null || (uri.getRawQuery() != null && uri.getRawQuery().toLowerCase().matches(".*(token|key|secret|password)=.*"))) {
                throw new ServiceException("服务地址不得携带账号信息或密钥参数（试点免密钥）");
            }
            Set<String> hosts = new LinkedHashSet<>(Arrays.asList("localhost", "127.0.0.1"));
            hosts.addAll(splitCsv(allowedHttpHosts));
            if (uri.getHost() == null || !hosts.contains(uri.getHost().toLowerCase())) {
                throw new ServiceException("服务地址 host 不在白名单内（默认仅本机；可在 smartdock.mcp.allowed-http-hosts 追加）");
            }
            row.setCommand(null);
            row.setArgs(null);
        } else {
            throw new ServiceException("传输类型只支持 STDIO / STREAMABLE_HTTP / SSE");
        }
        validateHeaders(row.getHeaders());
        if (row.getEnabled() == null) {
            row.setEnabled("0");
        }
    }

    /** 三审回修（dsflash P1/glm53 P2）：试点一律不接收请求头——免密钥定位下无用途，
     * 精确键名黑名单可被 X-Auth-Token/Cookie 绕过且值明文入库；真密钥接入+加密存储留给 B+1 */
    public static void validateHeaders(String headersJson) {
        if (headersJson != null && !headersJson.isBlank() && !headersJson.trim().equals("{}")) {
            throw new ServiceException("试点不接收请求头（免密钥定位；密钥接入与加密存储排在 B+1）");
        }
    }

    public static List<String> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return List.of();
        }
        String[] arr;
        try {
            arr = JSON_MAPPER.readValue(argsJson, String[].class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException("args 须是字符串数组 JSON");
        }
        if (arr == null || Arrays.stream(arr).anyMatch(x -> x == null || x.isBlank())) {
            throw new ServiceException("args 须是非空字符串数组");
        }
        return Arrays.asList(arr);
    }

    public static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /* ============================ 挂载（建会话链路） ============================ */

    /**
     * 把平台级启用的 MCP 服务挂进助手工具面（AgentFactory 在平台/业务工具之后调用）。
     * 单服务失败=审计 MOUNT_ERROR + warn 后继续——服务下线绝不连坐助手会话。
     *
     * @return 本次实际新增的工具名（并入注册面返回值，供调用方统计/日志）
     */
    public Set<String> attachEnabled(Toolkit toolkit, String tenantId, Long userId, String aguiThreadId,
                                     io.agentscope.core.permission.PermissionContextState.Builder pb) {
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
                McpClientBuilder builder = McpClientBuilder.create(row.getServerName())
                    .timeout(Duration.ofSeconds(10))
                    .initializationTimeout(Duration.ofSeconds(10));
                if ("STDIO".equals(row.getTransportType())) {
                    builder.stdioTransport(row.getCommand(), parseArgs(row.getArgs()), Map.of());
                } else if ("STREAMABLE_HTTP".equals(row.getTransportType())) {
                    builder.streamableHttpTransport(row.getUrl());
                } else {
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
                // 三审回修（dspro P1-5）：撞名预检——框架同名 Map.put 静默覆盖内置/业务
                // 工具；试点直接拒绝整服务（与 ProviderToolRegistry"撞名让位"策略对齐）
                List<io.modelcontextprotocol.spec.McpSchema.Tool> tools =
                    raw.listTools().block(Duration.ofMillis(remainAfterInit));
                for (io.modelcontextprotocol.spec.McpSchema.Tool t : tools == null ? List.<io.modelcontextprotocol.spec.McpSchema.Tool>of() : tools) {
                    if (before.contains(t.name())) {
                        throw new ServiceException("MCP 工具名与现有工具冲突（" + t.name() + "），已拒绝挂载避免静默覆盖");
                    }
                }
                GatedMcpClient gated = new GatedMcpClient(raw, tenantId, userId, row.getServerName(), callLogMapper);
                // 装饰层：调用级限频 + 双态审计（工具列表/初始化直接委托）
                toolkit.registerMcpClient(gated).block(Duration.ofMillis(Math.max(remainMs, 1_000)));
                int mounted = 0;
                for (String name : toolkit.getToolNames()) {
                    if (!before.contains(name)) {
                        pb.addAllowRule(name, new PermissionRule(name, "", PermissionBehavior.ALLOW, "dcs-assistant-mcp"));
                        added.add(name);
                        mounted++;
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
                    null, mounted + " 个工具 " + added);
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

        GatedMcpClient(McpClientWrapper delegate, String tenantId, Long userId,
                       String serverName, DcsMcpCallLogMapper callLogMapper) {
            super(delegate.getName());
            this.delegate = delegate;
            this.tenantId = tenantId;
            this.userId = userId;
            this.serverName = serverName;
            this.callLogMapper = callLogMapper;
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
            return call.get()
                // 三审回修（dsflash/dspro P2）：MCP 侧 isError=true 也算失败（工具级报错不再记成功）
                .doOnSuccess(r -> {
                    if (r != null && Boolean.TRUE.equals(r.isError())) {
                        audit(callLogMapper, serverName, tenantId, userId, toolName, "CALL_ERROR", "0",
                            truncate(String.valueOf(r.content())), null);
                    } else {
                        audit(callLogMapper, serverName, tenantId, userId, toolName, "CALL_OK", "1", null,
                            (System.currentTimeMillis() - t0) + "ms");
                    }
                })
                .doOnError(e -> audit(callLogMapper, serverName, tenantId, userId, toolName, "CALL_ERROR", "0",
                    truncate(e.getMessage()), null));
        }

        @Override
        public void close() {
            delegate.close();
        }

        static void audit(DcsMcpCallLogMapper mapper, String serverName, String tenantId, Long userId,
                          String toolName, String action, String status, String errorMsg, String remark) {
            try {
                DcsMcpCallLog l = new DcsMcpCallLog();
                l.setTenantId(tenantId);
                l.setUserId(userId);
                l.setServerName(serverName);
                l.setToolName(toolName);
                l.setAction(action);
                l.setStatus(status);
                l.setErrorMsg(errorMsg != null ? errorMsg : remark);
                l.setDurationMs(remark != null && remark.endsWith("ms") ? Integer.valueOf(remark.replace("ms", "")) : null);
                l.setCreateTime(new java.util.Date());
                mapper.insert(l);
            } catch (Exception e) {
                log.warn("[McpPilot] 调用审计写入失败（不连坐）：{}", e.getMessage());
            }
        }
    }

    /* 供 controller 分页审计用 */
}

