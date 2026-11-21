package com.smartdock.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.domain.AiConfig;
import com.smartdock.domain.AiModelPrice;
import com.smartdock.domain.AiProviderAccess;
import com.smartdock.domain.vo.AiConfigVo;
import com.smartdock.mapper.AiConfigMapper;
import com.smartdock.mapper.AiProviderAccessMapper;
import com.smartdock.service.IAiModelPriceService;
import com.smartdock.service.support.AiChatClientFactory;
import com.smartdock.service.support.AiProviderProbeClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 单点解析器 resolveEnabledConfig 查询形状锁(J 批一期 2026-10-08 改版, 原 2026-09-25 Q7-⑥)。
 *
 * <p>背景: J 批一期起助手路(原 configId 升序)/单发路(原 updateTime 倒序)/MQ 路(原自带直查)
 * 三处查询统一为本解析器——形状锁收拢到这一处即可锁全系统; 批3(2026-10-09)星标化后两段式:
 * 星标段(tenant_id + enabled='1' + is_default='1' + useType 可选 + limit 1, 唯一索引免排序)
 * → miss 回退段(tenant_id + enabled='1' + useType 可选 + update_time DESC, config_id DESC + limit 1);
 * 含 CNY 闸与 FIXED 免费豁免。</p>
 *
 * @author smartdock
 */
@ExtendWith(MockitoExtension.class)
class AiConfigQueryShapeUnitTest {

    @Mock
    private AiConfigMapper aiConfigMapper;
    @Mock
    private AiProviderAccessMapper accessMapper;
    @Mock
    private AiChatClientFactory chatClientFactory;
    @Mock
    private AiProviderProbeClient providerProbeClient;
    @Mock
    private IAiModelPriceService modelPriceService;
    @Mock
    private com.smartdock.service.support.ProviderKeyPool keyPool;
    @Mock
    private com.smartdock.mapper.AssistantBackendDefaultMapper backendDefaultMapper;
    @Mock
    private com.smartdock.mapper.AiModelHealthMapper healthMapper;
    @Mock
    private com.smartdock.mapper.AiUsageMapper usageMapper;

    private AiConfigServiceImpl service() {
        // Q8：ServiceImpl 新增钥匙池依赖（hydrate 轮询取钥）——单测注入 mock
        // 批1015c Q1：新增后台默认槽只读 mapper（resolveBackendConfig 用；形状锁用例不触及，注入 mock 防构造缺失）
        // N1 Q2/Q3：新增巡检表与用量台账依赖（形状锁用例不触及，注入 mock 防构造缺失）
        return new AiConfigServiceImpl(aiConfigMapper, accessMapper, chatClientFactory, providerProbeClient, modelPriceService, keyPool, backendDefaultMapper, healthMapper, usageMapper);
    }

