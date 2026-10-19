package org.dromara.test.assistant;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.bo.DcsAiConfigBo;
import com.smartdock.service.IDcsAiConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J1 单点解析器对账单测（J 批一期 2026-10-08；批3 星标化改造 2026-10-09）。
 *
 * <p>判据（拍板 confJ=A + cnyGate=A；批3 Q4:A/Q11）：
 * ①resolveEnabledConfig=★默认行优先→无星回退 update_time DESC（兜底口径钉死防回归）；
 * ②无可用行 ServiceException 人话（含用途提示）；
 * ③CNY 闸三态：CNY 行放行 / USD 行拒（话术含人民币）/ FIXED_PER_CALL 单价 0 豁免；
 * ④批3 星标语义：新增配好即用（缺省 enabled='1' 且不带星）/ 同组多可用行共存合法 /
 * activate=换星不动 enabled / 停用行点星 409 / 编辑剥离 is_default / 唯一默认索引拒双星；
 * ⑤resolveConfigById（助手默认槽供值）：可用放行 / 停用拒 / 跨租户拒。</p>
 *
 * <p>夹具走 A1 同款裸 SQL（9973xx 临时租户 + 91991x 雪花段），@AfterEach 全量回收。</p>
 */
@Tag("p0probe")
@SpringBootTest
class J1AiConfigResolverTest {

    /** 排序口径夹具租户: 两行可用均无星, 旧行 configId 更小(updateTime 更早) */
    private static final String T_ORDER = "997321";
    /** 无配置租户 */
    private static final String T_EMPTY = "997322";
    /** USD 计价夹具租户 */
    private static final String T_USD = "997323";
    /** 守卫夹具租户(每个用例自建自净, 不互相依赖) */
    private static final String T_GUARD = "997324";
    /** FIXED 免费豁免夹具租户 */
    private static final String T_FIXED = "997325";
    /** 星标优先夹具租户(批3) */
    private static final String T_STAR = "997326";

    /** 夹具雪花段上界(@AfterEach 按段整删, 不碰 A1 的 9199000x 段) */
    private static final String FIXTURE_ID_RANGE =
        " config_id BETWEEN 9199100000000001 AND 9199100000000099 ";

    @Autowired
    IDcsAiConfigService aiConfigService;
    @Autowired
    DataSource ds;

    /**
     * ①回退排序口径: 两行可用均无星(星空缺段), useType=null 全组解析取 update_time 新者
     * (若回归 configId ASC/update_time ASC 会取到旧行被断言抓住)。
     */
    @Test
    void resolvePicksNewestByUpdateTimeWhenNoStar() throws Exception {
        // 同租户每厂商仅一行活行(uk_dcs_ai_config_tenant_provider 部分唯一索引), 两行分用厂商;
        // 旧行也过闸(FIXED 免费豁免)——排序若回归会在 modelName 断言上干净暴露, 而非闸异常噪音
        exec(cfgSql(9199100000000001L, T_ORDER, "OPENCODE", "longcat-2.5-preview-free", "FIXED_PER_CALL", "0", "1", null)
            + ", '2026-01-01 00:00:00')");
        exec(cfgSql(9199100000000002L, T_ORDER, "DEEPSEEK", "deepseek-v4-pro", "OFFICIAL_TOKEN", null, "1", null)
                .replace(", 'CHAT',", ", 'EMBEDDING',")
            + ", '2026-06-01 00:00:00')");
        DcsAiConfig cfg = aiConfigService.resolveEnabledConfig(T_ORDER, null);
        assertEquals("deepseek-v4-pro", cfg.getModelName(), "无星回退必须取 update_time 最新可用行");
        assertEquals(9199100000000002L, cfg.getConfigId());
        assertEquals("EMBEDDING", cfg.getUseType());
    }

