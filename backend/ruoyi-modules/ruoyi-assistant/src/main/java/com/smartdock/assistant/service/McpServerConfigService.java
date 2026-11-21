package com.smartdock.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.assistant.domain.model.McpServer;
import com.smartdock.assistant.mapper.McpServerMapper;
import com.smartdock.assistant.support.McpHeaderCipher;
import com.smartdock.assistant.support.McpToolFacts;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MCP 服务注册表的管理面（批 5 题 2 案 1，2026-11-19：从 legacy 的 McpPilotService 拆出）。
 *
 * <p>管的是「配置」：新增/编辑/启停/删除 + 清单与名单查询。运行面（建会话把外部服务
 * 挂进工具面、调用审计、限频、清理 job）留在原类，两侧不共用状态。</p>
 *
 * <p>白名单校验全在这里（命令 / 脚本目录 / host / 请求头），controller 只管鉴权与审计。
 * 两个 {@code @Value} 是本类自己声明、自己使用——拆类时漏搬会让 STDIO 保存全线报错
 * （allowed-dirs 为空）或 host 白名单静默变窄（只剩默认本机）。</p>
 *
 * <p>注：运行面读「已启用的服务行」（listEnabledForPlatform）刻意留在原类——挂载热路径
 * 只依赖注册表的只读查询，不反向依赖本管理服务；本类只管管理面写路径与清单。</p>
 */
@Service
@RequiredArgsConstructor
public class McpServerConfigService {

    /** 试点=平台级共享（预留租户级） */
    public static final String PLATFORM_TENANT = "000000";

    private static final Set<String> ALLOWED_COMMANDS = Set.of("node", "node.exe");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,29}$");

    /** STDIO 脚本允许目录（绝对路径，逗号分隔；空=禁用 STDIO 注册） */
    @Value("${smartdock.mcp.allowed-dirs:}")
    private String allowedDirs;

    /** HTTP 传输 host 白名单（逗号分隔；默认只放行本机） */
    @Value("${smartdock.mcp.allowed-http-hosts:localhost,127.0.0.1}")
    private String allowedHttpHosts;

    private final McpServerMapper serverMapper;
    private final McpHeaderCipher headerCipher;

    /** 管理清单：全部服务行（按更新时间倒序） */
    public List<McpServer> listAll() {
        return serverMapper.selectList(new LambdaQueryWrapper<McpServer>()
            .orderByDesc(McpServer::getUpdateTime));
    }

    /** G4-#4：全部服务名（工具闸 mcp: 键校验用；平台级表，跨租户读） */
    public Set<String> allServerNames() {
        return TenantHelper.ignore(() -> serverMapper.selectList(
                new LambdaQueryWrapper<McpServer>()
                    .select(McpServer::getServerName))
            .stream().map(McpServer::getServerName).collect(Collectors.toSet()));
    }

    /** 回显键名（解密后取，值永不出库到前端）——实现收在加密件 */
    public String headerKeysOf(McpServer row) {
        return headerCipher.headerKeysOf(row);
    }

    /** 新增/编辑：白名单校验（命令/脚本目录/host/请求头）全在这——controller 只管鉴权与审计 */
    public void save(McpServer row) {
        if (row.getServerName() == null || !NAME_PATTERN.matcher(row.getServerName()).matches()) {
            throw new ServiceException("服务名限 3-30 位小写字母/数字/下划线，且以字母开头");
        }
        normalizeAndValidate(row);
        Date now = new Date();
        if (row.getId() == null) {
            row.setTenantId(PLATFORM_TENANT);
            row.setReadOnly("1");
            row.setCreateTime(now);
            row.setUpdateTime(now);
            // G4-#1：headers 明文 → ENC_ 密文落库（显式加密，不赌拦截器装配）
            row.setHeaders(headerCipher.encrypt(row.getHeaders()));
            try {
                serverMapper.insert(row);
            } catch (DuplicateKeyException e) {
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
                row.setHeaders(headerCipher.encrypt(row.getHeaders()));
            }
            serverMapper.updateById(row);
        }
    }

    public void toggle(Long id, String enabled) {
        if (!"0".equals(enabled) && !"1".equals(enabled)) {
            throw new ServiceException("enabled 只支持 0/1");
        }
        McpServer patch = new McpServer();
        patch.setId(id);
        patch.setEnabled(enabled);
        patch.setUpdateTime(new Date());
        if (serverMapper.updateById(patch) == 0) {
            throw new ServiceException("记录不存在或已删除");
        }
    }

    /** 试点物理删（审计表留痕），无外键依赖 */
    public void delete(Long id) {
        serverMapper.deleteById(id);
    }

    /** 白名单校验单点（只被 save 调用） */
    void normalizeAndValidate(McpServer row) {
        String t = row.getTransportType();
        if ("STDIO".equals(t)) {
            if (!ALLOWED_COMMANDS.contains(row.getCommand() == null ? "" : row.getCommand())) {
                throw new ServiceException("STDIO 试点只允许 node 命令（要接其它运行时请在白名单配置中评估后开放）");
            }
            List<String> args = McpToolFacts.parseArgs(row.getArgs());
            List<String> dirs = McpToolFacts.splitCsv(allowedDirs);
            if (dirs.isEmpty()) {
                throw new ServiceException("STDIO 未配置脚本允许目录（smartdock.mcp.allowed-dirs），暂不可注册");
            }
            for (String a : args) {
                if (a == null || a.isBlank()) {
                    throw new ServiceException("args 须是非空字符串数组（元素 " + a + "）");
                }
                Path canonical;
                try {
                    canonical = Path.of(a).toRealPath();
                } catch (IOException e) {
                    throw new ServiceException("脚本路径不存在：" + a);
                } catch (InvalidPathException e) {
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
                    } catch (IOException e) {
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
            hosts.addAll(McpToolFacts.splitCsv(allowedHttpHosts));
            if (uri.getHost() == null || !hosts.contains(uri.getHost().toLowerCase())) {
                throw new ServiceException("服务地址 host 不在白名单内（默认仅本机；可在 smartdock.mcp.allowed-http-hosts 追加）");
            }
            row.setCommand(null);
            row.setArgs(null);
        } else {
            throw new ServiceException("传输类型只支持 STDIO / STREAMABLE_HTTP / SSE");
        }
        McpToolFacts.validateHeaders(row.getHeaders());
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
}