    @BeforeAll
    static void initTableInfo() {
        // lambda 列解析依赖 TableInfo, 单测环境先初始化(批B 起接入表也参与 hydrate 查询)
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "ai-config-query-shape"), AiConfig.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "ai-provider-access-shape"), AiProviderAccess.class);
    }

    /** FIXED_PER_CALL 且单价 0 = 真免费豁免, 形状锁用例借它绕开价格服务调用 */
    private AiConfig fixedFreeConfig(String tenantId, String useType) {
        AiConfig c = new AiConfig();
        c.setConfigId(1L);
        c.setTenantId(tenantId);
        c.setProvider("opencode");
        c.setModelName("longcat-2.5-preview-free");
        c.setUseType(useType);
        c.setEnabled("1");
        c.setBillingMode("FIXED_PER_CALL");
        c.setUnitPricePerCall(java.math.BigDecimal.ZERO);
        return c;
    }

    @Test
    @Tag("dev")
    @SuppressWarnings("unchecked")
    void resolveLocksTenantEnabledUseTypeUpdateDescLimit1() {
        // 批3 两段式: 星标段命中即返回(星段不排序, 唯一索引保证至多一星)
        AiConfig cfg = fixedFreeConfig("000000", "CHAT");
        when(aiConfigMapper.selectOne(any())).thenReturn(cfg);

        AiConfig out = service().resolveEnabledConfig("000000", "CHAT");
        assertThat(out).isSameAs(cfg);

        ArgumentCaptor<LambdaQueryWrapper<AiConfig>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(aiConfigMapper).selectOne(captor.capture());
        String sql = captor.getValue().getSqlSegment().toLowerCase();
        assertThat(sql).contains("tenant_id").contains("enabled").contains("use_type")
            .contains("is_default").contains("limit 1");
        assertThat(captor.getValue().getParamNameValuePairs())
            .containsValue("000000")
            .containsValue("1")
            .containsValue("CHAT");
    }

    /** 批3 回退段: 星标段 miss 后第二查=任意可用行 update_time DESC, config_id DESC(兜底口径防回归) */
    @Test
    @Tag("dev")
    @SuppressWarnings("unchecked")
    void resolveFallbackSegmentLocksUpdateDescShape() {
        AiConfig cfg = fixedFreeConfig("000000", "CHAT");
        when(aiConfigMapper.selectOne(any())).thenReturn(null, cfg);

        assertThat(service().resolveEnabledConfig("000000", "CHAT")).isSameAs(cfg);

        ArgumentCaptor<LambdaQueryWrapper<AiConfig>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(aiConfigMapper, org.mockito.Mockito.times(2)).selectOne(captor.capture());
        String starSql = captor.getAllValues().get(0).getSqlSegment().toLowerCase();
        String fallbackSql = captor.getAllValues().get(1).getSqlSegment().toLowerCase();
        assertThat(starSql).contains("is_default");
        assertThat(fallbackSql).contains("tenant_id").contains("enabled").contains("use_type")
            .contains("update_time desc").contains("config_id desc").contains("limit 1")
            .doesNotContain("is_default");
    }

    @Test
    @Tag("dev")
    @SuppressWarnings("unchecked")
    void nullUseTypeOmitsUseTypeColumnForLegacyAnyUseTypeBehavior() {
        // 批3 两段式: 星标段命中(useType null 省略 use_type 条件, 旧行为兼容)
        AiConfig cfg = fixedFreeConfig("000000", null);
        when(aiConfigMapper.selectOne(any())).thenReturn(cfg);

        assertThat(service().resolveEnabledConfig("000000", null)).isSameAs(cfg);

        ArgumentCaptor<LambdaQueryWrapper<AiConfig>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(aiConfigMapper).selectOne(captor.capture());
        String sql = captor.getValue().getSqlSegment().toLowerCase();
        assertThat(sql).contains("tenant_id").contains("enabled").contains("is_default").contains("limit 1");
        // 批 9（2026-11-21）存量红修复：本断言守的是「useType null 走星标段、不拼 use_type 列」；
        // 旧版顺带要求不含 update_time——那是早期星标段无 ORDER BY 时的段形状指纹，件4 统一
        // selectEnabledRow 后两段都带 update_time 兜底排序，指纹失效（HEAD 同码同断言=批 9 前
        // 就红）。星标段判别特征已由 contains("is_default") 承担；兜底段另有同款断言在
        // nullUseTypeFallbackAlsoOmitsUseTypeColumn 验证 use_type 省略语义不变。
        assertThat(sql).doesNotContain("use_type");
    }

    /** 批3 回退段同享「useType null 省略 use_type」的旧行为兼容 */
    @Test
    @Tag("dev")
    @SuppressWarnings("unchecked")
    void nullUseTypeFallbackAlsoOmitsUseTypeColumn() {
        AiConfig cfg = fixedFreeConfig("000000", null);
        when(aiConfigMapper.selectOne(any())).thenReturn(null, cfg);

        assertThat(service().resolveEnabledConfig("000000", null)).isSameAs(cfg);

        ArgumentCaptor<LambdaQueryWrapper<AiConfig>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(aiConfigMapper, org.mockito.Mockito.times(2)).selectOne(captor.capture());
        String fallbackSql = captor.getAllValues().get(1).getSqlSegment().toLowerCase();
        assertThat(fallbackSql).contains("tenant_id").contains("enabled")
            .contains("update_time desc").contains("limit 1");
        assertThat(fallbackSql).doesNotContain("use_type");
    }

    @Test
    @Tag("dev")
    void absentConfigThrowsWithTenantAndUseTypeHint() {
        when(aiConfigMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service().resolveEnabledConfig("900001", "EMBEDDING"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("900001")
            .hasMessageContaining("EMBEDDING")
            .hasMessageContaining("未启用");
        assertThatThrownBy(() -> service().resolveEnabledConfig("900001", null))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("未启用的 AI 模型配置");
    }

    /** currentTenantEnabledConfig(HTTP 入口)透传登录租户到单点解析器——委托不改形状 */
    @Test
    @Tag("dev")
    @SuppressWarnings("unchecked")
    void currentTenantEntryDelegatesLoginTenantToResolver() {
        AiConfig cfg = fixedFreeConfig("000000", "CHAT");
        when(aiConfigMapper.selectOne(any())).thenReturn(cfg);
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getTenantId).thenReturn("000000");

            assertThat(service().currentTenantEnabledConfig("CHAT")).isSameAs(cfg);
        }

        ArgumentCaptor<LambdaQueryWrapper<AiConfig>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(aiConfigMapper).selectOne(captor.capture());
        // MP 3.5.x 参数映射惰性物化: 须先触发 getSqlSegment() 再读 paramNameValuePairs
        assertThat(captor.getValue().getSqlSegment()).contains("tenant_id");
        assertThat(captor.getValue().getParamNameValuePairs())
            .containsValue("000000")
            .containsValue("CHAT");
    }

    /** CNY 闸(拍板 cnyGate=A): 非 FIXED-free 走价格行检查, USD 行全系统统一拒 */
    @Test
    @Tag("dev")
    void nonCnyPriceRejectedWithHumanMessage() {
        AiConfig cfg = fixedFreeConfig("900001", "CHAT");
        cfg.setBillingMode("OFFICIAL_TOKEN"); // 非免费 → 必须查价格行
        when(aiConfigMapper.selectOne(any())).thenReturn(cfg);
        AiModelPrice usd = new AiModelPrice();
        usd.setCurrency("USD");
        when(modelPriceService.findEffectivePrice(anyString(), anyString(), anyString())).thenReturn(usd);

        assertThatThrownBy(() -> service().resolveEnabledConfig("900001", "CHAT"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("人民币");

        AiModelPrice cny = new AiModelPrice();
        cny.setCurrency("CNY");
        when(modelPriceService.findEffectivePrice(anyString(), anyString(), anyString())).thenReturn(cny);
        assertThat(service().resolveEnabledConfig("900001", "CHAT")).isSameAs(cfg);
    }

    /** FIXED 且单价显式 0 = 真免费豁免: 不触碰价格服务(库挂也不影响免费模型可用) */
    @Test
    @Tag("dev")
    void fixedFreeBypassesPriceServiceEntirely() {
        when(aiConfigMapper.selectOne(any())).thenReturn(fixedFreeConfig("900001", "CHAT"));

        assertThat(service().resolveEnabledConfig("900001", "CHAT")).isNotNull();
        verifyNoInteractions(modelPriceService);
    }

    /**
     * 批4 listUsableWithPrice(选择卡数据源): 只返回可用行(停用天然过滤)+★排最前;
     * token 行透出价格目录 input/output 单价, FIXED 行不查价格(免打扰价格服务)。
     */
    @Test
    @Tag("dev")
    @SuppressWarnings("unchecked")
    void listUsableLocksEnabledFilterAndPriceEnrich() {
        // ★token 行 + 普通token行 + 停用行(mapper 层已过滤, 这里只给可用行) + FIXED 行
        AiConfigVo starred = vo("900001", "DEEPSEEK", "deepseek-v4-flash", "OFFICIAL_TOKEN");
        starred.setIsDefault("1");
        AiConfigVo plain = vo("900001", "OPENAI", "gpt-4.1-mini", "OFFICIAL_TOKEN");
        AiConfigVo fixed = vo("900001", "OPENCODE", "longcat-free", "FIXED_PER_CALL");
        fixed.setUnitPricePerCall(java.math.BigDecimal.ZERO);
        when(aiConfigMapper.selectVoList(any())).thenReturn(java.util.List.of(starred, plain, fixed));
        AiModelPrice cny = new AiModelPrice();
        cny.setCurrency("CNY");
        cny.setInputPricePerMillion(new java.math.BigDecimal("2.00"));
        cny.setOutputPricePerMillion(new java.math.BigDecimal("8.00"));
        when(modelPriceService.findEffectivePrice(anyString(), anyString(), anyString())).thenReturn(cny);

        var out = service().listUsableWithPrice("900001", "CHAT");

        // 形状: tenant+enabled+useType 谓词, ★优先(update_time 次序)
        ArgumentCaptor<LambdaQueryWrapper<AiConfig>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(aiConfigMapper).selectVoList(captor.capture());
        String sql = captor.getValue().getSqlSegment().toLowerCase();
        assertThat(sql).contains("tenant_id").contains("enabled").contains("use_type")
            .contains("is_default desc").contains("update_time desc");
        // 价格 enrich: token 两行带单价；FIXED 行自业务批4 Q3 起也回填（OPENCODE 0 元行
        // 不回填会显示「价格未配置」误导），unitPricePerCall 原 0 保留不动
        assertThat(out.get(0).getInputPricePerMillion()).isEqualByComparingTo("2.00");
        assertThat(out.get(0).getOutputPricePerMillion()).isEqualByComparingTo("8.00");
        assertThat(out.get(0).getCurrency()).isEqualTo("CNY");
        assertThat(out.get(2).getInputPricePerMillion()).isEqualByComparingTo("2.00");
        assertThat(out.get(2).getUnitPricePerCall()).isEqualByComparingTo("0");
        verify(modelPriceService, org.mockito.Mockito.times(3))
            .findEffectivePrice(anyString(), anyString(), anyString());
        // Key 全部 mask
        assertThat(out).allMatch(v -> v.getApiKey() == null || v.getApiKey().contains("*"));
    }

    private AiConfigVo vo(String tenantId, String provider, String model, String billingMode) {
        AiConfigVo v = new AiConfigVo();
        v.setConfigId(1L);
        v.setTenantId(tenantId);
        v.setProvider(provider);
        v.setModelName(model);
        v.setUseType("CHAT");
        v.setEnabled("1");
        v.setBillingMode(billingMode);
        v.setApiKey("sk-secret");
        return v;
    }

    /** 批B hydrate 两跳第一跳：接入行存在 → 行内钥匙/地址被接入层覆盖（换钥匙一处生效的形状锁） */
    @Test
    @Tag("dev")
    void hydratePrefersAccessRowOverLegacyInline() {
        AiConfig cfg = fixedFreeConfig("900001", "CHAT");
        cfg.setApiKey("sk-old-inline");
        cfg.setBaseUrl("https://old.example.com/v1");
        when(aiConfigMapper.selectOne(any())).thenReturn(cfg);
        AiProviderAccess access = new AiProviderAccess();
        access.setTenantId("900001");
        access.setProvider("opencode");
        access.setApiKey("sk-new-access");
        access.setBaseUrl("https://new.example.com/v1");
        when(accessMapper.selectOne(any())).thenReturn(access);

        AiConfig out = service().resolveEnabledConfig("900001", "CHAT");

        assertThat(out.getApiKey()).isEqualTo("sk-new-access");
        assertThat(out.getBaseUrl()).isEqualTo("https://new.example.com/v1");
        // 接入行查询形状：唯一键 (tenant_id, provider) + limit 1
        ArgumentCaptor<LambdaQueryWrapper<AiProviderAccess>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(accessMapper).selectOne(captor.capture());
        String sql = captor.getValue().getSqlSegment().toLowerCase();
        assertThat(sql).contains("tenant_id").contains("provider").contains("limit 1");
    }

    /** 批B hydrate 第二跳（回退兜底）：接入行缺失 → 保留旧行内密文/地址（Q5:A 迁移安全网） */
    @Test
    @Tag("dev")
    void hydrateFallsBackToLegacyInlineWhenAccessMissing() {
        AiConfig cfg = fixedFreeConfig("900001", "CHAT");
        cfg.setApiKey("sk-legacy-inline");
        cfg.setBaseUrl("https://legacy.example.com/v1");
        when(aiConfigMapper.selectOne(any())).thenReturn(cfg);
        when(accessMapper.selectOne(any())).thenReturn(null);

        AiConfig out = service().resolveEnabledConfig("900001", "CHAT");

        assertThat(out.getApiKey()).isEqualTo("sk-legacy-inline");
        assertThat(out.getBaseUrl()).isEqualTo("https://legacy.example.com/v1");
    }

    /**
     * 批1015c Q1 resolveBackendConfig 分支①：后台默认槽已设(>0) → 走 resolveConfigById
     * 主键解析（行存在/租户匹配/可用+CNY 闸），不触碰 ★默认两段式查询。
     */
    @Test
    @Tag("dev")
    void backendSlotSetResolvesConfigById() {
        AiConfig cfg = fixedFreeConfig("900001", "CHAT");
        cfg.setConfigId(5L);
        when(backendDefaultMapper.findBackendDefault("900001")).thenReturn(5L);
        when(aiConfigMapper.selectById(5L)).thenReturn(cfg);

        assertThat(service().resolveBackendConfig("900001")).isSameAs(cfg);
        verify(aiConfigMapper, org.mockito.Mockito.never()).selectOne(any());
    }

    /** 批1015c Q1 分支②：槽空(null/0) → 行为与改造前完全一致（★默认→最近可用两段式） */
    @Test
    @Tag("dev")
    void backendSlotEmptyFallsBackToEnabledResolver() {
        AiConfig cfg = fixedFreeConfig("900001", "CHAT");
        when(backendDefaultMapper.findBackendDefault("900001")).thenReturn(null);
        when(aiConfigMapper.selectOne(any())).thenReturn(cfg);

        assertThat(service().resolveBackendConfig("900001")).isSameAs(cfg);
        verify(aiConfigMapper, org.mockito.Mockito.never()).selectById(any());
    }

    /**
     * 批1015c Q1 分支③：槽指向失效行（已删）→ resolveConfigById 抛 ServiceException，
     * 静默回退接口默认——坏槽不炸后台功能（三审 P1/P2 回修的回归锁）。
     */
    @Test
    @Tag("dev")
    void backendSlotDeadSilentlyFallsBack() {
        AiConfig cfg = fixedFreeConfig("900001", "CHAT");
        when(backendDefaultMapper.findBackendDefault("900001")).thenReturn(999L);
        when(aiConfigMapper.selectById(999L)).thenReturn(null);
        when(aiConfigMapper.selectOne(any())).thenReturn(cfg);

        assertThat(service().resolveBackendConfig("900001")).isSameAs(cfg);
    }
}