    /** ①★优先: 旧★行胜过更新的无星可用行(星标是选择器, update_time 仅兜底) */
    @Test
    void resolvePrefersStarredRowOverNewerUnstarred() throws Exception {
        // 旧 CHAT 行带星(FIXED 免费过闸); 新 CHAT 行更晚但无星——必须取星行
        exec(cfgSql(9199100000000003L, T_STAR, "OPENCODE", "longcat-2.5-preview-free", "FIXED_PER_CALL", "0", "1", "1")
            + ", '2026-01-01 00:00:00')");
        exec(cfgSql(9199100000000004L, T_STAR, "DEEPSEEK", "deepseek-v4-flash", "OFFICIAL_TOKEN", null, "1", null)
            + ", '2026-06-01 00:00:00')");
        DcsAiConfig cfg = aiConfigService.resolveEnabledConfig(T_STAR, "CHAT");
        assertEquals("longcat-2.5-preview-free", cfg.getModelName(), "★默认行必须优先于更新的无星行");
        assertEquals(9199100000000003L, cfg.getConfigId());
    }

    /** ①★优先护栏(批4 Q6/Review Focus ⑤): 停用★行被 resolver 天然跳过→回退可用行 */
    @Test
    void resolveSkipsDisabledStarAndFallsBack() throws Exception {
        // ★行停用(急刹车场景), 无星可用行兜底
        exec(cfgSql(9199100000000007L, T_STAR, "OPENCODE", "longcat-2.5-preview-free", "FIXED_PER_CALL", "0", "0", "1")
            + ", '2026-01-01 00:00:00')");
        exec(cfgSql(9199100000000008L, T_STAR, "DEEPSEEK", "deepseek-v4-flash", "OFFICIAL_TOKEN", null, "1", null)
            + ", '2026-06-01 00:00:00')");
        DcsAiConfig cfg = aiConfigService.resolveEnabledConfig(T_STAR, "CHAT");
        assertEquals("deepseek-v4-flash", cfg.getModelName(), "停用★必须被跳过, 回退可用行");
        assertEquals(9199100000000008L, cfg.getConfigId());
    }

