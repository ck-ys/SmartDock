package com.smartdock.analytics.engine;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.analytics.domain.AnalyticsCollectLog;
import com.smartdock.analytics.domain.AnalyticsSite;
import com.smartdock.analytics.mapper.AnalyticsCollectLogMapper;
import com.smartdock.analytics.mapper.AnalyticsSiteMapper;
import com.smartdock.analytics.query.AnalyticsCollectBo;
import com.smartdock.analytics.quality.BotDetector;
import com.smartdock.analytics.quality.CollectRequestContext;
import com.smartdock.analytics.quality.IpGeoResolver;
import com.smartdock.analytics.quality.OriginDomainMatcher;
import com.smartdock.analytics.quality.UserAgentParser;
import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.event.ProfileEventBo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 公开采集口：siteKey → 租户/业务源 → 事件账本。
 *
 * <p>批 7 采集质量管线（拍板 Q1-Q8 全 A）：机器人 UA 整批拒绝、来源域名白名单校验、
 * 服务端 UA/地域解析增强、eventUid 重复感知；拒绝与跳过按（批次 × 原因）留痕到
 * analytics_collect_log，进「采集质量」报表，不进统计。</p>
 *
 * <p>确定性拒绝（robot / domain_mismatch）返回 HTTP 200 + accepted=0 + dropped 原因，
 * SDK 识别后不进离线队列不重试，机器人请求变成哑请求；超大批次属操作契约违规，仍抛错。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsCollectorService {

    private static final int MAX_BATCH = 200;

    private final AnalyticsSiteMapper siteMapper;
    private final AnalyticsCollectLogMapper collectLogMapper;
    private final ProfileEventPublisher eventPublisher;

    public Map<String, Object> collect(AnalyticsCollectBo bo, CollectRequestContext ctx) {
        CollectRequestContext req = ctx == null ? CollectRequestContext.empty() : ctx;
        if (bo == null || StringUtils.isBlank(bo.getSiteKey())) {
            throw new ServiceException("siteKey 不能为空");
        }
        if (bo.getEvents() == null || bo.getEvents().isEmpty()) {
            return Map.of("received", 0, "accepted", 0);
        }
        if (bo.getEvents().size() > MAX_BATCH) {
            throw new ServiceException("单批最多 " + MAX_BATCH + " 条事件");
        }
        AnalyticsSite site = TenantHelper.ignore(() -> siteMapper.selectOne(
            new LambdaQueryWrapper<AnalyticsSite>()
                .eq(AnalyticsSite::getSiteKey, bo.getSiteKey().trim())
                .eq(AnalyticsSite::getEnabled, "1")
                .last("LIMIT 1")));
        if (site == null) {
            throw new ServiceException("siteKey 无效或站点已停用");
        }

        // 机器人: 整批拒绝 + 留痕(拍板 Q1-A/Q2-A)
        if (BotDetector.isBot(req.userAgent())) {
            recordDrop(site, "robot", bo.getEvents().size(), req,
                StringUtils.isBlank(req.userAgent()) ? "empty user-agent" : null);
            return dropped(bo, "robot");
        }
        // 来源域名白名单: 带 Origin/Referer 才校验, 缺失放行(拍板 Q5-A)
        String origin = StringUtils.isNotBlank(req.origin()) ? req.origin() : req.referer();
        if (!OriginDomainMatcher.matches(site.getAllowedDomains(), origin)) {
            recordDrop(site, "domain_mismatch", bo.getEvents().size(), req,
                OriginDomainMatcher.extractHost(origin));
            return dropped(bo, "domain_mismatch");
        }

        // 服务端 UA/地域解析优先, 客户端值兜底(拍板 Q8-A); 会话重建从 context 读这些字段
        UserAgentParser.UaInfo ua = UserAgentParser.parse(req.userAgent());
        String country = IpGeoResolver.resolve(req.clientIp());

        List<ProfileEventBo> events = new ArrayList<>();
        for (AnalyticsCollectBo.CollectEvent item : bo.getEvents()) {
            if (item == null || StringUtils.isBlank(item.getEventName())) {
                continue;
            }
            String anonymous = item.getAnonymousId();
            String subjectId = StringUtils.isNotBlank(item.getUserId()) ? item.getUserId() : anonymous;
            if (StringUtils.isBlank(subjectId)) {
                subjectId = "anon-" + UUID.randomUUID();
                anonymous = subjectId;
            }
            ProfileEventBo event = new ProfileEventBo();
            event.setSource(site.getSource());
            event.setEventName(item.getEventName().trim());
            event.setEventType(item.getEventName().trim());
            event.setEventUid(StringUtils.isBlank(item.getEventUid())
                ? "sdk-" + UUID.randomUUID() : item.getEventUid());
            event.setSubjectType(StringUtils.isNotBlank(item.getUserId()) ? "user" : "visitor");
            event.setSubjectId(subjectId);
            event.setAnonymousId(anonymous);
            event.setSessionId(item.getSessionId());
            event.setOccurredTime(item.getOccurredTime());
            event.setSubjectName(item.getSubjectName());
            event.setProps(item.getProps() == null ? new LinkedHashMap<>() : item.getProps());
            event.setContext(item.getContext() == null ? new LinkedHashMap<>() : item.getContext());
            enrichContext(event.getContext(), ua, country);
            // Schema 约束在 props 上：把采集上下文里的页面/关键词同步一份到业务属性
            // （不能用 String.valueOf(props.get(...))判空——null 会被变成长度4的"null"串，兜底永远不触发）
            copyContextToProps(event, "pageUrl", "pageUrl");
            copyContextToProps(event, "searchTerm", "keyword");
            events.add(event);
        }
        if (events.isEmpty()) {
            return Map.of("received", bo.getEvents().size(), "accepted", 0);
        }

        Map<String, Object> result = TenantHelper.dynamic(site.getTenantId(),
            () -> eventPublisher.publishBatchDetailed(events));
        int written = ((Number) result.getOrDefault("written", 0)).intValue();
        int duplicated = ((Number) result.getOrDefault("duplicated", 0)).intValue();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> failures = (List<Map<String, String>>) result.getOrDefault("failures", List.of());

        // 重复投递与逐条失败留痕(不抛错, 质量报表可见); recordDrop 自带租户切换
        if (duplicated > 0) {
            recordDrop(site, "duplicate", duplicated, req, null);
        }
        if (!failures.isEmpty()) {
            String detail = StringUtils.joinWith("; ", failures.stream()
                .map(f -> String.valueOf(f.get("reason")))
                .limit(3).toArray());
            recordDrop(site, "schema_invalid", failures.size(), req, detail);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", bo.getEvents().size());
        out.put("accepted", written);
        out.put("duplicated", duplicated);
        out.put("skipped", bo.getEvents().size() - written - duplicated);
        return out;
    }

    private void enrichContext(Map<String, Object> context, UserAgentParser.UaInfo ua, String country) {
        if (ua.resolved()) {
            if (!UserAgentParser.UNKNOWN.equals(ua.deviceType())) {
                context.put("deviceType", ua.deviceType());
            }
            if (!UserAgentParser.UNKNOWN.equals(ua.os())) {
                context.put("os", ua.os());
            }
            if (!UserAgentParser.UNKNOWN.equals(ua.browser())) {
                context.put("browser", ua.browser());
            }
        }
        if (country != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> geo = (Map<String, Object>) context.computeIfAbsent("geo",
                k -> new LinkedHashMap<String, Object>());
            geo.putIfAbsent("country", country);
        }
    }

    /**
     * context → props 兜底拷贝：props 已有同名非空值时不覆盖（客户端直报优先）。
     */
    private void copyContextToProps(ProfileEventBo event, String contextKey, String propsKey) {
        Object value = event.getContext().get(contextKey);
        Object existing = event.getProps().get(propsKey);
        if (value != null && (existing == null || String.valueOf(existing).isBlank())) {
            event.getProps().put(propsKey, value);
        }
    }

    private Map<String, Object> dropped(AnalyticsCollectBo bo, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", bo.getEvents().size());
        out.put("accepted", 0);
        out.put("dropped", reason);
        return out;
    }

    /**
     * 留痕一行 = 一批一种原因; 明文 IP 不落库, 只存 SHA-256 前 16 位(拍板 Q3-A/Q4-A)。
     * 留痕失败不阻断主链路(报表缺失优于采集不可用)。
     */
    private void recordDrop(AnalyticsSite site, String reason, int eventCount,
                            CollectRequestContext req, String detail) {
        try {
            AnalyticsCollectLog row = new AnalyticsCollectLog();
            row.setTenantId(site.getTenantId());
            row.setSource(site.getSource());
            row.setSiteKey(site.getSiteKey());
            row.setDropReason(reason);
            row.setEventCount(eventCount);
            row.setIpHash(req.clientIp() == null ? null
                : DigestUtil.sha256Hex(req.clientIp()).substring(0, 16));
            row.setUserAgent(truncate(req.userAgent(), 512));
            row.setOriginHost(truncate(OriginDomainMatcher.extractHost(
                StringUtils.isNotBlank(req.origin()) ? req.origin() : req.referer()), 255));
            row.setDetail(truncate(detail, 500));
            row.setCreateTime(new java.util.Date());
            TenantHelper.dynamic(site.getTenantId(), () -> collectLogMapper.insert(row));
        } catch (Exception e) {
            log.warn("[Analytics] collect drop log failed reason={} err={}", reason, e.getMessage());
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
