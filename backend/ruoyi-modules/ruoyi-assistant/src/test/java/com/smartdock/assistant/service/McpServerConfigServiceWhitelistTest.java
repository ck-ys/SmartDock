package com.smartdock.assistant.service;

import com.smartdock.assistant.domain.model.DcsMcpServer;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 搬入新根后的白名单守卫护栏（批 5 题 2 案 1，2026-11-19）。
 *
 * <p>normalizeAndValidate 从 legacy 的 McpPilotService 原样搬到本类，是全系统最重的一道输入闸
 * （命令白名单 / 脚本目录 canonical 比对 / host 白名单 / 请求头形状）。这里把每条拒绝分支钉死，
 * 防「搬家时悄悄改宽」——拆类前它没有任何自动化测试（多审席 2/席 4 同指该缺口）。</p>
 *
 * <p>纯内存：mapper 与加密件传 null（本方法不碰库、不加密），不起 Spring、不连库。</p>
 */
@Tag("local")
class McpServerConfigServiceWhitelistTest {

    private McpServerConfigService svc;

    @BeforeEach
    void setUp() {
        svc = new McpServerConfigService(null, null);
        ReflectionTestUtils.setField(svc, "allowedDirs", "");
        ReflectionTestUtils.setField(svc, "allowedHttpHosts", "");
    }

    private static DcsMcpServer stdio(String command, String argsJson) {
        DcsMcpServer r = new DcsMcpServer();
        r.setServerName("demo_stdio");
        r.setTransportType("STDIO");
        r.setCommand(command);
        r.setArgs(argsJson);
        return r;
    }

    private static DcsMcpServer http(String url) {
        DcsMcpServer r = new DcsMcpServer();
        r.setServerName("demo_http");
        r.setTransportType("STREAMABLE_HTTP");
        r.setUrl(url);
        return r;
    }

    @Test
    void stdioOnlyAcceptsNode() {
        assertThatThrownBy(() -> svc.normalizeAndValidate(stdio("sh", "[]")))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("只允许 node");
        // 带路径的命令也拒绝（白名单是精确匹配，不做 basename 归一）
        assertThatThrownBy(() -> svc.normalizeAndValidate(stdio("/usr/bin/node", "[]")))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("只允许 node");
    }

    @Test
    void stdioRejectedWhenAllowedDirsEmpty() {
        assertThatThrownBy(() -> svc.normalizeAndValidate(stdio("node", "[]")))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("未配置脚本允许目录");
    }

    @Test
    void stdioScriptMustSitInsideAllowedDirs() throws Exception {
        // 用本模块 pom.xml 当"脚本"：真实存在的路径，不必在仓外造文件
        Path pom = Path.of("pom.xml").toRealPath();
        assertThat(Files.isRegularFile(pom)).as("surefire 工作目录应是模块根").isTrue();
        String pomArg = pom.toString().replace('\\', '/');

        ReflectionTestUtils.setField(svc, "allowedDirs", pom.getParent().toString());
        assertThatCode(() -> svc.normalizeAndValidate(stdio("node", "[\"" + pomArg + "\"]")))
            .doesNotThrowAnyException();

        // 白名单换成 target 目录 → 同一个脚本必须在白名单外（Path.startsWith 按路径段比对）
        Path target = pom.getParent().resolve("target");
        Files.createDirectories(target);
        ReflectionTestUtils.setField(svc, "allowedDirs", target.toString());
        assertThatThrownBy(() -> svc.normalizeAndValidate(stdio("node", "[\"" + pomArg + "\"]")))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("不在允许目录内");
    }

    @Test
    void httpSchemeAndCredentialsRejected() {
        assertThatThrownBy(() -> svc.normalizeAndValidate(http("ftp://localhost/x")))
            .hasMessageContaining("只支持 http/https");
        assertThatThrownBy(() -> svc.normalizeAndValidate(http("http://user:pass@localhost/x")))
            .hasMessageContaining("不得携带账号信息");
        assertThatThrownBy(() -> svc.normalizeAndValidate(http("http://localhost/x?token=abc")))
            .hasMessageContaining("不得携带账号信息");
        // 大小写不敏感（实现先 toLowerCase 再匹配）
        assertThatThrownBy(() -> svc.normalizeAndValidate(http("http://localhost/x?Api-Key=abc")))
            .hasMessageContaining("不得携带账号信息");
    }

    @Test
    void httpHostWhitelistDefaultsToLocalAndCanBeExtended() {
        assertThatThrownBy(() -> svc.normalizeAndValidate(http("https://evil.example.com/sse")))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("host 不在白名单内");

        assertThatCode(() -> svc.normalizeAndValidate(http("http://127.0.0.1:19999/sse")))
            .doesNotThrowAnyException();

        // 追加 host：逗号分隔串带空格也要能用（splitCsv 负责 trim）
        ReflectionTestUtils.setField(svc, "allowedHttpHosts", " mcp.internal , 10.0.0.9 ");
        assertThatCode(() -> svc.normalizeAndValidate(http("https://mcp.internal/sse")))
            .doesNotThrowAnyException();
    }

    @Test
    void unknownTransportRejected() {
        DcsMcpServer r = http("http://localhost/x");
        r.setTransportType("WEBSOCKET");
        assertThatThrownBy(() -> svc.normalizeAndValidate(r))
            .hasMessageContaining("传输类型只支持");
    }

    @Test
    void headersValidatedAndRejectedOnStdio() {
        DcsMcpServer badHeader = http("http://localhost/x");
        badHeader.setHeaders("{\"a b\":\"1\"}");
        assertThatThrownBy(() -> svc.normalizeAndValidate(badHeader))
            .hasMessageContaining("header 键名");

        // STDIO 分支里 headers 校验在目录校验之后——先把目录配好，确保测的是 headers 规则本身
        ReflectionTestUtils.setField(svc, "allowedDirs", Path.of(".").toAbsolutePath().toString());
        DcsMcpServer stdioWithHeader = stdio("node", "[]");
        stdioWithHeader.setHeaders("{\"X-A\":\"1\"}");
        assertThatThrownBy(() -> svc.normalizeAndValidate(stdioWithHeader))
            .hasMessageContaining("STDIO 行不支持请求头");
    }

    @Test
    void defaultsAndOppositeTransportFieldsCleared() {
        // HTTP 行：清掉 command/args（防脏数据带进挂载侧），enabled 缺省 0
        DcsMcpServer r = http("http://localhost:19999/sse");
        r.setCommand("node");
        r.setArgs("[\"x\"]");
        svc.normalizeAndValidate(r);
        assertThat(r.getEnabled()).as("enabled 缺省应为 0").isEqualTo("0");
        assertThat(r.getCommand()).isNull();
        assertThat(r.getArgs()).isNull();

        // STDIO 行：清掉 url；显式 enabled 保持不动
        ReflectionTestUtils.setField(svc, "allowedDirs", Path.of(".").toAbsolutePath().toString());
        DcsMcpServer s = stdio("node", "[]");
        s.setUrl("http://localhost/x");
        s.setEnabled("1");
        svc.normalizeAndValidate(s);
        assertThat(s.getUrl()).isNull();
        assertThat(s.getEnabled()).isEqualTo("1");
    }
}
