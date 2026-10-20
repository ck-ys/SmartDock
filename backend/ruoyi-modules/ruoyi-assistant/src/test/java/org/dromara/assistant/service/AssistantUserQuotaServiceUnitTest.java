package org.dromara.assistant.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.assistant.domain.AssistantUserQuota;
import org.dromara.assistant.limiter.AssistantDailyQuota;
import org.dromara.assistant.mapper.AssistantUserQuotaMapper;
import org.dromara.assistant.service.AssistantConfigService.EffectiveConfig;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务批3-③（#16 2026-10-12）单元测试：用户额度三层合成与禁用闸。
 *
 * <p>覆盖计划 S3 要求：effectiveLimits 合成矩阵（NULL 跟随租户默认/覆盖值压默认/
 * disabled 行覆盖值仍返回供展示）、禁用闸 429 人话文案断言、双空保存走 clearLimits
 * 不 upsert、pageList 的 PG 小写键→前端驼峰契约重组。纯 Mockito 不起 Spring——
 * LambdaQueryWrapper 需 TableInfo 元数据，@BeforeAll 显式初始化（MP 无 Spring 环境
 * 不自动建缓存，缺了会 "can not find lambda cache"）。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AssistantUserQuotaServiceUnitTest {

    private static final String TENANT = "000000";
    private static final long USER = 9100000001L;

    @Mock
    private AssistantUserQuotaMapper quotaMapper;
    @Mock
    private AssistantConfigService configService;
    @Mock
    private AssistantDailyQuota dailyQuota;
    @Mock
    private org.dromara.assistant.limiter.AssistantWebSearchQuota webSearchQuota;

    @InjectMocks
    private AssistantUserQuotaService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AssistantUserQuota.class);
    }

    /** 租户默认行：回合 50/预算 20/记忆开/联网 20（dcs_assistant_config 无行时平台默认同款量级） */
    private EffectiveConfig tenantDefault(int rounds) {
        return tenantDefault(rounds, 20);
    }

    /** 批④起联网租户默认可自定义（websearch_limit_per_user 列）；称呼列快刀批 Q6 新增（此处不参与配额口径，占位即可） */
    private EffectiveConfig tenantDefault(int rounds, int websearch) {
        return new EffectiveConfig(true, true, rounds, new BigDecimal("20"), "1", 60000, websearch, "0", null);
    }

    /** 覆盖行实体（null 列=跟随默认） */
    private AssistantUserQuota row(Integer round, Integer websearch, String disabled) {
        AssistantUserQuota q = new AssistantUserQuota();
        q.setTenantId(TENANT);
        q.setUserId(USER);
        q.setRoundLimitOverride(round);
        q.setWebsearchLimitOverride(websearch);
        q.setDisabled(disabled);
        return q;
    }

    private void stubLimits(AssistantUserQuota quotaRow) {
        when(configService.effective(TENANT)).thenReturn(tenantDefault(50));
        when(quotaMapper.selectOne(any())).thenReturn(quotaRow);
    }

    @Test
    void 无覆盖行_回合联网都跟随租户默认_未禁用() {
        stubLimits(null);
        AssistantUserQuotaService.UserLimits limits = service.effectiveLimits(TENANT, USER);
        assertThat(limits.roundLimit()).isEqualTo(50);
        assertThat(limits.websearchLimit()).isEqualTo(20);
        assertThat(limits.disabled()).isFalse();
    }

    @Test
    void 回合覆盖压过租户默认_未覆盖的联网列仍默认() {
        stubLimits(row(30, null, "0"));
        AssistantUserQuotaService.UserLimits limits = service.effectiveLimits(TENANT, USER);
        assertThat(limits.roundLimit()).isEqualTo(30);
        assertThat(limits.websearchLimit()).isEqualTo(20);
        assertThat(limits.disabled()).isFalse();
    }

    @Test
    void 禁用行_disabled置位_覆盖值仍返回供前端展示() {
        // 前端禁用态要展示"本会限 30"，所以 disabled 行的限额列照常合成，闸只看 disabled
        stubLimits(row(30, 99, "1"));
        AssistantUserQuotaService.UserLimits limits = service.effectiveLimits(TENANT, USER);
        assertThat(limits.disabled()).isTrue();
        assertThat(limits.roundLimit()).isEqualTo(30);
        assertThat(limits.websearchLimit()).isEqualTo(99);
    }

    @Test
    void 禁用用户_闸拒绝_429人话文案() {
        stubLimits(row(30, null, "1"));
        assertThatThrownBy(() -> service.assertEnabled(TENANT, USER))
            .isInstanceOf(ServiceException.class)
            .hasMessage("管理员已暂停你的 AI 助手使用，如有疑问请联系管理员")
            .extracting(e -> ((ServiceException) e).getCode())
            .isEqualTo(429);
    }

    @Test
    void 未禁用用户_闸放行不抛() {
        stubLimits(row(30, null, "0"));
        service.assertEnabled(TENANT, USER);
        verify(quotaMapper).selectOne(any());
    }

    @Test
    void 双空保存_走clearLimits_不走upsert() {
        // upsert 是 COALESCE 写不回 NULL，清空跟随默认必须走专用 clearLimits
        service.updateLimits(TENANT, USER, null, null);
        verify(quotaMapper).clearLimits(TENANT, USER);
        verify(quotaMapper, never()).upsert(any());
    }

    @Test
    void 非法限额_400人话拒绝_不落库() {
        assertThatThrownBy(() -> service.updateLimits(TENANT, USER, 0, null))
            .isInstanceOf(ServiceException.class)
            .hasMessage("回合上限须在 1~10000 之间")
            .extracting(e -> ((ServiceException) e).getCode())
            .isEqualTo(400);
        assertThatThrownBy(() -> service.updateLimits(TENANT, USER, 10001, null))
            .isInstanceOf(ServiceException.class);
        verify(quotaMapper, never()).upsert(any());
        verify(quotaMapper, never()).clearLimits(anyString(), any());
    }

    @Test
    void 重置今日_双limiter同参委托_回合与联网计数键同点清() {
        // 批④接线：管理端「重置今日」一键恢复两类额度（计划批③ Interfaces 约定兑现）
        service.resetToday(TENANT, USER);
        verify(dailyQuota).resetToday(TENANT, USER);
        verify(webSearchQuota).resetToday(TENANT, USER);
    }

    @Test
    void 联网租户默认列生效_覆盖压过列值() {
        // 批④ S1：websearch 租户默认从常量换源 config 列（此处 30），单人覆盖（99）仍最高优
        stubLimits(row(null, null, "0"));
        when(configService.effective(TENANT)).thenReturn(tenantDefault(50, 30));
        assertThat(service.effectiveLimits(TENANT, USER).websearchLimit()).isEqualTo(30);
        stubLimits(row(null, 99, "0"));
        when(configService.effective(TENANT)).thenReturn(tenantDefault(50, 30));
        assertThat(service.effectiveLimits(TENANT, USER).websearchLimit()).isEqualTo(99);
    }

    @Test
    void 四象限分流_双值走upsert_单列空走定向清列SQL() {
        // 批④ S4：两列独立「跟随默认」——双值=upsert；round值+联网空=upsertRoundClearWeb；
        // round空+联网值=upsertWebClearRound；双空=clearLimits（另有专测）。
        // id 由 MP 参数处理器在真实运行时填充，mock 层不断言
        service.updateLimits(TENANT, USER, 50, 20);
        verify(quotaMapper).upsert(any());
        verify(quotaMapper, never()).upsertRoundClearWeb(any());
        verify(quotaMapper, never()).upsertWebClearRound(any());
        // 分象限清调用记录，后续 never() 断言只对当前象限负责
        org.mockito.Mockito.clearInvocations(quotaMapper);

        service.updateLimits(TENANT, USER, 50, null);
        ArgumentCaptor<AssistantUserQuota> cap1 = ArgumentCaptor.forClass(AssistantUserQuota.class);
        verify(quotaMapper).upsertRoundClearWeb(cap1.capture());
        assertThat(cap1.getValue().getRoundLimitOverride()).isEqualTo(50);
        // 防静默丢联网上限（三席 P0-2 登记项）：round 设值时联网列必须显式清，不走 COALESCE upsert
        verify(quotaMapper, never()).upsert(any());
        org.mockito.Mockito.clearInvocations(quotaMapper);

        service.updateLimits(TENANT, USER, null, 99);
        ArgumentCaptor<AssistantUserQuota> cap2 = ArgumentCaptor.forClass(AssistantUserQuota.class);
        verify(quotaMapper).upsertWebClearRound(cap2.capture());
        assertThat(cap2.getValue().getWebsearchLimitOverride()).isEqualTo(99);

        verify(quotaMapper, never()).clearLimits(anyString(), any());
    }

    @Test
    void 禁用保存_走upsert_disabled置1() {
        service.setDisabled(TENANT, USER, true);
        ArgumentCaptor<AssistantUserQuota> cap = ArgumentCaptor.forClass(AssistantUserQuota.class);
        verify(quotaMapper).upsert(cap.capture());
        assertThat(cap.getValue().getDisabled()).isEqualTo("1");
        // COALESCE 语义：未提的限额列=null=保持库内原值
        assertThat(cap.getValue().getRoundLimitOverride()).isNull();
    }

    @Test
    void pageList_小写键重组驼峰_覆盖行生效值_无费用行缺省0() {
        // Mapper Map 键=PG 小写折叠（userid/nickname/...），Service 负责重组前端驼峰契约
        Map<String, Object> withOverride = new LinkedHashMap<>();
        withOverride.put("userid", USER);
        withOverride.put("nickname", "张三");
        withOverride.put("username", "user001");
        withOverride.put("deptname", "计算机部门");
        withOverride.put("roundoverride", 30);
        withOverride.put("websearchoverride", null);
        withOverride.put("disabled", "0");
        Map<String, Object> plainUser = new LinkedHashMap<>();
        plainUser.put("userid", 9100000002L);
        plainUser.put("nickname", "李四");
        plainUser.put("username", "user002");
        plainUser.put("deptname", "计算机部门");
        plainUser.put("roundoverride", null);
        plainUser.put("websearchoverride", null);
        plainUser.put("disabled", "0");
        when(quotaMapper.selectQuotaPage(anyString(), any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of(withOverride, plainUser));
        when(quotaMapper.countQuotaPage(anyString(), any(), any())).thenReturn(2L);
        // 本月费用只有第一个用户有记录，第二个走缺省 0
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("userid", USER);
        cost.put("monthcost", new BigDecimal("1.50"));
        when(quotaMapper.selectMonthCostByUsers(anyString(), anyList())).thenReturn(List.of(cost));
        when(configService.effective(TENANT)).thenReturn(tenantDefault(50));
        when(dailyQuota.turnsUsed(TENANT, USER)).thenReturn(8L);

        Map<String, Object> out = service.pageList(TENANT, "  ", null, 1, 20);

        assertThat(out.get("total")).isEqualTo(2L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows).hasSize(2);
        // 覆盖行：驼峰键齐 + 生效回合=覆盖 30 + 今日 8 + 本月 1.50
        Map<String, Object> first = rows.get(0);
        assertThat(first.get("userId")).isEqualTo(USER);
        assertThat(first.get("nickName")).isEqualTo("张三");
        assertThat(first.get("userName")).isEqualTo("user001");
        assertThat(first.get("deptName")).isEqualTo("计算机部门");
        assertThat(first.get("roundLimitOverride")).isEqualTo(30);
        assertThat(first.get("effectiveRoundLimit")).isEqualTo(30);
        assertThat(first.get("turnsUsedToday")).isEqualTo(8L);
        assertThat(first.get("monthCost")).isEqualTo(new BigDecimal("1.50"));
        // 未覆盖行：生效回合=租户默认 50 + 无费用记录缺省 0
        Map<String, Object> second = rows.get(1);
        assertThat(second.get("userId")).isEqualTo(9100000002L);
        assertThat(second.get("effectiveRoundLimit")).isEqualTo(50);
        assertThat(second.get("monthCost")).isEqualTo(0);
    }

    /* ===== 业务批7 Q1：billingAwareLimits 四格矩阵（dspro P2-7——P0-2 急停回归锁） ===== */

    @Mock
    private com.smartdock.service.IDcsAiCreditService creditService;

    private com.smartdock.domain.DcsAiCreditSubscription sub(long planId) {
        com.smartdock.domain.DcsAiCreditSubscription s = new com.smartdock.domain.DcsAiCreditSubscription();
        s.setTenantId(TENANT);
        s.setPlanId(planId);
        s.setStatus("1");
        return s;
    }

    @Test
    void 未订阅_原样透传_wallet模式() {
        stubLimits(null);
        when(creditService.consumeMode(TENANT)).thenReturn("WALLET");
        AssistantUserQuotaService.BillingAwareLimits limits = service.billingAwareLimits(TENANT, USER);
        assertThat(limits.creditsMode()).isFalse();
        assertThat(limits.roundLimit()).isEqualTo(50);
        assertThat(limits.websearchLimit()).isEqualTo(20);
    }

    @Test
    void 订阅生效_回合切不限次_联网切套餐档位() {
        stubLimits(null);
        when(creditService.consumeMode(TENANT)).thenReturn("CREDIT");
        when(creditService.activeSubscription(TENANT)).thenReturn(java.util.Optional.of(sub(210002L)));
        when(creditService.subscriberWebsearchLimit(TENANT)).thenReturn(java.util.Optional.of(150));
        AssistantUserQuotaService.BillingAwareLimits limits = service.billingAwareLimits(TENANT, USER);
        assertThat(limits.creditsMode()).isTrue();
        assertThat(limits.roundLimit()).isEqualTo(AssistantUserQuotaService.UNLIMITED_ROUNDS);
        assertThat(limits.websearchLimit()).isEqualTo(150);
    }

    @Test
    void 开关切回钱包_有生效订阅仍wallet模式() {
        // 业务批8 Q7 丁口径收口回归锁（2026-10-14 用户反馈暴露）：creditsMode 锚点=consumeMode
        // 开关而非"有无生效订阅"——租户手动切回 WALLET 后（即便订阅仍生效）回合/联网立即回租户默认，
        // status 不再误报积分；WALLET 短路不触达订阅查询
        stubLimits(null);
        when(creditService.consumeMode(TENANT)).thenReturn("WALLET");
        AssistantUserQuotaService.BillingAwareLimits limits = service.billingAwareLimits(TENANT, USER);
        assertThat(limits.creditsMode()).isFalse();
        assertThat(limits.roundLimit()).isEqualTo(50);
        assertThat(limits.websearchLimit()).isEqualTo(20);
        assertThat(limits.planCode()).isNull();
    }

    @Test
    void 订阅但回合急停配0_刹车不被套餐绕过() {
        // glm53 P1-1/dspro P0-2 回归锁：租户默认回合 0=紧急刹车，订阅也不放行
        when(configService.effective(TENANT)).thenReturn(tenantDefault(0));
        when(quotaMapper.selectOne(any())).thenReturn(null);
        when(creditService.consumeMode(TENANT)).thenReturn("CREDIT");
        when(creditService.activeSubscription(TENANT)).thenReturn(java.util.Optional.of(sub(210001L)));
        when(creditService.subscriberWebsearchLimit(TENANT)).thenReturn(java.util.Optional.of(50));
        AssistantUserQuotaService.BillingAwareLimits limits = service.billingAwareLimits(TENANT, USER);
        assertThat(limits.creditsMode()).isTrue();
        assertThat(limits.roundLimit()).isZero();
        assertThat(limits.websearchLimit()).isEqualTo(50);
    }

    @Test
    void 积分服务闪断_fail_open降级钱包模式() {
        // dsflash P2-6：consumeMode/订阅查询抛异常时按钱包模式放行，不把 status/resolver 带崩
        stubLimits(null);
        when(creditService.consumeMode(TENANT)).thenThrow(new RuntimeException("db down"));
        AssistantUserQuotaService.BillingAwareLimits limits = service.billingAwareLimits(TENANT, USER);
        assertThat(limits.creditsMode()).isFalse();
        assertThat(limits.roundLimit()).isEqualTo(50);
        assertThat(limits.websearchLimit()).isEqualTo(20);
    }

    @Test
    void 订阅但套餐档位未配_联网回落租户默认_关停0仍优先() {
        stubLimits(null);
        when(creditService.consumeMode(TENANT)).thenReturn("CREDIT");
        when(creditService.activeSubscription(TENANT)).thenReturn(java.util.Optional.of(sub(210001L)));
        when(creditService.subscriberWebsearchLimit(TENANT)).thenReturn(java.util.Optional.empty());
        assertThat(service.billingAwareLimits(TENANT, USER).websearchLimit()).isEqualTo(20);
        // 租户联网 0=关停：订阅无权打开
        when(configService.effective(TENANT)).thenReturn(tenantDefault(50, 0));
        assertThat(service.billingAwareLimits(TENANT, USER).websearchLimit()).isZero();
    }
}