    /** ②无可用行: ServiceException 且话术带用途提示(HTTP 调用方按此类型降级捕获) */
    @Test
    void resolveNoRowThrowsWithUseTypeHint() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> aiConfigService.resolveEnabledConfig(T_EMPTY, "CHAT"));
        assertTrue(ex.getMessage().contains("CHAT"), "话术须指明缺失的用途: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(T_EMPTY), "话术须指明租户: " + ex.getMessage());
    }

    /** ③USD 计价模型全系统统一拒(拍板 cnyGate=A 上收, 助手路/单发路同闸) */
    @Test
    void resolveRejectsUsdPricedModel() throws Exception {
        exec(cfgSql(9199100000000005L, T_USD, "OPENAI", "gpt-4.1-mini", "OFFICIAL_TOKEN", null, "1", null)
            + ", '2026-06-01 00:00:00')");
        ServiceException ex = assertThrows(ServiceException.class,
            () -> aiConfigService.resolveEnabledConfig(T_USD, "CHAT"));
        assertTrue(ex.getMessage().contains("人民币"), "话术须说明人民币限制: " + ex.getMessage());
    }

    /** ③豁免: FIXED_PER_CALL 且单价显式 0 的真免费模型不依赖价格行 */
    @Test
    void resolveAllowsFixedFreeWithoutPriceRow() throws Exception {
        exec(cfgSql(9199100000000006L, T_FIXED, "OPENCODE", "longcat-2.5-preview-free", "FIXED_PER_CALL", "0", "1", null)
            + ", '2026-06-01 00:00:00')");
        DcsAiConfig cfg = aiConfigService.resolveEnabledConfig(T_FIXED, "CHAT");
        assertEquals("FIXED_PER_CALL", cfg.getBillingMode(), "真免费豁免必须放行");
    }

    /** ④批3: 新增缺省=配好即用(enabled='1' 且不带星), 不再抢占/停用任何兄弟行 */
    @Test
    void insertDefaultsToEnabledAndKeepsSibling() throws Exception {
        exec(cfgSql(9199100000000011L, T_GUARD, "DEEPSEEK", "deepseek-v4-flash", "OFFICIAL_TOKEN", null, "1", "1")
            + ", '2026-01-01 00:00:00')");
        Long newId;
        try (var login = mockStatic(LoginHelper.class)) {
            // 批B 三审 P0：insertByBo 入口有租户守卫(同 updateByBo 口径), 测试无登录态→以超管身份放行
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            login.when(LoginHelper::isSuperAdmin).thenReturn(true);
            newId = aiConfigService.insertByBo(bo(T_GUARD, "OPENAI", "gpt-4.1-mini", null));
        }
        assertNotNull(newId);
        assertEquals("1", enabledOf(newId), "新增缺省必须可用(配好即用)");
        assertNull(starOf(newId), "新增行永不带星(换星走 activate)");
        assertEquals("1", enabledOf(9199100000000011L), "兄弟可用行不受新增影响");
        assertEquals("1", starOf(9199100000000011L), "兄弟行的★不受新增影响");
    }

    /** ④批3: 同组多可用行合法共存(旧「唯一启用」索引已删, enabled=纯状态位) */
    @Test
    void multipleEnabledRowsCoexist() throws Exception {
        exec(cfgSql(9199100000000012L, T_GUARD, "DEEPSEEK", "deepseek-v4-flash", "OFFICIAL_TOKEN", null, "1", null)
            + ", '2026-01-01 00:00:00')");
        exec(cfgSql(9199100000000013L, T_GUARD, "OPENAI", "gpt-4.1-mini", "OFFICIAL_TOKEN", null, "1", null)
            + ", '2026-06-01 00:00:00')");
        assertEquals("1", enabledOf(9199100000000012L));
        assertEquals("1", enabledOf(9199100000000013L), "同组双可用共存必须合法");
    }

    /** ④批3: 编辑置 enabled 不再守卫互斥(状态位自由翻转); is_default 传值被剥离不生效 */
    @Test
    void updateEnabledFlagDoesNotTouchSiblingsAndStripsStar() throws Exception {
        exec(cfgSql(9199100000000014L, T_GUARD, "DEEPSEEK", "deepseek-v4-flash", "OFFICIAL_TOKEN", null, "1", null)
            + ", '2026-01-01 00:00:00')");
        exec(cfgSql(9199100000000015L, T_GUARD, "OPENAI", "gpt-4.1-mini", "OFFICIAL_TOKEN", null, "0", null)
            + ", '2026-01-01 00:00:00')");
        DcsAiConfigBo patch = bo(T_GUARD, "OPENAI", "gpt-4.1-mini", "1");
        patch.setConfigId(9199100000000015L);
        patch.setIsDefault("1");
        try (var login = mockStatic(LoginHelper.class)) {
            // updateByBo 有租户访问校验(七审 P2-2), 测试无登录态→以超管身份放行
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            login.when(LoginHelper::isSuperAdmin).thenReturn(true);
            assertTrue(aiConfigService.updateByBo(patch));
        }
        assertEquals("1", enabledOf(9199100000000015L), "编辑置可用必须生效");
        assertEquals("1", enabledOf(9199100000000014L), "兄弟可用行不受编辑影响");
        assertNull(starOf(9199100000000015L), "编辑路径必须剥离 is_default(换星只走 activate)");
    }

    /**
     * ④批3 activate=换星: 摘旧星+置新星, 不动任何行的 enabled; 停用行点星 409 防呆。
     * 无登录态拒绝口径沿用 m3 P1-7(按行租户访问校验)。
     */
    @Test
    void activateSwapsStarWithoutTouchingEnabled() throws Exception {
        exec(cfgSql(9199100000000016L, T_GUARD, "DEEPSEEK", "deepseek-v4-flash", "OFFICIAL_TOKEN", null, "1", "1")
            + ", '2026-01-01 00:00:00')");
        exec(cfgSql(9199100000000017L, T_GUARD, "OPENAI", "gpt-4.1-mini", "OFFICIAL_TOKEN", null, "1", null)
            + ", '2026-06-01 00:00:00')");
        // 无登录态(非超管+无租户上下文)必须拒, 防越权换星
        ServiceException ex = assertThrows(ServiceException.class,
            () -> aiConfigService.activate(9199100000000017L));
        assertTrue(ex.getMessage().contains("租户"), "无上下文 activate 必须拒: " + ex.getMessage());
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            login.when(LoginHelper::isSuperAdmin).thenReturn(true);
            assertTrue(aiConfigService.activate(9199100000000017L));
        }
        assertEquals("1", starOf(9199100000000017L), "目标行置星");
        assertNull(starOf(9199100000000016L), "同用途旧星行摘星");
        assertEquals("1", enabledOf(9199100000000016L), "换星不动旧行 enabled(状态与默认正交)");
        assertEquals("1", enabledOf(9199100000000017L));
    }

    /** ④批3: 停用行点星=「默认指向调不动的模型」, 入口拒绝防呆(Review Focus ⑤) */
    @Test
    void activateRejectsDisabledRow() throws Exception {
        exec(cfgSql(9199100000000018L, T_GUARD, "OPENAI", "gpt-4.1-mini", "OFFICIAL_TOKEN", null, "0", null)
            + ", '2026-01-01 00:00:00')");
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getTenantId).thenReturn("000000");
            login.when(LoginHelper::isSuperAdmin).thenReturn(true);
            ServiceException ex = assertThrows(ServiceException.class,
                () -> aiConfigService.activate(9199100000000018L));
            assertTrue(ex.getMessage().contains("停用"), "停用行点星必须 409 防呆: " + ex.getMessage());
        }
    }

    /**
     * ④批3: DB 唯一默认索引——同租户同用途第二条带星行被部分唯一索引拒绝
     * (uk_dcs_ai_config_tenant_default; 竞态窗口由索引封死, 人话转译在 Service 层 catch)。
     */
    @Test
    void uniqueDefaultIndexRejectsSecondStar() throws Exception {
        exec(cfgSql(9199100000000019L, T_GUARD, "DEEPSEEK", "deepseek-v4-flash", "OFFICIAL_TOKEN", null, "1", "1")
            + ", '2026-01-01 00:00:00')");
        // 同租户同用途第二条带星行: 裸 SQL 绕过守卫直插, 必须被唯一索引拒绝
        boolean rejected = false;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO dcs_ai_config (config_id, tenant_id, provider, base_url, api_key, model_name,"
                 + " use_type, enabled, is_default, del_flag, update_time) VALUES (9199100000000020, '" + T_GUARD
                 + "', 'QWEN', 'https://api.example.com/v1', 'x', 'qwen3.7-max', 'CHAT', '1', '1', '0', CURRENT_TIMESTAMP)")) {
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            rejected = e.getMessage().contains("uk_dcs_ai_config_tenant_default");
        }
        assertTrue(rejected, "并发双星必须被部分唯一索引拒绝(uk_dcs_ai_config_tenant_default)");
    }

    /** ⑤resolveConfigById(助手默认槽供值): 可用行放行(同 CNY 闸, FIXED 免费夹具) */
    @Test
    void resolveConfigByIdAllowsUsableRow() throws Exception {
        exec(cfgSql(9199100000000021L, T_STAR, "OPENCODE", "longcat-2.5-preview-free", "FIXED_PER_CALL", "0", "1", null)
            + ", '2026-01-01 00:00:00')");
        DcsAiConfig cfg = aiConfigService.resolveConfigById(T_STAR, 9199100000000021L);
        assertEquals("longcat-2.5-preview-free", cfg.getModelName());
    }

    /** ⑤resolveConfigById: 停用行拒(助手默认指向失效场景, 工厂层据此回退) */
    @Test
    void resolveConfigByIdRejectsDisabledRow() throws Exception {
        exec(cfgSql(9199100000000022L, T_STAR, "OPENAI", "gpt-4.1-mini", "OFFICIAL_TOKEN", null, "0", null)
            + ", '2026-01-01 00:00:00')");
        ServiceException ex = assertThrows(ServiceException.class,
            () -> aiConfigService.resolveConfigById(T_STAR, 9199100000000022L));
        assertTrue(ex.getMessage().contains("停用"), "停用行按 id 解析必须拒: " + ex.getMessage());
    }

    /** ⑤resolveConfigById: 跨租户行按「不存在」拒(多租户隔离, 不泄露行存在性) */
    @Test
    void resolveConfigByIdRejectsCrossTenantRow() throws Exception {
        exec(cfgSql(9199100000000023L, T_STAR, "OPENCODE", "longcat-2.5-preview-free", "FIXED_PER_CALL", "0", "1", null)
            + ", '2026-01-01 00:00:00')");
        ServiceException ex = assertThrows(ServiceException.class,
            () -> aiConfigService.resolveConfigById(T_EMPTY, 9199100000000023L));
        assertTrue(ex.getMessage().contains("不存在"), "跨租户按 id 解析必须拒: " + ex.getMessage());
    }

    /** EMBEDDING 同闸同源: 000000 嵌入行过 CNY 闸(价格行由 migration_20261008_05 补种; 批3 migration 已带★) */
    @Test
    void embeddingPathPassesGateWithSeededCnyRow() {
        DcsAiConfig cfg = aiConfigService.resolveEnabledConfig("000000", "EMBEDDING");
        assertEquals("Qwen/Qwen3-Embedding-8B", cfg.getModelName(), "嵌入行必须过闸可用");
    }

    /** 构造合法新增 BO(normalize 会补齐缺省字段, 这里只填必填+被测字段) */
    private DcsAiConfigBo bo(String tenantId, String provider, String modelName, String enabled) {
        DcsAiConfigBo b = new DcsAiConfigBo();
        b.setTenantId(tenantId);
        b.setProvider(provider);
        b.setBaseUrl("https://api.example.com/v1");
        b.setModelName(modelName);
        b.setUseType("CHAT");
        b.setApiKey("test-key");
        b.setEnabled(enabled);
        return b;
    }

    /** 裸 SQL 插配置行(cfgSql 拼 update_time 收尾); star=null=不带星 */
    private String cfgSql(long id, String tenant, String provider, String model,
                          String billingMode, String unitPrice, String enabled, String star) {
        String price = unitPrice == null ? "NULL" : unitPrice;
        String starVal = star == null ? "NULL" : "'" + star + "'";
        return "INSERT INTO dcs_ai_config (config_id, tenant_id, provider, base_url, api_key, model_name,"
            + " use_type, enabled, is_default, del_flag, billing_mode, unit_price_per_call, update_time) VALUES ("
            + id + ", '" + tenant + "', '" + provider + "', 'https://api.example.com/v1', 'x', '" + model
            + "', 'CHAT', '" + enabled + "', " + starVal + ", '0', '" + billingMode + "', " + price;
    }

    private String enabledOf(long configId) throws Exception {
        return colOf("enabled", configId);
    }

    private String starOf(long configId) throws Exception {
        return colOf("is_default", configId);
    }

    private String colOf(String col, long configId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT " + col + " FROM dcs_ai_config WHERE config_id=" + configId);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "行必须存在: " + configId);
            return rs.getString(1);
        }
    }

    @AfterEach
    void cleanFixture() throws Exception {
        exec("DELETE FROM dcs_ai_config WHERE" + FIXTURE_ID_RANGE
            + " OR tenant_id IN ('" + T_ORDER + "','" + T_EMPTY + "','" + T_USD + "','"
            + T_GUARD + "','" + T_FIXED + "','" + T_STAR + "')");
    }

    private void exec(String sql) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
