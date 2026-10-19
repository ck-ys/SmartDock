package com.smartdock.risk;

import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.service.UserService;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.risk.controller.DcsRiskDashboardController;
import com.smartdock.risk.mapper.DcsRiskEventMapper;
import com.smartdock.risk.service.ProfileRiskReviewService;
import com.smartdock.risk.service.RiskActionService;
import com.smartdock.risk.service.RiskScoreService;
import com.smartdock.risk.support.RiskUserEnricher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 风控大盘 Controller 单测（B1 拆分后更新：原 DcsRiskController 已拆为 5 个子 Controller，
 * dashboard 现归属 {@link DcsRiskDashboardController}，构造参数也相应变化）。
 *
 * <p>验证点：dashboard(timeRange) 会把 timeRange 解析成 since，并以此 since 调 recentEvents(since, 20)。
 * 拆分前后此契约不变。
 *
 * @author smartdock
 */
@Tag("dev")
class DcsRiskDashboardUnitTest {

    @Test
    void dashboardUsesSameTimeRangeForRecentEvents() {
        DcsRiskEventMapper eventMapper = mock(DcsRiskEventMapper.class);
        when(eventMapper.eventTypeStats(any(Date.class))).thenReturn(List.of());
        when(eventMapper.userRiskLevelStats(any(Date.class))).thenReturn(List.of());
        when(eventMapper.recentEvents(any(Date.class), eq(20))).thenReturn(List.of());
        when(eventMapper.topRulesByCount(any(Date.class), eq(10))).thenReturn(List.of());
        when(eventMapper.selectCount(any())).thenReturn(0L);

        // 拆分后 dashboard 只注入自己用到的依赖，enricher 单独抽成 @Component；
        // 风控2.0 批1(2026-09-22)追加 DcsRolloutProperties(大盘防线状态直读开关), 真实实例走默认值；
        // 批4(2026-10-18) DcsRiskMissionStatMapper 退役——误报反馈计数并入 DcsWordFpFeedbackMapper.countAll()
        DcsRiskDashboardController controller = new DcsRiskDashboardController(
            eventMapper,
            mock(RiskScoreService.class),
            mock(RiskActionService.class),
            mock(ProfileRiskReviewService.class),
            new RiskUserEnricher(mock(UserService.class)),
            new com.smartdock.risk.config.DcsRolloutProperties(),
            mock(com.smartdock.risk.mapper.DcsWordFpFeedbackMapper.class),
            mock(PermissionService.class)
        );

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getTenantId).thenReturn("200001");
            login.when(LoginHelper::getUserId).thenReturn(1L);
            controller.dashboard("24h");
        }

        verify(eventMapper).recentEvents(any(Date.class), eq(20));
    }
}
