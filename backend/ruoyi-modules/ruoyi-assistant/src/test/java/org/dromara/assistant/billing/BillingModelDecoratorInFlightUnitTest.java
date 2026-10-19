package org.dromara.assistant.billing;

import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.dromara.common.core.utils.SpringUtils;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.service.IDcsAiModelPriceService;
import com.smartdock.service.support.AiBillingService;
import com.smartdock.service.support.TenantWalletService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ISSUE-001/004（2026-10-14）单元测试：模型调用在飞标记（收割判别键）全生命周期。
 *
 * <p>覆盖口径：①装配期同步抛出（PII/思考档等装饰器链）在飞先清零再上抛——原缺口下
 * doFinally 不建链、计数永久留 1，该 thread 的 60s 收割永久失明；②订阅在飞标记 +
 * 断连拆链（dispose=CANCEL）清零——收割器放行的前提。免费档（unitPrice=0）绕过钱包
 * 前置闸，纯 Mockito 不起 Spring；AssistantCostLedger 用真实实例（行为本身是被测物）。</p>
 *
 * <p>环境预埋：currentFloor() 读 RedisUtils（静态 CLIENT=SpringUtils.getBean），纯单测
 * 无 Spring 上下文时类初始化抛 Error 穿透既有 catch(Exception)——@BeforeAll 先注入
 * 假 ApplicationContext + mock Redisson，让静态初始化可完成（运行期调用结果不重要，
 * currentFloor 已有 null/异常兜底返回 ZERO）。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class BillingModelDecoratorInFlightUnitTest {

    @Mock
    private Model delegate;

    @Mock
    private AiBillingService billingService;

    @Mock
    private TenantWalletService walletService;

    @Mock
    private IDcsAiModelPriceService priceService;

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

    /** 免费档配置：估算 0 元跳过积分/钱包前置闸（免 stub 余额链） */
    private static DcsAiConfig freeTierConfig() {
        DcsAiConfig config = new DcsAiConfig();
        config.setTenantId("000000");
        config.setUnitPricePerCall(BigDecimal.ZERO);
        return config;
    }

    @Test
    void 装配期同步抛出_在飞清零再上抛_收割器不失能() {
        AssistantCostLedger ledger = new AssistantCostLedger();
        when(delegate.stream(any(), any(), any())).thenThrow(new RuntimeException("PII 装配炸了"));
        BillingModelDecorator decorator = new BillingModelDecorator(
            delegate, freeTierConfig(), 42L, billingService, walletService, priceService, ledger, "th-1");

        AtomicReference<Throwable> err = new AtomicReference<>();
        decorator.stream(List.of(), List.of(), null).subscribe(v -> { }, err::set);

        assertThat(err.get()).hasMessage("PII 装配炸了");
        assertThat(ledger.isInFlight("th-1")).as("装配期抛出后计数必须归零（doFinally 不建链的 Reactor 语义缺口）").isFalse();
    }

    @Test
    void 订阅时标记在飞_断连取消清零_收割放行() {
        AssistantCostLedger ledger = new AssistantCostLedger();
        Flux<ChatResponse> neverComplete = Flux.never();
        when(delegate.stream(any(), any(), any())).thenReturn(neverComplete);
        BillingModelDecorator decorator = new BillingModelDecorator(
            delegate, freeTierConfig(), 42L, billingService, walletService, priceService, ledger, "th-2");

        Disposable d = decorator.stream(List.of(), List.of(), null).subscribe();
        assertThat(ledger.isInFlight("th-2")).as("订阅期间模型调用在飞=收割豁免").isTrue();

        d.dispose();
        assertThat(ledger.isInFlight("th-2")).as("断连拆链（CANCEL）清零——60s 收割的放行前提").isFalse();
    }
}
