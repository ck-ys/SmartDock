package org.dromara.test.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.hutool.core.lang.Dict;
import org.dromara.assistant.security.AssistantPiiFilter;
import org.dromara.assistant.tools.ToolIdentity;
import org.dromara.assistant.tools.WebSearchTools;

import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.core.service.ConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * web_search 工具单测（2026-10-06 博查接入）。
 *
 * <p>纯单测不打外网：①key 未配置→DISABLED（模型转告未启用，不自炸）；
 * ②空/超长搜索词→BAD_REQUEST。③真实搜索（含脱敏出网）由批F E2E 实弹覆盖。</p>
 */
@Tag("dev")
class WebSearchToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebSearchTools tools(String key) {
        // ConfigService 多个抽象方法，lambda 不可用——匿名类其余方法返回 null（本工具只触达 getConfigValue）
        ConfigService cfg = new ConfigService() {
            @Override
            public String getConfigValue(String configKey) {
                return key;
            }

            @Override
            public Dict getConfigMap(String configKey) {
                return null;
            }

            @Override
            public List<Dict> getConfigArrayMap(String configKey) {
                return null;
            }

            @Override
            public <T> T getConfigObject(String configKey, Class<T> clazz) {
                return null;
            }

            @Override
            public <T> List<T> getConfigArray(String configKey, Class<T> clazz) {
                return null;
            }
        };
        // 批④(2026-10-12)签名扩容：+联网配额 limiter（DISABLED/BAD_REQUEST 前置短路不触达，
        // 传 new 空实例保真实行为）+限额源 service（同路径不触达，null 即可）
        return new WebSearchTools(ToolIdentity.from(user()), cfg, filter(),
            new org.dromara.assistant.limiter.AssistantWebSearchQuota(), null);
    }

    /** PiiFilter 仅 DISABLED/BAD_REQUEST 路径不触达 mask——JdbcTemplate 传 null 即可 */
    private AssistantPiiFilter filter() {
        return new AssistantPiiFilter(null);
    }

    private LoginUser user() {
        LoginUser u = new LoginUser();
        u.setTenantId("000000");
        u.setUserId(1L);
        u.setUserType("00");
        u.setDeptId(0L);
        u.setUsername("admin");
        return u;
    }

    @Test
    void blankKeyReturnsDisabled() throws Exception {
        JsonNode out = MAPPER.readTree(tools("").webSearch("天气", null, null, null, null));
        assertEquals("DISABLED", out.path("status").asText(), "空 key 应自降级 DISABLED 不抛异常");
        assertTrue(out.path("message").asText().contains("未启用"),
            "DISABLED 文案应引导模型转告用户");
    }

    @Test
    void nullKeyReturnsDisabled() throws Exception {
        JsonNode out = MAPPER.readTree(tools(null).webSearch("新闻", 3, "oneDay", null, null));
        assertEquals("DISABLED", out.path("status").asText());
    }

    @Test
    void blankQueryRejected() throws Exception {
        JsonNode out = MAPPER.readTree(tools("sk-fake").webSearch("   ", null, null, null, null));
        assertEquals("BAD_REQUEST", out.path("status").asText(), "空搜索词应拒");
    }

    @Test
    void overlongQueryRejected() throws Exception {
        JsonNode out = MAPPER.readTree(tools("sk-fake").webSearch("字".repeat(101), null, null, null, null));
        assertEquals("BAD_REQUEST", out.path("status").asText(), "超 100 字搜索词应拒");
    }
}
