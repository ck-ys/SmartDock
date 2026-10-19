package org.dromara.test.assistant;

import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.dromara.assistant.factory.AssistantModelFactory;
import com.smartdock.ai.AiNotConfiguredException;
import com.smartdock.domain.DcsAiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 模型工厂单测（批A Task A1，P1 计划 2026-10-03）。
 *
 * <p>判据：①测试租户 978311 解析到收敛后唯一启用行（J 批一期起 OPENCODE longcat）且字段可取；
 * ②按配置构建出 OpenAI 兼容流式模型且型号透传；③无配置租户抛业务异常（禁静默回退）。</p>
 */
@Tag("p0probe")
@SpringBootTest
class A1ModelFactoryTest {

    @Autowired
    AssistantModelFactory modelFactory;
    @Autowired
    DataSource ds;

    /** 临时租户: 启用 USD 计价模型 gpt-4.1-mini, 验证人民币闸(拍板②) */
    private static final String NON_CNY_TENANT = "997311";

    /** 临时租户: FIXED_PER_CALL 无价格行, 验证闸豁免(2026-10-08 OPENCODE 接入, 三审 P2-3) */
    private static final String FIXED_PER_CALL_TENANT = "997312";

    @Test
    void resolveConfigByTenant() {
        DcsAiConfig cfg = modelFactory.resolveConfig("978311");
        assertNotNull(cfg, "978311 应有启用的助手配置行");
        // J 批一期(2026-10-08): 解析统一单点解析器 updateTime DESC + 收敛 migration 后
        // 978311 唯一启用行 = OPENCODE longcat(旧断言"configId 升序第一行 deepseek"口径已废)
        assertEquals("longcat-2.5-preview-free", cfg.getModelName());
        assertEquals("OPENCODE", cfg.getProvider());
        assertEquals("1", cfg.getEnabled());
    }

    @Test
    void buildModelPassesModelNameAndStream() {
        DcsAiConfig cfg = modelFactory.resolveConfig("978311");
        OpenAIChatModel model = assertInstanceOf(OpenAIChatModel.class, modelFactory.buildModel(cfg));
        assertEquals("longcat-2.5-preview-free", model.getModelName(), "型号必须透传到模型实例");
    }

    @Test
    void tenantWithoutConfigThrows() {
        assertThrows(AiNotConfiguredException.class, () -> modelFactory.resolveConfig("999999"),
            "无配置租户必须抛 AiNotConfiguredException，禁静默回退平台侧配置");
    }

    /**
     * 人民币闸(用户拍板 2026-09-30 ②): 钱包仅人民币计费, 美元计价模型禁止用于助手。
     * 夹具: 临时租户 997311 启用 gpt-4.1-mini(平台目录 USD 价行), resolveConfig 必须拒绝且话术含"人民币"。
     */
    @Test
    void nonCnyModelRejectedForAssistant() throws Exception {
        exec("INSERT INTO dcs_ai_config (config_id, tenant_id, provider, base_url, api_key, model_name, use_type, enabled, del_flag)"
            + " VALUES (9199000000000001, '" + NON_CNY_TENANT + "', 'OPENAI', 'https://api.openai.com/v1', 'x',"
            + " 'gpt-4.1-mini', 'CHAT', '1', '0')");
        AiNotConfiguredException ex = assertThrows(AiNotConfiguredException.class,
            () -> modelFactory.resolveConfig(NON_CNY_TENANT), "美元计价模型必须拒绝(堵免费敞口)");
        assertTrue(ex.getMessage().contains("人民币"), "话术须说明人民币限制: " + ex.getMessage());
    }

    /**
     * 人民币闸豁免(2026-10-08 OPENCODE 接入, dspro 三审收紧): FIXED_PER_CALL 且按次单价
     * 显式为 0 的真免费模型不依赖官方价格行——resolveConfig 必须放行。
     */
    @Test
    void fixedPerCallZeroPriceBypassesCnyPriceGate() throws Exception {
        exec("INSERT INTO dcs_ai_config (config_id, tenant_id, provider, base_url, api_key, model_name, use_type, enabled, del_flag, billing_mode, unit_price_per_call)"
            + " VALUES (9199000000000002, '" + FIXED_PER_CALL_TENANT + "', 'OPENCODE', 'https://opencode.ai/zen/go/v1', 'x',"
            + " 'longcat-2.5-preview-free', 'CHAT', '1', '0', 'FIXED_PER_CALL', 0)");
        DcsAiConfig cfg = modelFactory.resolveConfig(FIXED_PER_CALL_TENANT);
        assertNotNull(cfg, "按次计费且单价显式 0 的免费模型不依赖价格行, 必须放行");
        assertEquals("FIXED_PER_CALL", cfg.getBillingMode());
    }

    /**
     * 豁免反例(dspro 三审 P1): FIXED_PER_CALL 但单价非 0(缺省 0.01)——错标付费模型不得借
     * 按次口径绕过人民币价格行检查, 必须拒绝且话术含"人民币"。
     */
    @Test
    void fixedPerCallNonZeroPriceStillRequiresCnyRow() throws Exception {
        exec("INSERT INTO dcs_ai_config (config_id, tenant_id, provider, base_url, api_key, model_name, use_type, enabled, del_flag, billing_mode, unit_price_per_call)"
            + " VALUES (9199000000000003, '" + FIXED_PER_CALL_TENANT + "', 'CUSTOM', 'https://paid.example.com/v1', 'x',"
            + " 'paid-model', 'CHAT', '1', '0', 'FIXED_PER_CALL', 0.01)");
        AiNotConfiguredException ex = assertThrows(AiNotConfiguredException.class,
            () -> modelFactory.resolveConfig(FIXED_PER_CALL_TENANT),
            "单价非 0 的按次行必须回到价格行检查(防错标付费模型白嫖)");
        assertTrue(ex.getMessage().contains("人民币"), "话术须说明人民币限制: " + ex.getMessage());
    }

    @AfterEach
    void cleanFixture() throws Exception {
        exec("DELETE FROM dcs_ai_config WHERE tenant_id='" + NON_CNY_TENANT + "' AND config_id=9199000000000001");
        exec("DELETE FROM dcs_ai_config WHERE tenant_id='" + FIXED_PER_CALL_TENANT + "' AND config_id IN (9199000000000002, 9199000000000003)");
    }

    private void exec(String sql) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
