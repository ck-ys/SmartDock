package com.smartdock.analytics.engine;

import com.smartdock.profile.event.ProfileEventBo;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 数据接入页事件工厂（2026-10-22 拍板·全按推荐）：纯函数、无 Spring 依赖，
 * 供 {@link AnalyticsIntakeService} 使用；单测直测本类（@Tag("local")）。
 *
 * @author smartdock
 */
public final class IntakeEventFactory {

    private IntakeEventFactory() {
    }

    /** 模拟/检测事件的 eventUid 统一前缀——「清掉模拟数据」按它整批删除 */
    public static final String SIM_UID_PREFIX = "sim-";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 模拟访客会走的演示路径（入口/去向共用） */
    static final String[] SIM_PAGES = {"/", "/pricing", "/docs", "/blog/launch", "/about"};

    /** sd_ + 32 位十六进制随机；site_key 全局唯一约束的碰撞由调用方重试兜底 */
    public static String generateSiteKey(Random random) {
        StringBuilder sb = new StringBuilder("sd_");
        for (int i = 0; i < 32; i++) {
            sb.append(HEX[random.nextInt(16)]);
        }
        return sb.toString();
    }

    /**
     * 模拟 N 个访客：每人一个会话，1-3 个 page_view（首屏为入口页），约三分之一再点一次按钮
     * （制造非跳出会话素材）。全部带 sim- uid 前缀与 props.simulated=true，可一键清理。
     */
    public static List<ProfileEventBo> buildSimulatedEvents(int visitors, String source, Date now, Random random) {
        List<ProfileEventBo> events = new ArrayList<>();
        for (int v = 1; v <= visitors; v++) {
            String visitor = "sim-visitor-" + Long.toHexString(random.nextLong() & Long.MAX_VALUE);
            String session = "sim-session-" + v + "-" + Long.toHexString(random.nextLong() & Long.MAX_VALUE);
            String entry = SIM_PAGES[random.nextInt(SIM_PAGES.length)];
            int pages = 1 + random.nextInt(3);
            List<ProfileEventBo> visitorEvents = new ArrayList<>();
            for (int p = 0; p < pages; p++) {
                String page = p == 0 ? entry : SIM_PAGES[random.nextInt(SIM_PAGES.length)];
                ProfileEventBo event = base(source, visitor, session, random);
                event.setEventName("page_view");
                event.setEventType("page_view");
                event.getContext().put("pageUrl", page);
                event.getProps().put("pageUrl", page);
                event.getContext().put("title", "模拟演示页");
                visitorEvents.add(event);
            }
            if (random.nextInt(3) == 0) {
                ProfileEventBo event = base(source, visitor, session, random);
                event.setEventName("cta_click");
                event.setEventType("cta_click");
                event.getContext().put("pageUrl", entry);
                event.getProps().put("pageUrl", entry);
                event.getProps().put("button", "demo");
                visitorEvents.add(event);
            }
            // 时间从当前时刻向前回推（最后一条落在 now 前半秒）：实时看板按 occurred_time < now 过滤，
            // 事件不能带未来时间戳，否则点完模拟切过去数字不动
            for (int i = 0; i < visitorEvents.size(); i++) {
                int back = visitorEvents.size() - 1 - i;
                visitorEvents.get(i).setOccurredTime(new Date(now.getTime() - back * (2_000L + random.nextInt(3_000)) - 500L));
            }
            events.addAll(visitorEvents);
        }
        return events;
    }

    /** 检测接入的探针事件：uid 同带 sim- 前缀（随模拟数据一起可清），事件名独立不掺进 PV 口径 */
    public static ProfileEventBo buildDetectEvent(String source, String uid) {
        ProfileEventBo event = new ProfileEventBo();
        event.setSource(source);
        event.setEventName("intake_detect");
        event.setEventType("intake_detect");
        event.setEventUid(uid);
        event.setSubjectType("visitor");
        event.setSubjectId("sim-detect-probe");
        event.setAnonymousId("sim-detect-probe");
        event.setOccurredTime(new Date());
        event.getProps().put("detect", true);
        // 同带模拟标记：即用即删万一漏网，「清掉模拟数据」按此标记兜底扫走
        event.getProps().put("simulated", true);
        event.getContext().put("pageUrl", "/intake-detect");
        event.getContext().put("device", "PC");
        return event;
    }

    private static ProfileEventBo base(String source, String visitor, String session, Random random) {
        ProfileEventBo event = new ProfileEventBo();
        event.setSource(source);
        event.setEventUid(SIM_UID_PREFIX + "demo-" + Long.toHexString(random.nextLong() & Long.MAX_VALUE)
            + Long.toHexString(random.nextLong() & Long.MAX_VALUE));
        event.setSubjectType("visitor");
        event.setSubjectId(visitor);
        event.setAnonymousId(visitor);
        event.setSessionId(session);
        event.getProps().put("simulated", true);
        event.getContext().put("device", "PC");
        event.getContext().put("referrer", "https://demo.smartdock.local/");
        event.getContext().put("userAgent", "SmartdockIntake/1.0 (simulate)");
        return event;
    }

    /** 会话键→事件数分组（清理/对账用不上，单测断言会话结构用） */
    public static Map<String, Integer> countBySession(List<ProfileEventBo> events) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (ProfileEventBo event : events) {
            out.merge(event.getSessionId(), 1, Integer::sum);
        }
        return out;
    }
}
