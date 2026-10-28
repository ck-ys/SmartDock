package com.smartdock.profile.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdock.analytics.engine.AnalyticsRollupTrigger;
import com.smartdock.analytics.mapper.AnalyticsVisitorFirstSeenMapper;
import com.smartdock.profile.engine.ProfileEngineConfigService;
import com.smartdock.profile.engine.ProfileRecalcTrigger;
import com.smartdock.profile.identity.ProfileIdentityService;
import com.smartdock.profile.mapper.ProfileEventMapper;
import com.smartdock.profile.protocol.ProfileSchemaService;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 单源口径契约测试(单源重构 M1 B 片): 发布咽喉必须把任意来源收口到
 * {@link ProfileSources#UNIFIED}, 且下游(身份解析/重算触发)看到的也是收口后的源;
 * source 传空不再拒收(兼容保留)。CSV 补录走 publishBatch, 与单条同一条 toEntity 路径。
 *
 * <p>纯单测基建(同 ProfileScoreUnconfiguredGuardTest 配方): JsonUtils 类初始化要
 * SpringUtils.getBean(ObjectMapper)——@BeforeAll 注入 mock 上下文 + 真 ObjectMapper;
 * 租户走 TenantHelper.setDynamic 线程 ThreadLocal。</p>
 *
 * @author smartdock
 */
@Tag("local")
class ProfileEventSourceUnificationTest {

    private ProfileEventMapper eventMapper;
    private ProfileEngineConfigService configService;
    private ProfileIdentityService identityService;
    private ProfileRecalcTrigger recalcTrigger;
    private com.smartdock.analytics.engine.AnalyticsRollupTrigger rollupTrigger;
    private com.smartdock.analytics.mapper.AnalyticsVisitorFirstSeenMapper firstSeenMapper;
    private ProfileEventServiceImpl service;

    @BeforeAll
    static void seedPureUnitTestInfrastructure() {
        org.springframework.context.ConfigurableApplicationContext ctx =
            Mockito.mock(org.springframework.context.ConfigurableApplicationContext.class);
        org.springframework.core.env.ConfigurableEnvironment env =
            Mockito.mock(org.springframework.core.env.ConfigurableEnvironment.class);
        Mockito.when(env.getProperty("tenant.enable")).thenReturn("true");
        Mockito.when(ctx.getEnvironment()).thenReturn(env);
        Mockito.when(ctx.getBean(org.springframework.core.env.Environment.class)).thenReturn(env);
        Mockito.when(ctx.getBean(ObjectMapper.class)).thenReturn(new ObjectMapper());
        new SpringUtils().setApplicationContext(ctx);
    }

    @BeforeEach
    void buildServiceWithMockedCollaborators() {
        eventMapper = Mockito.mock(ProfileEventMapper.class);
        configService = Mockito.mock(ProfileEngineConfigService.class);
        identityService = Mockito.mock(ProfileIdentityService.class);
        recalcTrigger = Mockito.mock(ProfileRecalcTrigger.class);
        ProfileSchemaService schemaService = Mockito.mock(ProfileSchemaService.class);
        rollupTrigger = Mockito.mock(AnalyticsRollupTrigger.class);
        firstSeenMapper = Mockito.mock(AnalyticsVisitorFirstSeenMapper.class);
        Mockito.when(schemaService.validate(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of());
        Mockito.when(identityService.resolveAndLink(any(), any(), any(), any(), any())).thenReturn("u-1");
        Mockito.when(eventMapper.insertIgnore(any(ProfileEvent.class))).thenReturn(1);

        Mockito.when(configService.selfMonitorEnabled(any(), any())).thenReturn(true);
        service = new ProfileEventServiceImpl(eventMapper, configService, schemaService, identityService,
            recalcTrigger, rollupTrigger, firstSeenMapper, noOpTransactionManager());
        TenantHelper.setDynamic("000000");
    }

    @AfterEach
    void unbindTenant() {
        TenantHelper.clearDynamic();
    }

    @Test
    void incomingSourceIsOverriddenAtChokePoint() {
        assertTrue(service.publish(bo("crm-portal")));

        ArgumentCaptor<ProfileEvent> captor = ArgumentCaptor.forClass(ProfileEvent.class);
        Mockito.verify(eventMapper).insertIgnore(captor.capture());
        assertEquals(ProfileSources.UNIFIED, captor.getValue().getSource());

        // 下游同源: 身份解析与重算触发看到的都是收口后的源, 不是调用方传入值
        // (verify 混用字面量与 matcher 是 Mockito 反模式, 全部走 eq/anyMap)
        Mockito.verify(identityService).resolveAndLink(eq("000000"), eq(ProfileSources.UNIFIED),
            eq("user"), eq("u-1"), anyMap());
        Mockito.verify(recalcTrigger).onEvent("000000", ProfileSources.UNIFIED, "user", "u-1");
        // 三审补钉: publish 的正向触发断言(此前只有 quiet 的反向半边)
        Mockito.verify(rollupTrigger).onEvent(eq("000000"), eq(ProfileSources.UNIFIED), any());
    }

    @Test
    void blankSourceIsAcceptedAndUnified() {
        // 单源口径: source 兼容保留——不传也不再拒收
        assertTrue(service.publish(bo(null)));

        ArgumentCaptor<ProfileEvent> captor = ArgumentCaptor.forClass(ProfileEvent.class);
        Mockito.verify(eventMapper).insertIgnore(captor.capture());
        assertEquals(ProfileSources.UNIFIED, captor.getValue().getSource());
    }

    @Test
    void batchPublishRoutesThroughSameChokePoint() {
        // CSV 补录/管理端灌数都汇到 publishBatch——与单条同一条 toEntity 收口路径
        int written = service.publishBatch(List.of(bo("mall"), bo(null)));
        assertEquals(2, written);

        ArgumentCaptor<ProfileEvent> captor = ArgumentCaptor.forClass(ProfileEvent.class);
        Mockito.verify(eventMapper, Mockito.times(2)).insertIgnore(captor.capture());
        captor.getAllValues().forEach(e -> assertEquals(ProfileSources.UNIFIED, e.getSource()));
    }

    @Test
    void subjectTypeIsNormalizedAtWriteBoundary() {
        // B 片三审回修: 入库前归一(subjectType 缺省 user/去空白)——" user " 这类影子命名空间
        // 会让配置按 user 命中而快照/复核挂在另一个键上
        ProfileEventBo bo = bo("dcs");
        bo.setSubjectType(" user ");
        assertTrue(service.publish(bo));

        ArgumentCaptor<ProfileEvent> captor = ArgumentCaptor.forClass(ProfileEvent.class);
        Mockito.verify(eventMapper).insertIgnore(captor.capture());
        assertEquals("user", captor.getValue().getSubjectType());
    }

    @Test
    void quietPublishWritesLedgerWithoutFiringTriggers() {
        // E 片三席回修: 静默发布写账本 + 触发分析汇总(自监控事件已进会话口径,
        // 不触发则首屏 KPI 一半实时一半滞后到次日); 分数重算仍然安静(登录不刷分)
        assertTrue(service.publishQuiet(bo("dcs")));

        Mockito.verify(eventMapper).insertIgnore(any(ProfileEvent.class));
        Mockito.verify(recalcTrigger, Mockito.never()).onEvent(any(), any(), any(), any());
        Mockito.verify(rollupTrigger).onEvent(any(), any(), any());
    }

    @Test
    void quietSelfMonitorEventsBlockedWhenSwitchOff() {
        // 三审回修: 自监控总开关收敛到发布咽喉——停用后登录/站内核心操作任何入口都不再新增
        Mockito.when(configService.selfMonitorEnabled(any(), any())).thenReturn(false);

        org.junit.jupiter.api.Assertions.assertFalse(service.publishQuiet(named("user.login", "dcs")));
        org.junit.jupiter.api.Assertions.assertFalse(service.publishQuiet(named("task.published", "dcs")));
        Mockito.verify(eventMapper, Mockito.never()).insertIgnore(any(ProfileEvent.class));

        // 开关只闸自监控名单: 普通业务事件静默发布不受影响
        org.junit.jupiter.api.Assertions.assertTrue(service.publishQuiet(named("order.paid", "crm")));
        Mockito.verify(eventMapper, Mockito.times(1)).insertIgnore(any(ProfileEvent.class));
    }

    @Test
    void selfMonitorEventsDoNotEnterVisitorFirstSeen() {
        // E 片口径反转(题2 统一事件流): 登录/站内操作就是「使用」——首见=首次使用,
        // 持续使用分析/首周激活都以它为分母; C 片"防访客污染"的跳过逻辑随访客指标退役
        service.publishQuiet(named("user.login", "dcs"));
        Mockito.verify(firstSeenMapper, Mockito.atLeastOnce()).upsert(any(), any(), any(), any());

        // 普通业务事件照常登记首见
        service.publish(bo("web"));
        Mockito.verify(firstSeenMapper, Mockito.atLeast(2)).upsert(any(), any(), any(), any());
    }

    @Test
    void unifiedValueIsPinnedToExistingData() {
        // 取值锚定存量数据所在源——改动等于把新旧事件拆成两套, 必须连同数据迁移一起评审
        assertEquals("dcs", ProfileSources.UNIFIED);
    }

    private ProfileEventBo named(String eventName, String source) {
        ProfileEventBo bo = bo(source);
        bo.setEventName(eventName);
        bo.setEventUid("m1named:" + eventName + ":" + source);
        return bo;
    }

    private ProfileEventBo bo(String source) {
        ProfileEventBo bo = new ProfileEventBo();
        bo.setSource(source);
        bo.setEventName("order.paid");
        bo.setEventUid("m1src:" + source + ":1");
        bo.setSubjectId("u-1");
        return bo;
    }

    private PlatformTransactionManager noOpTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
