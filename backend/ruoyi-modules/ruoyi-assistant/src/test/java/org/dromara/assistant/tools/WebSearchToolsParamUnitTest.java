package org.dromara.assistant.tools;

import org.dromara.assistant.limiter.AssistantWebSearchQuota;
import org.dromara.assistant.security.AssistantPiiFilter;
import org.dromara.assistant.service.AssistantUserQuotaService;
import org.dromara.common.core.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import cn.hutool.extra.spring.SpringUtil;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务批3-④（#17，2026-10-12）单元测试：web_search 站点范围参数校验、上游人话映射、
 * 计费配额闸（超限不计数+人话 LIMIT 转告）。HTTP 出网路径由 E2E 覆盖，单测不 mock 网络。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class WebSearchToolsParamUnitTest {

    private static final String TENANT = "000000";
    private static final Long USER = 9100000002L;

    @Mock
    private ConfigService configService;
    @Mock
    private AssistantPiiFilter piiFilter;
    @Mock
    private AssistantWebSearchQuota webSearchQuota;
    @Mock
    private AssistantUserQuotaService quotaService;
    @Mock
    private ToolIdentity identity;

    private WebSearchTools tools;
    /** 存量红根因（2026-10-14 XML 报告实证）：webSearch 读 key 走 TenantHelper.dynamic →
     * isEnable() → SpringUtils.getProperty——getProperty 是 hutool SpringUtil 的继承静态
     * （子类不重写），无 Spring 上下文时 NPE 被兜底 catch 吞成「搜索服务暂时不可用」。
     * 桩必须打在声明类 SpringUtil 上（子类静态不可拦截）；默认回落 null →
     * tenant.enable=false → dynamic 的 set/clear 双双短路走纯本地分支 */
    private MockedStatic<SpringUtil> springMock;

    @BeforeEach
    void stubCommon() {
        springMock = mockStatic(SpringUtil.class);
        tools = new WebSearchTools(identity, configService, piiFilter, webSearchQuota, quotaService);
        // 工具身份（隔离锚点）+ 有效博查 key（sk- 前缀过 DISABLED 检查）；脱敏原样返回；限额=单人覆盖 20
        lenient().when(identity.getTenantId()).thenReturn(TENANT);
        lenient().when(identity.getUserId()).thenReturn(USER);
        lenient().when(configService.getConfigValue(anyString())).thenReturn("sk-test-key");
        lenient().when(piiFilter.mask(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        // 业务批7 Q1：限额源切计费感知视图（订阅租户联网=套餐档位）
        lenient().when(quotaService.billingAwareLimits(anyString(), anyLong()))
            .thenReturn(new AssistantUserQuotaService.BillingAwareLimits(50, 20, false, false, null));
    }

    @AfterEach
    void closeSpringMock() {
        springMock.close();
    }

    private String call(String query, String include, String exclude) {
        return tools.webSearch(query, 5, null, include, exclude);
    }

    @Test
    void 站点列表解析_合法管道域名_忽略大小写与空段() {
        List<String> sites = WebSearchTools.parseSites(" A.com | b.com || c.d.cn ");
        assertThat(sites).containsExactly("a.com", "b.com", "c.d.cn");
        assertThat(WebSearchTools.parseSites(null)).isEmpty();
        assertThat(WebSearchTools.parseSites("  ")).isEmpty();
    }

    @Test
    void 站点列表解析_非法形态全拒() {
        assertThat(WebSearchTools.parseSites("a")).isNull(); // 无点分尾段
        assertThat(WebSearchTools.parseSites("https://a.com")).isNull(); // 带 scheme
        assertThat(WebSearchTools.parseSites("a.com/path")).isNull(); // 带路径
        assertThat(WebSearchTools.parseSites("a.com|bad name")).isNull(); // 含空格 token
        assertThat(WebSearchTools.parseSites("a..com")).isNull();
        assertThat(WebSearchTools.parseSites("-a.com")).isNull();
        assertThat(WebSearchTools.parseSites("a.1x")).isNull(); // 尾段须纯字母≥2
        // 超 100 个域名拒绝
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= 100; i++) {
            if (i > 0) {
                many.append('|');
            }
            many.append("s").append(i).append(".com");
        }
        assertThat(WebSearchTools.parseSites(many.toString())).isNull();
    }

    @Test
    void 站点匹配_include子域同算_exclude优先剔除() {
        List<String> inc = List.of("edu.cn");
        List<String> exc = List.of("spam.com");
        assertThat(WebSearchTools.siteAllowed("https://www.ncst.edu.cn/a", inc, List.of())).isTrue();
        assertThat(WebSearchTools.siteAllowed("https://edu.cn/", inc, List.of())).isTrue();
        assertThat(WebSearchTools.siteAllowed("https://notedu.cn/", inc, List.of())).isFalse(); // 非子域后缀
        assertThat(WebSearchTools.siteAllowed("https://x.fakedu.cn/", inc, List.of())).isFalse();
        assertThat(WebSearchTools.siteAllowed("https://a.spam.com/x", List.of(), exc)).isFalse();
        assertThat(WebSearchTools.siteAllowed("https://ok.com/x", List.of(), exc)).isTrue();
        // 坏 url：无 include 约束时保留（宁多不少），有 include 时无法证明即剔除
        assertThat(WebSearchTools.siteAllowed("not-a-url", List.of(), exc)).isTrue();
        assertThat(WebSearchTools.siteAllowed("not-a-url", inc, List.of())).isFalse();
    }

    @Test
    void 上游人话映射_401_403_410_429_默认() {
        assertThat(WebSearchTools.describeHttpFailure(401)).contains("密钥无效").contains("不要重试");
        assertThat(WebSearchTools.describeHttpFailure(403)).contains("权限不足");
        assertThat(WebSearchTools.describeHttpFailure(410)).contains("已下线");
        assertThat(WebSearchTools.describeHttpFailure(429)).contains("稍等");
        assertThat(WebSearchTools.describeHttpFailure(503)).contains("HTTP 503");
    }

    @Test
    void 配额超限_返回LIMIT人话_含恢复时点与管理端指引() {
        when(webSearchQuota.trySearch(anyString(), anyLong(), anyInt()))
            .thenReturn(AssistantWebSearchQuota.Gate.DENIED);
        String json = call("今天北京天气", null, null);
        assertThat(json).contains("\"status\":\"LIMIT\"");
        assertThat(json).contains("今日联网搜索次数已用完");
        assertThat(json).contains("每人每天 20 次");
        assertThat(json).contains("明日 0 点自动恢复");
        assertThat(json).contains("用户额度");
    }

    @Test
    void 额度为零_返回DISABLED_不触计数也不承诺明日恢复() {
        // dspro P2-2：0 的语义=「未开放」而非「当日用完」——须区别于 LIMIT 文案
        when(quotaService.billingAwareLimits(anyString(), anyLong()))
            .thenReturn(new AssistantUserQuotaService.BillingAwareLimits(50, 0, false, false, null));
        String json = call("今天北京天气", null, null);
        assertThat(json).contains("\"status\":\"DISABLED\"");
        assertThat(json).contains("未开放");
        assertThat(json).doesNotContain("明日 0 点自动恢复");
        // 零额度在闸前短路——不触 Redis 计数也不出网
        verify(webSearchQuota, org.mockito.Mockito.never()).trySearch(anyString(), anyLong(), anyInt());
        verify(webSearchQuota, org.mockito.Mockito.never()).refundSearch(anyString(), anyLong());
    }

    @Test
    void include非法_不过配额闸直接BAD_REQUEST() {
        // 语法非法在 trySearch 之前短路——不消耗额度也不出网
        String json = call("天气", "https://bad.com", null);
        assertThat(json).contains("\"status\":\"BAD_REQUEST\"");
        verify(webSearchQuota, org.mockito.Mockito.never()).trySearch(anyString(), anyLong(), anyInt());
    }

    @Test
    void 搜索词超长_BAD_REQUEST不过闸() {
        String json = call("长".repeat(101), null, null);
        assertThat(json).contains("\"status\":\"BAD_REQUEST\"");
        verify(webSearchQuota, org.mockito.Mockito.never()).trySearch(anyString(), anyLong(), anyInt());
    }
}
