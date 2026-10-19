package com.smartdock.analytics.engine;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdock.analytics.domain.AnalyticsSession;
import com.smartdock.analytics.mapper.AnalyticsEventMapper;
import com.smartdock.analytics.mapper.AnalyticsSessionMapper;
import com.smartdock.analytics.query.AnalyticsEventRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事件 → 会话：外部 sessionId 优先，否则按访问者 30 分钟无操作切分。
 *
 * <p>纯 Java 计算，不依赖 MQ；全量重建按时间窗删除后重写，保证幂等。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsSessionizer {

    private static final long SESSION_GAP_MS = 30 * 60 * 1000L;
    private static final Set<String> PAGE_EVENTS = Set.of("page_view", "pageview", "pv");
    /** 纯函数不依赖 Spring 容器，测试可直接调用 build() */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnalyticsSessionMapper sessionMapper;
    private final AnalyticsEventMapper eventMapper;

    public int rebuild(String tenantId, String source, Date from, Date to) {
        sessionMapper.deleteRange(tenantId, source, from, to);
        List<AnalyticsEventRow> events = eventMapper.selectEvents(tenantId, source, from, to);
        if (events.isEmpty()) {
            return 0;
        }
        Map<String, Date> firstSeen = new HashMap<>();
        for (Map<String, Object> row : eventMapper.selectVisitorFirstSeen(tenantId, source)) {
            Object key = row.get("visitorKey");
            Object first = row.get("firstSeen");
            if (key != null && first instanceof Date date) {
                firstSeen.put(String.valueOf(key), date);
            }
        }
        List<AnalyticsSession> sessions = build(tenantId, source, events, firstSeen);
        for (AnalyticsSession session : sessions) {
            sessionMapper.insert(session);
        }
        log.info("[Analytics] sessions rebuilt source={} range={}~{} count={}",
            source, from, to, sessions.size());
        return sessions.size();
    }

    /**
     * 纯函数：事件列表 → 会话列表。测试直接调用。
     */
    public static List<AnalyticsSession> build(String tenantId, String source,
                                               List<AnalyticsEventRow> events,
                                               Map<String, Date> firstSeen) {
        List<AnalyticsEventRow> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(AnalyticsEventRow::getOccurredTime)
            .thenComparing(AnalyticsEventRow::getEventId));

        Map<String, List<AnalyticsEventRow>> groups = new LinkedHashMap<>();
        Map<String, Date> lastImplicit = new HashMap<>();
        for (AnalyticsEventRow event : sorted) {
            if (event.getOccurredTime() == null) {
                continue;
            }
            String visitor = visitorKey(event);
            String sessionKey;
            if (StringUtils.isNotBlank(event.getSessionId())) {
                sessionKey = "s:" + event.getSessionId().trim();
            } else {
                Date last = lastImplicit.get(visitor);
                if (last == null || event.getOccurredTime().getTime() - last.getTime() > SESSION_GAP_MS) {
                    sessionKey = "v:" + visitor + ":" + (event.getOccurredTime().getTime() / 60000L);
                } else {
                    sessionKey = "v:" + visitor + ":" + (last.getTime() / 60000L);
                }
                lastImplicit.put(visitor, event.getOccurredTime());
            }
            groups.computeIfAbsent(sessionKey, key -> new ArrayList<>()).add(event);
        }

        List<AnalyticsSession> out = new ArrayList<>(groups.size());
        for (Map.Entry<String, List<AnalyticsEventRow>> entry : groups.entrySet()) {
            List<AnalyticsEventRow> list = entry.getValue();
            list.sort(Comparator.comparing(AnalyticsEventRow::getOccurredTime));
            AnalyticsSession session = buildOne(tenantId, source, entry.getKey(), list, firstSeen);
            if (session != null) {
                out.add(session);
            }
        }
        return out;
    }

    private static AnalyticsSession buildOne(String tenantId, String source, String sessionKey,
                                             List<AnalyticsEventRow> events, Map<String, Date> firstSeen) {
        if (events.isEmpty()) {
            return null;
        }
        AnalyticsEventRow first = events.get(0);
        AnalyticsEventRow last = events.get(events.size() - 1);
        Date startedAt = first.getOccurredTime();
        Date endedAt = last.getOccurredTime();

        String visitor = resolveVisitor(events);
        String subjectType = null;
        String subjectId = null;
        String anonymousId = null;
        String entryPage = null;
        String exitPage = null;
        String referrer = null;
        String channel = null;
        String campaign = null;
        String device = null;
        String os = null;
        String browser = null;
        String country = null;
        String province = null;
        String city = null;
        String searchTerm = null;
        int pageViews = 0;

        for (AnalyticsEventRow event : events) {
            Map<String, Object> context = parse(event.getContext());
            Map<String, Object> props = parse(event.getProps());
            String page = pageUrl(context, props);
            if (page != null) {
                if (entryPage == null) {
                    entryPage = page;
                }
                exitPage = page;
            }
            if (PAGE_EVENTS.contains(String.valueOf(event.getEventName()))) {
                pageViews++;
            }
            if (anonymousId == null && StringUtils.isNotBlank(event.getAnonymousId())) {
                anonymousId = event.getAnonymousId();
            }
            if ("user".equals(event.getSubjectType()) && StringUtils.isNotBlank(event.getSubjectId())) {
                subjectType = "user";
                subjectId = event.getSubjectId();
            }
            if (referrer == null) {
                referrer = firstString(context, "referrer", "referer");
            }
            if (channel == null) {
                channel = firstString(context, "sourceChannel", "channel", "utmSource");
                if (channel == null && context.get("utm") instanceof Map<?, ?> utm) {
                    Object value = utm.get("source");
                    channel = value == null ? null : String.valueOf(value);
                }
            }
            if (campaign == null) {
                campaign = firstString(context, "campaign", "utmCampaign");
                if (campaign == null && context.get("utm") instanceof Map<?, ?> utm) {
                    Object value = utm.get("campaign");
                    campaign = value == null ? null : String.valueOf(value);
                }
            }
            if (device == null) {
                device = firstString(context, "deviceType", "device");
            }
            if (os == null) {
                os = firstString(context, "os", "osName");
            }
            if (browser == null) {
                browser = firstString(context, "browser", "browserName");
            }
            if (country == null || province == null || city == null) {
                Map<String, Object> geo = context.get("geo") instanceof Map<?, ?> m
                    ? castMap(m) : Map.of();
                if (country == null) {
                    country = firstString(geo, "country");
                    if (country == null) {
                        country = firstString(context, "country");
                    }
                }
                if (province == null) {
                    province = firstString(geo, "province", "region");
                    if (province == null) {
                        province = firstString(context, "province", "region");
                    }
                }
                if (city == null) {
                    city = firstString(geo, "city");
                    if (city == null) {
                        city = firstString(context, "city");
                    }
                }
            }
            if (searchTerm == null) {
                searchTerm = firstString(context, "searchTerm", "keyword");
                if (searchTerm == null) {
                    searchTerm = firstString(props, "keyword", "searchTerm");
                }
            }
        }
        if (channel == null) {
            channel = hostOf(referrer);
        }
        if (channel == null) {
            channel = "direct";
        }

        AnalyticsSession session = new AnalyticsSession();
        session.setSessionId(IdWorker.getId());
        session.setTenantId(tenantId);
        session.setSource(source);
        session.setSessionKey(sessionKey);
        session.setVisitorId(visitor);
        session.setSubjectType(subjectType);
        session.setSubjectId(subjectId);
        session.setAnonymousId(anonymousId);
        Date firstVisit = earliestFirstSeen(events, firstSeen);
        session.setIsNewVisitor(firstVisit != null && firstVisit.getTime() >= startOfDay(startedAt) ? "1" : "0");
        session.setStartedAt(startedAt);
        session.setEndedAt(endedAt);
        session.setLastEventAt(endedAt);
        session.setDurationSeconds((int) Math.min(1800, Math.max(0,
            (endedAt.getTime() - startedAt.getTime()) / 1000L)));
        session.setPageViews(pageViews);
        session.setEventCount(events.size());
        // 跳出=只浏览了一个页面且没有搜索/注册等交互；page_leave 是收尾事件，不算交互
        boolean interacted = events.stream().anyMatch(e ->
            !PAGE_EVENTS.contains(String.valueOf(e.getEventName()))
                && !"page_leave".equals(String.valueOf(e.getEventName())));
        session.setIsBounce(pageViews <= 1 && !interacted ? "1" : "0");
        session.setEntryPage(entryPage);
        session.setExitPage(exitPage);
        session.setReferrer(referrer);
        session.setSourceChannel(channel);
        session.setCampaign(campaign);
        session.setDeviceType(device);
        session.setOs(os);
        session.setBrowser(browser);
        session.setCountry(country);
        session.setProvince(province);
        session.setCity(city);
        session.setSearchTerm(searchTerm);
        return session;
    }

    private static String resolveVisitor(List<AnalyticsEventRow> events) {
        String fallback = null;
        for (AnalyticsEventRow event : events) {
            String visitor = visitorKey(event);
            if (fallback == null) {
                fallback = visitor;
            }
            if ("user".equals(event.getSubjectType()) && StringUtils.isNotBlank(event.getSubjectId())
                && !event.getSubjectId().equals(event.getAnonymousId())) {
                return event.getSubjectId();
            }
        }
        return fallback == null ? "unknown" : fallback;
    }

    private static String visitorKey(AnalyticsEventRow event) {
        if (StringUtils.isNotBlank(event.getVisitorKey())) {
            return event.getVisitorKey();
        }
        if (StringUtils.isNotBlank(event.getAnonymousId())) {
            return event.getAnonymousId();
        }
        return StringUtils.isBlank(event.getSubjectId()) ? "unknown" : event.getSubjectId();
    }

    private static Date earliestFirstSeen(List<AnalyticsEventRow> events, Map<String, Date> firstSeen) {
        Date min = null;
        Set<String> keys = new HashSet<>();
        for (AnalyticsEventRow event : events) {
            keys.add(visitorKey(event));
            if (StringUtils.isNotBlank(event.getSubjectId())) {
                keys.add(event.getSubjectId());
            }
        }
        for (String key : keys) {
            Date date = firstSeen.get(key);
            if (date != null && (min == null || date.before(min))) {
                min = date;
            }
        }
        return min;
    }

    private static long startOfDay(Date date) {
        LocalDate day = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String pageUrl(Map<String, Object> context, Map<String, Object> props) {
        String page = firstString(context, "pageUrl", "url", "path");
        return page != null ? page : firstString(props, "pageUrl", "url", "path");
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Map<?, ?> nested) {
                String text = firstString(castMap(nested), "name", "type", "value");
                if (text != null) {
                    return text;
                }
            }
            if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> parse(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String hostOf(String referrer) {
        if (StringUtils.isBlank(referrer)) {
            return null;
        }
        String host;
        try {
            host = URI.create(referrer).getHost();
            if (StringUtils.isBlank(host)) {
                host = referrer;
            }
        } catch (Exception e) {
            host = referrer;
        }
        String lower = host.toLowerCase();
        if (lower.startsWith("www.")) {
            lower = lower.substring(4);
        }
        if (lower.contains("baidu.")) return "baidu";
        if (lower.contains("google.")) return "google";
        if (lower.contains("weibo.")) return "weibo";
        if (lower.contains("weixin") || lower.contains("wechat") || lower.contains("qq.com")) return "wechat";
        if (lower.contains("bing.")) return "bing";
        if (lower.contains("zhihu.")) return "zhihu";
        if (lower.contains("douyin") || lower.contains("tiktok")) return "douyin";
        if (lower.contains("toutiao")) return "toutiao";
        return lower;
    }
}
