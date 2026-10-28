package com.smartdock.analytics;

import com.smartdock.profile.monitor.SelfMonitorEvents;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分析读侧自监控隔离钉(C 片三审 P0 回修的机制级防法):
 * 六条核心读查询必须把自监控事件(user.login + 站内五操作)挡在访客口径之外——
 * 会话重建/实时看板/窗口访客/DAU/访客时间线任何一条漏加 NOT IN,
 * 一次登录就会污染新访客数与演示基线六数字。白名单增删事件名时本测试会逼着 SQL 同步。
 *
 * @author smartdock
 */
@Tag("local")
class AnalyticsSelfMonitorIsolationTest {

    private static final String[] QUERIES = {
        "selectEvents", "selectEventsForVisitors", "selectVisitorEvents",
        "selectRealtimeBuckets", "countWindowVisitors", "selectDailyActiveVisitors"
    };

    @Test
    void coreReadQueriesExcludeSelfMonitorEvents() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String name : QUERIES) {
            Method m = com.smartdock.analytics.mapper.AnalyticsEventMapper.class.getMethod(name, paramTypesOf(name));
            Select select = m.getAnnotation(Select.class);
            assertNotNull(select, name + " 缺少 @Select 注解?");
            String sql = String.join(" ", select.value());
            if (!sql.contains("event_name NOT IN")) {
                missing.add(name + ": 缺 NOT IN 子句");
                continue;
            }
            // 名单里的每个事件名都必须真的出现在 SQL 字面量中
            List<String> all = new ArrayList<>(SelfMonitorEvents.CORE_OPERATIONS);
            all.add(SelfMonitorEvents.LOGIN);
            for (String event : all) {
                if (!sql.contains("'" + event + "'")) {
                    missing.add(name + ": SQL 名单缺事件 " + event);
                }
            }
        }
        assertTrue(missing.isEmpty(), "自监控隔离缺口: " + String.join("; ", missing));
    }

    /** 六个方法的参数类型(与 mapper 签名一致, 改签名时同步这里)。 */
    private static Class<?>[] paramTypesOf(String name) {
        return switch (name) {
            case "selectEvents" -> new Class<?>[]{String.class, String.class, java.util.Date.class, java.util.Date.class};
            case "selectEventsForVisitors" -> new Class<?>[]{String.class, String.class, java.util.Date.class,
                java.util.Date.class, String.class};
            case "selectVisitorEvents" -> new Class<?>[]{String.class, String.class, String.class,
                java.util.Date.class, java.util.Date.class};
            case "selectRealtimeBuckets", "countWindowVisitors" -> new Class<?>[]{String.class, String.class,
                java.util.Date.class, java.util.Date.class};
            case "selectDailyActiveVisitors" -> new Class<?>[]{String.class, String.class,
                java.util.Date.class, java.util.Date.class};
            default -> throw new IllegalArgumentException(name);
        };
    }
}
