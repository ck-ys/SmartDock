package com.smartdock.analytics;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分析读侧统一事件流钉(单源重构 E 片口径反转):
 * 六条核心读查询(会话重建/漏斗下钻/用户时间线/实时看板/窗口用户/日活)必须吃全量事件——
 * C 片的 NOT IN 自监控隔离随访客指标退役而反转: 登录/站内操作就是「使用」,
 * 再挡掉它们 = 会话/活跃/使用次数全为零(假话)。本测试钉住"不得再出现按事件名排除"的回归。
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
    void coreReadQueriesIncludeSelfMonitorEvents() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String name : QUERIES) {
            Method m = com.smartdock.analytics.mapper.AnalyticsEventMapper.class.getMethod(name, paramTypesOf(name));
            Select select = m.getAnnotation(Select.class);
            assertNotNull(select, name + " 缺少 @Select 注解?");
            String sql = String.join(" ", select.value());
            if (sql.contains("event_name NOT IN")) {
                violations.add(name + ": 仍按事件名排除(E 片起统一事件流, 不许再把自监控挡在读侧外)");
            }
        }
        assertTrue(violations.isEmpty(), "统一事件流回归: " + String.join("; ", violations));
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
