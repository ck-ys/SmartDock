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
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 小修包件5（2026-11-02 拍板「全按推荐」）：孤儿事件防线——业务事务里发事件时,
 * 账本写入推迟到外层事务 afterCommit; 外层回滚则什么都不写。
 * 行为锁：活动事务下 publish 立即返回 true 但不落账; 提交后落账一次; 回滚后永不落账。
 *
 * <p>三席复审(dsflash P2-4): 这里用桩事务管理器 + 手工 initSynchronization 锁的是
 * <b>分支逻辑</b>, 不能证明真实 Spring suspend/resume 语义——真实链路由 2026-11-02 活体
 * 探针覆盖(建草稿→发布走 @Transactional, 断言 profile_event 与 profile_subject.
 * last_event_time 同值落库, 见 .workshop/tmp/20261102-smallfix-aftercommit-probe.cjs)。</p>
 */
@Tag("local")
class ProfileEventAfterCommitDeferralTest {

    private ProfileEventMapper eventMapper;
    private ProfileEventServiceImpl service;
    private final java.util.concurrent.atomic.AtomicInteger txCount = new java.util.concurrent.atomic.AtomicInteger();

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
        ProfileEngineConfigService configService = Mockito.mock(ProfileEngineConfigService.class);
        ProfileIdentityService identityService = Mockito.mock(ProfileIdentityService.class);
        ProfileRecalcTrigger recalcTrigger = Mockito.mock(ProfileRecalcTrigger.class);
        ProfileSchemaService schemaService = Mockito.mock(ProfileSchemaService.class);
        AnalyticsRollupTrigger rollupTrigger = Mockito.mock(AnalyticsRollupTrigger.class);
        AnalyticsVisitorFirstSeenMapper firstSeenMapper = Mockito.mock(AnalyticsVisitorFirstSeenMapper.class);
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
    void unbind() {
        TenantHelper.clearDynamic();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void inTxPublishDefersLedgerWriteUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.publish(bo()));
            // 受理≠落账: 外层事务未提交, 账本一笔都不能有
            verify(eventMapper, never()).insertIgnore(any(ProfileEvent.class));

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(eventMapper).insertIgnore(any(ProfileEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rolledBackTxPublishesNothing() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.publish(bo()));
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            // 外层回滚: 事件不该进账本(此前的孤儿来源)
            verify(eventMapper, never()).insertIgnore(any(ProfileEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void quietPublishDefersToo() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.publishQuiet(bo()));
            verify(eventMapper, never()).insertIgnore(any(ProfileEvent.class));
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(eventMapper).insertIgnore(any(ProfileEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 三席回修(dspro P0): afterCommit 里旁路写若裸跑, 会落在"已提交但仍绑定"的外层连接上,
     * cleanup 时按脏连接回滚——账本成功、主体索引/首见静默丢失。
     * 行为锁: 提交后写 3 笔事务 = 账本 1 + 主体/首见各 1 独立短事务。
     */
    @Test
    void afterCommitSideWritesRunInTheirOwnTransactions() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.publish(bo());
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            assertEquals(3, txCount.get(), "账本 1 笔 + 主体/首见各 1 笔独立短事务");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 非事务上下文(CSV 补录/管理端灌数): 旁路写走自动提交, 不额外开事务——保持原写入成本 */
    @Test
    void directPublishKeepsSingleLedgerTransaction() {
        assertTrue(service.publish(bo()));
        verify(eventMapper).insertIgnore(any(ProfileEvent.class));
        assertEquals(1, txCount.get());
    }

    /**
     * 三席回修(dspro P1-1): 事件在调用线程定型。afterCommit 执行时 dynamic 租户 ThreadLocal
     * 已被 finally 清掉, 若延迟解析会把事件写进操作者自己的租户(跨租户污染)。
     */
    @Test
    void eventTenantIsFrozenAtPublishTimeNotAtCommitTime() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.publish(bo());
            TenantHelper.clearDynamic(); // 模拟 dynamic 包裹已退出, 外层事务此刻才提交
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            org.mockito.ArgumentCaptor<ProfileEvent> captor =
                org.mockito.ArgumentCaptor.forClass(ProfileEvent.class);
            verify(eventMapper).insertIgnore(captor.capture());
            assertEquals("000000", captor.getValue().getTenantId());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 契约: 事务内"受理"不代表跳过校验——坏事件必须在调用线程同步报出, 与直发路径一致 */
    @Test
    void invalidEventFailsSynchronouslyEvenInsideTx() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            ProfileEventBo bad = bo();
            bad.setEventUid(null);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.publish(bad));
            verify(eventMapper, never()).insertIgnore(any(ProfileEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ProfileEventBo bo() {
        ProfileEventBo bo = new ProfileEventBo();
        bo.setSource("dcs");
        bo.setEventName("order.paid");
        bo.setEventUid("smfix5:1");
        bo.setSubjectId("u-1");
        return bo;
    }

    private PlatformTransactionManager noOpTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                txCount.incrementAndGet();
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
