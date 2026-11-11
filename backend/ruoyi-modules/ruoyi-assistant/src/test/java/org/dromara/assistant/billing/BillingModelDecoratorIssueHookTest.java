package org.dromara.assistant.billing;

import io.agentscope.core.model.Model;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.exception.ServiceException;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.service.IDcsAiModelPriceService;
import com.smartdock.service.support.AiBillingService;
import com.smartdock.service.support.TenantWalletService;
import org.dromara.assistant.service.AssistantConversationService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-11-11 拍板 Q2-2 补丁单测：模型失败侧落「这轮为什么没回答」问题行。
 *
 * <p>活体实证背景（SSE 抓帧）：AgentScope 2.1.0-BETA1 的 ReActAgent 把模型错误吞成
 * 无终态收流，RUN_ERROR 帧对服务商故障永远不出——装饰器 doOnError 是唯一可靠捕获点。
 * 钉四件事：①重试耗尽包装下剥到根因判码（401→auth）；②suppressed 里的 429 也要认
 * （rate）；③侧车（threadKey null）与无在途 run 不落；④问题链路自身炸了不连坐主流
 * 错误透传。</p>
 */
@Tag("dev")
@Tag("local")
@ExtendWith(MockitoExtension.class)
class BillingModelDecoratorIssueHookTest {

    @Mock
    private Model delegate;
    @Mock
    private AiBillingService billingService;
    @Mock
    private TenantWalletService walletService;
    @Mock
    private IDcsAiModelPriceService priceService;
    @Mock
    private AssistantConversationService conversationService;

    @BeforeAll
    static void seedSpringContextForRedisUtils() {
        ConfigurableApplicationContext ctx = mock(ConfigurableApplicationContext.class);
        ConfigurableListableBeanFactory bf = mock(ConfigurableListableBeanFactory.class);
        RedissonClient client = mock(RedissonClient.class);
        when(ctx.getBean(RedissonClient.class)).thenReturn(client);
        when(ctx.getBeanFactory()).thenReturn(bf);
        when(bf.getBean(RedissonClient.class)).thenReturn(client);
        new SpringUtils().setApplicationContext(ctx);
    }

    private static DcsAiConfig freeTierConfig() {
        DcsAiConfig config = new DcsAiConfig();
        config.setTenantId("000000");
        config.setUnitPricePerCall(BigDecimal.ZERO);
        return config;
    }

    private BillingModelDecorator decorator(String threadKey) {
        return new BillingModelDecorator(delegate, freeTierConfig(), 42L,
            billingService, walletService, priceService, new AssistantCostLedger(), threadKey)
            .issueRecorder(conversationService);
    }

    @Test
    void 重试耗尽包装_剥到根因判码落问题行() {
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.error(
            new IllegalStateException("Retries exhausted: 2/2",
                new RuntimeException("HTTP transport error: 401 Unauthorized: Incorrect API key"))));
        when(conversationService.latestRunningRunIdOfThread("000000", 42L, "th-i1")).thenReturn("run-i1");

        AtomicReference<Throwable> err = new AtomicReference<>();
        decorator("th-i1").stream(List.of(), List.of(), null).subscribe(v -> { }, err::set);

        // 判码用的是映射前的原始链（401 在根因里），不是映射后人话
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(conversationService).recordRunIssueForRunningRun(eq("run-i1"), eq("auth"), detail.capture());
        assertThat(detail.getValue()).contains("401").contains("Unauthorized");
        // 主流错误照常透传（重试耗尽映射的人话），捕获链不吞不错
        assertThat(err.get()).isInstanceOf(ServiceException.class);
    }

    @Test
    void suppressed里的429_判rate不判quota() {
        IllegalStateException outer = new IllegalStateException("Retries exhausted: 2/2",
            new RuntimeException("SSE/NDJSON stream failed"));
        outer.addSuppressed(new RuntimeException("429 Too Many Requests"));
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.error(outer));
        when(conversationService.latestRunningRunIdOfThread("000000", 42L, "th-i2")).thenReturn("run-i2");

        decorator("th-i2").stream(List.of(), List.of(), null).subscribe(v -> { }, e -> { });

        verify(conversationService).recordRunIssueForRunningRun(eq("run-i2"), eq("rate"), anyString());
    }

    @Test
    void 侧车不落问题行() {
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.error(new RuntimeException("boom")));

        // 侧车构造：threadKey=null（七参重载）——即使注入了服务也不落
        new BillingModelDecorator(delegate, freeTierConfig(), 42L,
            billingService, walletService, priceService, new AssistantCostLedger())
            .issueRecorder(conversationService)
            .stream(List.of(), List.of(), null).subscribe(v -> { }, e -> { });

        verify(conversationService, never()).recordRunIssueForRunningRun(anyString(), anyString(), anyString());
    }

    @Test
    void 无在途run不落_服务抛异常不连坐() {
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.error(new RuntimeException("connect timed out")));
        // 场景 A：thread 无 RUNNING run（终态已写/未登记）→ 静默跳过
        when(conversationService.latestRunningRunIdOfThread("000000", 42L, "th-i3")).thenReturn(null);

        decorator("th-i3").stream(List.of(), List.of(), null).subscribe(v -> { }, e -> { });
        verify(conversationService, never()).recordRunIssueForRunningRun(anyString(), anyString(), anyString());

        // 场景 B：服务自身抛异常 → 吞掉 WARN，主流错误照常到达订阅者
        when(conversationService.latestRunningRunIdOfThread("000000", 42L, "th-i4"))
            .thenThrow(new RuntimeException("pg down"));
        AtomicReference<Throwable> err = new AtomicReference<>();
        decorator("th-i4").stream(List.of(), List.of(), null).subscribe(v -> { }, err::set);
        assertThat(err.get()).hasMessageContaining("connect timed out");
    }
}
