package com.smartdock.service.support;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.DcsAiModelHealth;
import com.smartdock.domain.vo.DcsAiTestResultVo;
import com.smartdock.mapper.DcsAiConfigMapper;
import com.smartdock.mapper.DcsAiModelHealthMapper;
import com.smartdock.service.IDcsAiConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型巡检单测（AI 缺口批 Q9，2026-10-14 拍板全A）：
 * ① 连败纯函数（OK 归零 / FAIL 累加 / 首败=1 / null 前值=1）；
 * ② 首建档 insert（FAIL 首日 streak=1 不足 3 不告警）；
 * ③ 连败第 3 天触发 MODEL_HEALTH_DOWN（月频键 modelHealthDown:{tenant}:{config}:{yyyyMM}）；
 * ④ 恢复 OK 归零不告警；
 * ⑤ 停用配置（patrolProbe=null）跳过不建档；
 * ⑥ 三源目标解析（默认+白名单+近7天用量去重）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ModelHealthSchedulerUnitTest {

    @Mock
    private DcsAiModelHealthMapper healthMapper;

    @Mock
    private DcsAiConfigMapper aiConfigMapper;

    @Mock
    private IDcsAiConfigService aiConfigService;

    @Mock
    private WalletAlertService walletAlertService;

    private ModelHealthScheduler scheduler() {
        return new ModelHealthScheduler(healthMapper, aiConfigMapper, aiConfigService, walletAlertService);
    }

    @Test
    void nextStreak_pureFunction() {
        assertThat(ModelHealthScheduler.nextStreak(true, null)).isZero();
        assertThat(ModelHealthScheduler.nextStreak(true, 5)).isZero();
        assertThat(ModelHealthScheduler.nextStreak(false, null)).isEqualTo(1);
        assertThat(ModelHealthScheduler.nextStreak(false, 0)).isEqualTo(1);
        assertThat(ModelHealthScheduler.nextStreak(false, 2)).isEqualTo(3);
    }

    /** k3 P0-1 回修护栏：通知跳转必须是真实前端路由（菜单 9060 挂「租户管理」下，
     *  与批1 /tenant/ai-credit 同款前缀）——写错用户点「查看模型」直接 404 */
    @Test
    void refUrl_isRealFrontendRoute() {
        assertThat(ModelHealthScheduler.REF_URL).isEqualTo("/tenant/ai-config");
    }

    @Test
    void probeOne_firstFail_insertsStreak1_noAlert() {
        ModelHealthScheduler s = scheduler();
        when(aiConfigService.patrolProbe(9L)).thenReturn(new DcsAiTestResultVo(false, "401 Unauthorized"));
        when(healthMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        lenient().when(aiConfigMapper.selectById(9L)).thenReturn(configRow());

        assertThat(s.probeOneForTest("000000", 9L)).isTrue();

        ArgumentCaptor<DcsAiModelHealth> cap = ArgumentCaptor.forClass(DcsAiModelHealth.class);
        verify(healthMapper).insert(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("FAIL");
        assertThat(cap.getValue().getFailStreak()).isEqualTo(1);
        assertThat(cap.getValue().getDetail()).isEqualTo("401 Unauthorized");
        assertThat(cap.getValue().getLatencyMs()).isNull();
        verify(healthMapper, never()).updateById(any(DcsAiModelHealth.class));
        verify(walletAlertService, never()).publishCreditAlert(anyString(), anyString(), anyString(), anyString(),
            anyString(), any(), anyString());
    }

    @Test
    void probeOne_thirdConsecutiveFail_alertsMonthlyKey() {
        ModelHealthScheduler s = scheduler();
        when(aiConfigService.patrolProbe(9L)).thenReturn(new DcsAiTestResultVo(false, "timeout"));
        DcsAiModelHealth prev = new DcsAiModelHealth();
        prev.setFailStreak(2);
        when(healthMapper.selectOne(any(Wrapper.class))).thenReturn(prev);
        lenient().when(aiConfigMapper.selectById(9L)).thenReturn(configRow());

        s.probeOneForTest("000000", 9L);

        ArgumentCaptor<DcsAiModelHealth> cap = ArgumentCaptor.forClass(DcsAiModelHealth.class);
        verify(healthMapper).updateById(cap.capture());
        assertThat(cap.getValue().getFailStreak()).isEqualTo(3);
        verify(walletAlertService).publishCreditAlert(eq("000000"), eq("MODEL_HEALTH_DOWN"),
            anyString(), anyString(), anyString(), any(), org.mockito.ArgumentMatchers.startsWith("modelHealthDown:000000:9:"));
    }

    @Test
    void probeOne_recoveryResetsStreak_noAlert() {
        ModelHealthScheduler s = scheduler();
        when(aiConfigService.patrolProbe(9L)).thenReturn(new DcsAiTestResultVo(true, "连接成功", 812L));
        DcsAiModelHealth prev = new DcsAiModelHealth();
        prev.setFailStreak(3);
        when(healthMapper.selectOne(any(Wrapper.class))).thenReturn(prev);
        lenient().when(aiConfigMapper.selectById(9L)).thenReturn(configRow());

        assertThat(s.probeOneForTest("000000", 9L)).isFalse();

        ArgumentCaptor<DcsAiModelHealth> cap = ArgumentCaptor.forClass(DcsAiModelHealth.class);
        verify(healthMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("OK");
        assertThat(cap.getValue().getFailStreak()).isZero();
        assertThat(cap.getValue().getLatencyMs()).isEqualTo(812);
        verify(walletAlertService, never()).publishCreditAlert(anyString(), anyString(), anyString(), anyString(),
            anyString(), any(), anyString());
    }

    @Test
    void probeOne_disabledConfig_skipped() {
        ModelHealthScheduler s = scheduler();
        when(aiConfigService.patrolProbe(9L)).thenReturn(null);

        assertThat(s.probeOneForTest("000000", 9L)).isFalse();

        verify(healthMapper, never()).insert(any(DcsAiModelHealth.class));
        verify(healthMapper, never()).updateById(any(DcsAiModelHealth.class));
    }

    @Test
    void resolveTargets_threeSourcesDeduped() {
        ModelHealthScheduler s = scheduler();
        Map<String, Object> setting = new LinkedHashMap<>();
        setting.put("defaultConfigId", 9L);
        setting.put("switchScope", "[11, 12]");
        when(healthMapper.assistantModelSetting("000000")).thenReturn(setting);
        // 9(默认) 与 12(白名单) 在用量里重复出现 → 去重后仍 4 个
        Map<String, Object> u1 = new LinkedHashMap<>();
        u1.put("config_id", 9L);
        Map<String, Object> u2 = new LinkedHashMap<>();
        u2.put("config_id", 12L);
        Map<String, Object> u3 = new LinkedHashMap<>();
        u3.put("config_id", 33L);
        when(healthMapper.recentUsedConfigs(eq("000000"), any(Date.class))).thenReturn(List.of(u1, u2, u3));

        assertThat(s.resolveTargetsForTest("000000", new Date()))
            .containsExactlyInAnyOrder(9L, 11L, 12L, 33L);
    }

    @Test
    void resolveTargets_badScopeJson_failsOpenToEmpty() {
        ModelHealthScheduler s = scheduler();
        Map<String, Object> setting = new LinkedHashMap<>();
        setting.put("defaultConfigId", 9L);
        setting.put("switchScope", "not-json{");
        when(healthMapper.assistantModelSetting("000000")).thenReturn(setting);
        when(healthMapper.recentUsedConfigs(eq("000000"), any(Date.class))).thenReturn(List.of());

        assertThat(s.resolveTargetsForTest("000000", new Date())).containsExactly(9L);
    }

    private DcsAiConfig configRow() {
        DcsAiConfig c = new DcsAiConfig();
        c.setProvider("SILICONFLOW");
        c.setModelName("Qwen/Qwen3-8B");
        c.setEnabled("1");
        return c;
    }
}
