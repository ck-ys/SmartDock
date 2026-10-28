package com.smartdock.profile.monitor;

import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.domain.ProfileEngineConfig;
import com.smartdock.profile.engine.ProfileEngineConfigService;
import com.smartdock.profile.event.ProfileEventBo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.utils.SpringUtils;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import org.dromara.common.core.constant.Constants;
import org.dromara.common.log.event.LogininforEvent;
import org.dromara.common.tenant.helper.TenantHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自监控监听器契约测试(单源重构 C 片):
 * 登录成功→静默事件(user.login, 主体钉 user_id)/失败态不发/开关停不发/查无用户不发;
 * 白名单事件名钉死(E 片采用度与两步漏斗的取数口径, 改名=改指标)。
 *
 * @author smartdock
 */
@Tag("local")
class ProfileSelfMonitorListenerTest {

    private ProfileEventPublisher eventPublisher;
    private ProfileEngineConfigService configService;
    private ProfileSubjectMapper subjectMapper;
    private ProfileSelfMonitorListener listener;

    @BeforeAll
    static void seedPureUnitTestInfrastructure() {
        // TenantHelper.dynamic/setDynamic 要 tenant.enable 上下文才生效(无 Spring 环境下静默空转)
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
    void build() {
        eventPublisher = Mockito.mock(ProfileEventPublisher.class);
        configService = Mockito.mock(ProfileEngineConfigService.class);
        subjectMapper = Mockito.mock(ProfileSubjectMapper.class);
        listener = new ProfileSelfMonitorListener(eventPublisher, configService, subjectMapper);
        TenantHelper.setDynamic("000000");
        selfMonitorEnabled("1");
    }

    @AfterEach
    void unbind() {
        TenantHelper.clearDynamic();
    }

    private void selfMonitorEnabled(String flag) {
        when(configService.selfMonitorEnabled(anyString(), anyString())).thenReturn("1".equals(flag));
    }

    @Test
    void successfulLoginPublishesQuietEventWithUserId() {
        when(subjectMapper.selectUserIdByName("000000", "zhangsan"))
            .thenReturn(Map.of("userId", 9100000001L, "nickName", "张三"));

        listener.onLogin(login(Constants.LOGIN_SUCCESS, "zhangsan"));

        verify(eventPublisher).publishQuiet(argThat(bo -> bo != null
            && SelfMonitorEvents.LOGIN.equals(bo.getEventName())
            && "user".equals(bo.getSubjectType())
            && "9100000001".equals(bo.getSubjectId())
            && "张三".equals(bo.getSubjectName())
            && bo.getEventUid() != null && bo.getEventUid().startsWith("selfmon.login:9100000001:")));
    }

    @Test
    void nonSuccessStatusNeverPublishes() {
        listener.onLogin(login("Error", "zhangsan"));
        verify(eventPublisher, never()).publishQuiet(any());
    }

    @Test
    void disabledSwitchNeverPublishes() {
        selfMonitorEnabled("0");
        when(subjectMapper.selectUserIdByName(anyString(), anyString()))
            .thenReturn(Map.of("userId", 1L, "nickName", "x"));
        listener.onLogin(login(Constants.LOGIN_SUCCESS, "zhangsan"));
        verify(eventPublisher, never()).publishQuiet(any());
    }

    @Test
    void unknownUserNeverPublishes() {
        when(subjectMapper.selectUserIdByName(anyString(), anyString())).thenReturn(null);
        listener.onLogin(login(Constants.LOGIN_SUCCESS, "ghost"));
        verify(eventPublisher, never()).publishQuiet(any());
    }

    @Test
    void whitelistIsPinnedForAdoptionMetrics() {
        // E 片「采用度/两步上手漏斗(登录→首次核心动作)」按此名单取数——增删必须连带评估分析侧
        assertEquals("user.login", SelfMonitorEvents.LOGIN);
        assertEquals(java.util.Set.of(
            "task.published", "data.submitted", "data.audit_passed", "data.audit_rejected", "task.closed"),
            SelfMonitorEvents.CORE_OPERATIONS);
        // 白名单里没有页面浏览类事件——自监控不记页面浏览是拍板口径
        assertTrue(SelfMonitorEvents.CORE_OPERATIONS.stream().noneMatch(
            name -> name.contains("page") || name.contains("view")));
    }

    private LogininforEvent login(String status, String username) {
        LogininforEvent event = new LogininforEvent();
        event.setTenantId("000000");
        event.setUsername(username);
        event.setStatus(status);
        return event;
    }
}
