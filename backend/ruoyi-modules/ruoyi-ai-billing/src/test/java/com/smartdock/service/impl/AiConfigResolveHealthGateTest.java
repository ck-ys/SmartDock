package com.smartdock.service.impl;

import com.smartdock.domain.AiConfig;
import com.smartdock.domain.AiModelHealth;
import com.smartdock.mapper.AssistantBackendDefaultMapper;
import com.smartdock.mapper.AiConfigMapper;
import com.smartdock.mapper.AiModelHealthMapper;
import com.smartdock.mapper.AiProviderAccessMapper;
import com.smartdock.mapper.AiUsageMapper;
import com.smartdock.service.IAiModelPriceService;
import com.smartdock.service.support.AiChatClientFactory;
import com.smartdock.service.support.AiProviderProbeClient;
import com.smartdock.service.support.ProviderKeyPool;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 小修包件4（2026-11-02 拍板「全按推荐」）：选路健康闸——巡检 FAIL 且连败>=2 的死钥匙
 * 先排除；全排除时回退原序；单次抖动（连败 1）不排除。
 * 行为锁：按 mapper 调用序断言「排除段→回退段」的取行链路，结果行即证据。
 */
@Tag("local")
@ExtendWith(MockitoExtension.class)
class AiConfigResolveHealthGateTest {

    @Mock private AiConfigMapper aiConfigMapper;
    @Mock private AiProviderAccessMapper accessMapper;
    @Mock private AiChatClientFactory chatClientFactory;
    @Mock private AiProviderProbeClient providerProbeClient;
    @Mock private IAiModelPriceService modelPriceService;
    @Mock private ProviderKeyPool keyPool;
    @Mock private AssistantBackendDefaultMapper backendDefaultMapper;
    @Mock private AiModelHealthMapper healthMapper;
    @Mock private AiUsageMapper usageMapper;

    private AiConfigServiceImpl newService() {
        return new AiConfigServiceImpl(aiConfigMapper, accessMapper, chatClientFactory,
            providerProbeClient, modelPriceService, keyPool, backendDefaultMapper,
            healthMapper, usageMapper);
    }

    @BeforeAll
    static void initTableInfo() {
        // lambda 列解析依赖 TableInfo, 单测环境先初始化(同 AiConfigQueryShapeUnitTest 配方)
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "ai-config-health-gate"), AiConfig.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "ai-model-health-gate"), AiModelHealth.class);
    }

    /** FIXED_PER_CALL + 单价 0 = 真免费档, 过 CNY 闸不查价格表 */
    private static AiConfig freeRow(long id) {
        AiConfig c = new AiConfig();
        c.setConfigId(id);
        c.setTenantId("000000");
        c.setEnabled("1");
        c.setUseType("CHAT");
        c.setBillingMode("FIXED_PER_CALL");
        c.setUnitPricePerCall(BigDecimal.ZERO);
        return c;
    }

    private static AiModelHealth health(long id, String status, Integer streak) {
        AiModelHealth h = new AiModelHealth();
        h.setConfigId(id);
        h.setStatus(status);
        h.setFailStreak(streak);
        return h;
    }

    @Test
    void deadKeyExcluded_starRowSurvives() {
        when(healthMapper.selectList(org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(health(1L, "FAIL", 3)));
        when(aiConfigMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(freeRow(2L));

        AiConfig out = newService().resolveEnabledConfig("000000", "CHAT");

        assertThat(out.getConfigId()).isEqualTo(2L);
        verify(aiConfigMapper, times(1)).selectOne(org.mockito.ArgumentMatchers.any());

        // 三席审查(dsflash P2-5): 只 mock 返回行是空转——谓词错写也能绿。
        // 抓 wrapper 把「status=FAIL + fail_streak>=2 + 活配置 exists」钉成结构断言
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper> captor =
            org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(healthMapper).selectList(captor.capture());
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?> wrapper = captor.getValue();
        String segment = String.valueOf(wrapper.getSqlSegment());
        assertThat(segment).contains("status");
        // 三席复审(dsflash P2-5): 连操作符一起钉死——把 ge 误写成 le/eq 也不能绿
        assertThat(segment).contains("fail_streak >=");
        assertThat(segment).containsIgnoringCase("exists");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("FAIL");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(2);
    }

    @Test
    void healthQueryFailureFailsOpen() {
        // 三席审查(dsflash P2-4): 健康闸是附属查询, 挂了不能把整条 AI 选路打死
        when(healthMapper.selectList(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new IllegalStateException("health table unavailable"));
        when(aiConfigMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(freeRow(1L));

        AiConfig out = newService().resolveEnabledConfig("000000", "CHAT");

        assertThat(out.getConfigId()).isEqualTo(1L);
        verify(aiConfigMapper, times(1)).selectOne(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void singleBlipNotExcluded() {
        when(healthMapper.selectList(org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(health(1L, "FAIL", 1)));
        when(aiConfigMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(freeRow(1L));

        AiConfig out = newService().resolveEnabledConfig("000000", "CHAT");

        // 连败 1 只算抖动: 健康闸空集, 第一段就取回 1 号行
        assertThat(out.getConfigId()).isEqualTo(1L);
        verify(aiConfigMapper, times(1)).selectOne(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void allUnhealthy_fallsBackToOriginalOrder() {
        when(healthMapper.selectList(org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(health(1L, "FAIL", 5)));
        // 排除段: ★默认段空 + 兜底段空; 回退段: ★默认段仍空, 兜底段取回死钥匙 1 号(宁可 401 不误报无配置)
        when(aiConfigMapper.selectOne(org.mockito.ArgumentMatchers.any()))
            .thenReturn(null, null, null, freeRow(1L));

        AiConfig out = newService().resolveEnabledConfig("000000", "CHAT");

        assertThat(out.getConfigId()).isEqualTo(1L);
        verify(aiConfigMapper, times(4)).selectOne(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cheapestSkipsDeadKey_thenFallsBackWhenAllDead() {
        when(healthMapper.selectList(org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(health(1L, "FAIL", 2)));
        // 第一次(排除 1 号)空 → 回退原序取回 1 号
        when(aiConfigMapper.selectOne(org.mockito.ArgumentMatchers.any()))
            .thenReturn(null, freeRow(1L));

        AiConfig out = newService().resolveCheapestEnabledConfig("000000", "CHAT");

        assertThat(out).isNotNull();
        assertThat(out.getConfigId()).isEqualTo(1L);
        verify(aiConfigMapper, times(2)).selectOne(org.mockito.ArgumentMatchers.any());
    }
}
