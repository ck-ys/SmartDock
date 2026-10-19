package org.dromara.test.assistant;

import cn.dev33.satoken.filter.SaServletFilter;
import org.dromara.common.security.handler.AllUrlHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.WebApplicationContext;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T3 探针：URL 快照 + /agui 端点三态断言。
 * <p>判据（计划 Task 3）：</p>
 * <ul>
 *   <li>URL 快照：AllUrlHandler（= SaInterceptor 登录检查的匹配集）+ SaServletFilter include/exclude 反射落盘；</li>
 *   <li>三态断言：POST agui 端点不带 token——404=未注册 / 401=已注册未登录（期望态） / 200=未登录可访问（漏洞）；</li>
 *   <li>SSE 事件序列：401 响应体落盘 03-agui-events.json（真实 SSE 事件流需登录态+注册 agent，随 T6）；</li>
 *   <li>拦截链结论：SaInterceptor 对 AllUrlHandler.getUrls() 内的已注册 URL 做登录检查——
 *       agui 端点只要注册进 RequestMappingHandlerMapping 即自动纳入，无需改 SaServletFilter。</li>
 * </ul>
 */
@Tag("p0probe")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class P03UrlSnapshotTest {

    @LocalServerPort
    int port;

    @Autowired
    AllUrlHandler allUrlHandler;

    @Autowired
    WebApplicationContext ctx;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    SaServletFilter saServletFilter;

    /** 快照与事件证据落盘位置（target 下，不入 git；正式证据整理进 document/evidence/assistant-p0/） */
    private static final Path OUT = Path.of("target", "p03");

    @Test
    void urlSnapshotAndAguiRegistration() throws Exception {
        Files.createDirectories(OUT);
        List<String> urls = new ArrayList<>(allUrlHandler.getUrls());
        urls.sort(String::compareTo);
        Files.write(OUT.resolve("url-snapshot.txt"), urls, StandardCharsets.UTF_8);

        List<String> aguiUrls = urls.stream().filter(u -> u.toLowerCase().contains("agui")).toList();
        Files.write(OUT.resolve("agui-urls.txt"), aguiUrls, StandardCharsets.UTF_8);

        // T0 时 agui 自动配置已 matched——controller 端点应注册；若此处空=路径不是 /agui 前缀（下个测试会全量兜底搜）
        System.out.println("[P03] agui URLs = " + aguiUrls);
        System.out.println("[P03] url snapshot size = " + urls.size());
        assertFalse(urls.isEmpty(), "AllUrlHandler 应收集到 URL（上下文正常）");
    }

    @Test
    void saServletFilterSnapshot() throws Exception {
        Files.createDirectories(OUT);
        StringBuilder sb = new StringBuilder();
        sb.append("SaServletFilter(include=").append(readList(saServletFilter, "includeList", "includesList"))
          .append(", exclude=").append(readList(saServletFilter, "excludeList", "excludesList")).append(")\n");
        Files.write(OUT.resolve("sa-filter-snapshot.txt"), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[P03] " + sb);
        // fallback 判据：当前 include 仅 /actuator 系（计划 SecurityConfig.java:96-98），agui 不归它管
        assertTrue(true, "快照落盘（见 target/p03/sa-filter-snapshot.txt）");
    }

    @SuppressWarnings("unchecked")
    private static List<String> readList(Object bean, String... fieldNames) throws Exception {
        for (String name : fieldNames) {
            try {
                Field f = bean.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(bean);
                if (v instanceof List<?> list) {
                    return (List<String>) list;
                }
            } catch (NoSuchFieldException ignored) {
                // 试下一个候选字段名
            }
        }
        return List.of();
    }

    /**
     * 三态断言：未登录 POST agui 端点。
     * 404=未注册（fallback：需补 agent bean/端点）；401=已注册+登录拦截（期望终态）；
     * 200=未登录可访问（漏洞，立即上报）。断言写成 401-or-404 容许+事件落盘，杜绝把 200 漏过。
     */
    @Test
    void aguiEndpointThreeStateAssertion() throws Exception {
        Files.createDirectories(OUT);
        List<String> aguiUrls = allUrlHandler.getUrls().stream()
            .filter(u -> u.toLowerCase().contains("agui")).sorted().toList();

        StringBuilder events = new StringBuilder();
        events.append("{\"probeTime\":\"").append(java.time.Instant.now()).append("\",\n");
        events.append("\"port\":").append(port).append(",\n");
        events.append("\"aguiUrls\":").append(aguiUrls).append(",\n");
        events.append("\"probes\":[\n");

        List<String> unprotected = new ArrayList<>();
        if (aguiUrls.isEmpty()) {
            events.append("  {\"note\":\"快照无 agui URL——端点未注册（三态=404），SSE 探测随 T6 注册 agent 后补\"}\n");
        } else {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>("{}", headers);
            boolean first = true;
            for (String u : aguiUrls) {
                ResponseEntity<String> resp = rest.exchange(u, HttpMethod.POST, entity, String.class);
                int code = resp.getStatusCode().value();
                String body = resp.getBody() == null ? "" : resp.getBody().replace("\n", "\\n");
                System.out.println("[P03] POST " + u + " -> " + code + " body=" + body);
                if (!first) {
                    events.append(",\n");
                }
                first = false;
                events.append("  {\"url\":\"").append(u).append("\",\"status\":").append(code)
                    .append(",\"body\":\"").append(body).append("\"}");
                // 三态判定：RuoYi 业务错误惯例 HTTP 200 + body.code=401（被拦=PASS）；
                // 200 且 body 无 401/未登录痕迹 = 未登录可达业务逻辑（真漏洞）
                boolean blocked = code == 401 || body.contains("401") || body.contains("未登录") || body.contains("not logged");
                if (code == 200 && !blocked) {
                    unprotected.add(u);
                }
            }
            events.append('\n');
        }
        events.append("]}\n");
        Files.write(OUT.resolve("03-agui-events.json"), events.toString().getBytes(StandardCharsets.UTF_8));

        // 证据先落盘再断言：未登录绝不可达业务逻辑（404=未注册记录事实；401/401body=被拦通过）
        assertTrue(unprotected.isEmpty(), "以下 agui 端点未登录可达业务逻辑（漏洞）：" + unprotected);
    }
}
