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
 * 必须在白名单（默认仅 localhost/127.0.0.1，可配置追加）；③试点只收只读服务（DDL
 * 钉死 read_only='1'；G4-#1 起 HTTP 行可配鉴权头——值 AES 加密落库+键名回显，
 * 见 validateHeaders/encryptHeaders）；④挂载失败/服务下线只记审计+warn，绝不连坐助手会话；
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

    /** G4-#1 headers 显式加密密钥（16 位——AES 硬性要求 16/24/32；与实体注解同值，
     *  ENC_ 前缀幂等互认不会双重加密）。首版 "DcsMcpEncKey16" 是 14 位：拦截器路径
     *  抛 IllegalArgument 被其 catch 吞掉→明文落库（活库 g4htest 行实锤） */
    static final String HEADERS_ENC_KEY = "DcsMcpHeadersK16";

    /**
     * G4-#1 实测坑（2026-10-22 活库 g4htest 行实锤）：@EncryptField 注解在、启动扫描
     * 单测过（G4EncryptScanTest），但运行时插入仍明文——拦截器链为何没吃到本实体留给
     * 三审深挖。安全字段不赌隐式装配：服务层显式加解密（EncryptorManager 同款算法与
     * 密钥），对 ENC_ 前缀幂等——无论拦截器将来是否生效，双轨都安全不重不漏。
     */
    private final org.springframework.beans.factory.ObjectProvider<org.dromara.common.encrypt.core.EncryptorManager> encryptorManagerProvider;

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

    /** STDIO 脚本允许目录（绝对路径，逗号分隔；空=禁用 STDIO 注册） */
    @Value("${smartdock.mcp.allowed-dirs:}")
    private String allowedDirs;

    /** G4-#5：审计保留期清理（90 天）——分批 5000 行防长事务锁表。cron 03:55：
     *  G4 三审回修（dspro P2）错峰——03:40 已被 AssistantRetentionJob/ProfileRetentionJob
     *  占用；单实例口径（多节点部署时再引分布式锁，登记池） */
    @Scheduled(cron = "0 55 3 * * ?")
    void sweepExpiredCallLogs() {
        try {
            java.util.Date cutoff = new java.util.Date(
                System.currentTimeMillis() - 90L * 24 * 3600 * 1000);
            int total = 0;
            int n;
            do {
                n = callLogMapper.deleteBatchBefore(cutoff);
                total += n;
            } while (n >= 5000);
            if (total > 0) {
                log.info("[McpPilot] 审计保留期清理：删除 {} 行（>90 天）", total);
            }
        } catch (Exception e) {
            // glm53 终审 P2：定时任务必须兜底（对照 AssistantRetentionJob 风格）——
            // 清理失败不连坐调度线程，明早重试
            log.error("[McpPilot] 审计保留期清理失败（明日重试）：{}", e.getMessage(), e);
        }
    }

    /** HTTP 传输 host 白名单（逗号分隔；默认只放行本机） */
    @Value("${smartdock.mcp.allowed-http-hosts:localhost,127.0.0.1}")
    private String allowedHttpHosts;

    /** G3-A1：elicitation 反问桥——STDIO 行注册 handler（挂起等用户作答），HTTP 行暂不开（sync handler 阻塞入站线程的滞留风险待真机观察） */
    private final org.dromara.assistant.core.AssistantElicitationBridge elicitationBridge;

    /** G3-A1 三审回修：STDIO 工具调用自身预算（不含答题）——反问前后处理+收尾的余量 */
    @Value("${smartdock.mcp.tool-budget-seconds:120}")
    private int toolBudgetSeconds;

    /* ============================ 管理面 ============================ */

    public List<DcsMcpServer> listEnabledForPlatform() {
        return serverMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DcsMcpServer>()
            .eq(DcsMcpServer::getEnabled, "1"));
    }

    public List<DcsMcpServer> listAll() {
        return serverMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DcsMcpServer>()
            .orderByDesc(DcsMcpServer::getUpdateTime));
    }

    /** G4-#4：全部服务名（工具闸 mcp: 键校验用；平台级表，跨租户读） */
    public java.util.Set<String> allServerNames() {
        return TenantHelper.ignore(() -> serverMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DcsMcpServer>()
                    .select(DcsMcpServer::getServerName))
            .stream().map(DcsMcpServer::getServerName).collect(java.util.stream.Collectors.toSet()));
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
            // G4-#1：headers 明文 → ENC_ 密文落库（显式加密，不赌拦截器装配）
            row.setHeaders(encryptHeaders(row.getHeaders()));
            try {
                serverMapper.insert(row);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new ServiceException("服务名已存在：" + row.getServerName());
            }
        } else {
            row.setTenantId(null);
            row.setReadOnly(null);
            row.setUpdateTime(now);
            // G4-#1：编辑面只见键名不见值——headers 留空=保持原值（null 不参与 updateById）；
            // 传 "{}"=清空；传 JSON=替换（加密落库）
            if (row.getHeaders() != null && row.getHeaders().isBlank()) {
                row.setHeaders(null);
            } else {
                row.setHeaders(encryptHeaders(row.getHeaders()));
            }
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
        // G4 三审回修（dsflash P2-6）：STDIO 行不支持请求头（MCP STDIO 传输无 HTTP 头语义，
        // 挂载侧只会静默丢弃）——保存面显式拒绝，防"配了却不生效"的错觉
        if ("STDIO".equals(row.getTransportType())
            && row.getHeaders() != null && !row.getHeaders().isBlank()
            && !row.getHeaders().trim().equals("{}")) {
            throw new ServiceException("STDIO 行不支持请求头（仅 HTTP 行生效）");
        }
        if (row.getEnabled() == null) {
            row.setEnabled("0");
        }
    }

    /**
     * G4-#1（2026-10-22）：放开请求头接收（原试点一律拒收）。三面封死明文风险后黑名单
     * 不再必要——值显式 AES 落库（见 encryptHeaders）+ 回显只出键名 + save 端点不落请求
     * 体日志；键名黑名单方案被否：鉴权头（Authorization/X-Api-Key 等）正是本功能要接的
     * 目标。只做形状与尺寸校验。
     */
    public static void validateHeaders(String headersJson) {
        parseHeaders(headersJson);
    }

    /** headers 显式加密（G4-#1）：明文 JSON → ENC_ 密文；空/{} 直通（无敏感面） */
    String encryptHeaders(String plainJson) {
        if (plainJson == null || plainJson.isBlank() || plainJson.trim().equals("{}")
            || plainJson.startsWith("ENC_")) {
            return plainJson;
        }
        org.dromara.common.encrypt.core.EncryptorManager m = encryptorManagerProvider.getIfAvailable();
        if (m == null) {
            // 加密组件未启用（mybatis-encryptor.enable=false）——安全字段拒绝明文落库，fail-loud
            throw new ServiceException("加密组件未启用（mybatis-encryptor.enable），无法保存请求头");
        }
        return m.encrypt(plainJson, headersEncryptContext());
    }

    /** headers 显式解密：ENC_ 密文 → 明文 JSON；历史明文/空原样返回（幂等兼容） */
    String decryptHeaders(String storedJson) {
        if (storedJson == null || !storedJson.startsWith("ENC_")) {
            return storedJson;
        }
        org.dromara.common.encrypt.core.EncryptorManager m = encryptorManagerProvider.getIfAvailable();
        return m == null ? storedJson : m.decrypt(storedJson, headersEncryptContext());
    }

    /** G4-#1：回显键名（逗连）——解密后取；管理面列表唯一出口（值永不出库到前端）。
     *  G4 三审回修（dsflash P2-5）：单行坏密文不得 500 整个列表——解密失败降级
     *  "(解析失败)"，管理面仍可看到该行并修复 */
    public String headerKeysOf(DcsMcpServer row) {
        String json;
        try {
            json = decryptHeaders(row.getHeaders());
        } catch (Exception e) {
            log.warn("[McpPilot] headers 解密失败（键名降级）：server={}, err={}",
                row.getServerName(), e.getMessage());
            return "(解析失败)";
        }
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            java.util.Map<String, Object> map = JSON_MAPPER.readValue(json, java.util.Map.class);
            return map == null ? "" : String.join(",", map.keySet());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "(解析失败)";
        }
    }

    private static org.dromara.common.encrypt.core.EncryptContext headersEncryptContext() {
        org.dromara.common.encrypt.core.EncryptContext c = new org.dromara.common.encrypt.core.EncryptContext();
        c.setAlgorithm(org.dromara.common.encrypt.enumd.AlgorithmType.AES);
        c.setPassword(HEADERS_ENC_KEY);
        c.setEncode(org.dromara.common.encrypt.enumd.EncodeType.BASE64);
        return c;
    }

    /** headers JSON → 不可变 Map（HTTP 行挂载时喂 McpClientBuilder.headers）；
     *  校验：整体 ≤4KB、JSON 对象、无重复键、≤10 键、键 1-64 位字母数字-_、
     *  值非空字符串 ≤1024 字符（G4 三审回修：补整体上限防 Jackson 全量解析面、
     *  重复键显式拒绝防"后值静默覆盖"歧义） */
    @SuppressWarnings("unchecked")
    public static java.util.Map<String, String> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank() || headersJson.trim().equals("{}")) {
            return java.util.Map.of();
        }
        if (headersJson.length() > 4096) {
            throw new ServiceException("headers JSON 整体过长（≤4KB）");
        }
        java.util.Map<String, Object> raw;
        try {
            // 重复键显式拒绝：默认 Jackson 后值静默覆盖——管理员改配置看不出哪个键被吞
            JSON_MAPPER.reader()
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .readTree(headersJson);
            raw = JSON_MAPPER.readValue(headersJson, java.util.Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException("headers 须是无重复键的 {\"键\":\"值\"} JSON 对象");
        }
        if (raw == null || raw.isEmpty()) {
            return java.util.Map.of();
        }
        if (raw.size() > 10) {
            throw new ServiceException("headers 最多 10 个键");
        }
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Object> e : raw.entrySet()) {
            String k = e.getKey();
            if (k == null || k.isBlank() || k.length() > 64 || !k.matches("[A-Za-z0-9_-]+")) {
                throw new ServiceException("header 键名须为 1-64 位字母数字-_（收到：" + k + "）");
            }
            Object v = e.getValue();
            if (!(v instanceof String s) || s.isBlank()) {
                throw new ServiceException("header 值须为非空字符串（键：" + k + "）");
            }
            if (s.length() > 1024) {
                throw new ServiceException("header 值过长（≤1024 字符，键：" + k + "）");
            }
            out.put(k, s);
        }
        return java.util.Collections.unmodifiableMap(out);
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
     * 把平台级启用的 MCP 服务挂进助手工具面（AgentFactory 在平台/业务工具之后调用）。
     * 单服务失败=审计 MOUNT_ERROR + warn 后继续——服务下线绝不连坐助手会话。
     *
     * @return 本次实际新增的工具名（并入注册面返回值，供调用方统计/日志）
     */
    public Set<String> attachEnabled(Toolkit toolkit, String tenantId, Long userId, String aguiThreadId,
                                     io.agentscope.core.permission.PermissionContextState.Builder pb,
                                     java.util.Map<String, String> toolGate, int roleLevel) {
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
            if ("admin".equals(g) && roleLevel < org.dromara.assistant.tools.ToolIdentity.LEVEL_ADMIN) {
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
                    builder.stdioTransport(row.getCommand(), parseArgs(row.getArgs()), Map.of())
                        .syncElicitation(req -> elicitationBridge.handle(
                            row.getServerName(), tenantId, userId, aguiThreadId, req));
                } else if ("STREAMABLE_HTTP".equals(row.getTransportType())) {
                    // G4-#1：HTTP 行带鉴权头——落库密文先解密再喂 builder（2.0.4 javap 实证 headers(Map)）
                    builder.headers(parseHeaders(decryptHeaders(row.getHeaders())));
                    builder.streamableHttpTransport(row.getUrl());
                } else {
                    builder.headers(parseHeaders(decryptHeaders(row.getHeaders())));
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
                boolean serverReadOnly = !"0".equals(row.getReadOnly());
                for (io.modelcontextprotocol.spec.McpSchema.Tool t : tools == null ? List.<io.modelcontextprotocol.spec.McpSchema.Tool>of() : tools) {
                    if (before.contains(t.name())) {
                        throw new ServiceException("MCP 工具名与现有工具冲突（" + t.name() + "），已拒绝挂载避免静默覆盖");
                    }
                    Boolean hint = t.annotations() == null ? null : t.annotations().readOnlyHint();
                    if (isWriteTool(hint, serverReadOnly)) {
                        writeTools.add(t.name());
                    }
                }
                GatedMcpClient gated = new GatedMcpClient(raw, tenantId, userId, row.getServerName(),
                    callLogMapper, writeTools);
                // 装饰层：调用级限频 + 双态审计（工具列表/初始化直接委托）
                toolkit.registerMcpClient(gated).block(Duration.ofMillis(Math.max(remainMs, 1_000)));
                int mounted = 0;
                for (String name : toolkit.getToolNames()) {
                    if (!before.contains(name)) {
                        // G4-#3：MCP 工具不再无条件 ALLOW——写类（注解 FALSE 或写类服务无注解）
                        // 进 ASK 确认卡，堵「放开 read_only 后写类 MCP 绕确认闸」的 G1 同款事故面
                        boolean write = writeTools.contains(name);
                        if (write) {
                            pb.addAskRule(name, new PermissionRule(name, "", PermissionBehavior.ASK, "dcs-assistant-mcp"));
                        } else {
                            pb.addAllowRule(name, new PermissionRule(name, "", PermissionBehavior.ALLOW, "dcs-assistant-mcp"));
                        }
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
                DcsMcpCallLog l = new DcsMcpCallLog();
                l.setTenantId(tenantId);
                l.setUserId(userId);
                l.setServerName(serverName);
                l.setToolName(toolName);
                l.setAction(action);
                l.setStatus(status);
                l.setErrorMsg(errorMsg != null ? errorMsg : remark);
                // G4 三审回修（双席共识 P1）：remark 可能带 write 前缀（"write 123ms"）——
                // 原 endsWith("ms")+replace 解析必抛 NumberFormatException 且被吞 →
                // 写类成功调用审计行 100% 丢失；改尾部数字提取，前缀天然兼容
                java.util.regex.Matcher dm = java.util.regex.Pattern.compile("(\\d+)ms$")
                    .matcher(remark == null ? "" : remark);
                l.setDurationMs(dm.find() ? Integer.valueOf(dm.group(1)) : null);
                l.setCreateTime(new java.util.Date());
                mapper.insert(l);
            } catch (Exception e) {
                log.warn("[McpPilot] 调用审计写入失败（不连坐）：{}", e.getMessage());
            }
        }
    }

    /* 供 controller 分页审计用 */
}

