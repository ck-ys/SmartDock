package com.smartdock.analytics.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.analytics.domain.AnalyticsSite;
import com.smartdock.analytics.mapper.AnalyticsSiteMapper;
import com.smartdock.analytics.query.AnalyticsCollectBo;
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
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsCollectorService {

    private static final int MAX_BATCH = 200;

    private final AnalyticsSiteMapper siteMapper;
    private final ProfileEventPublisher eventPublisher;

    public Map<String, Object> collect(AnalyticsCollectBo bo) {
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
            // Schema 约束在 props 上：把采集上下文里的页面/关键词同步一份到业务属性
            Object pageUrl = event.getContext().get("pageUrl");
            if (pageUrl != null && StringUtils.isBlank(String.valueOf(event.getProps().get("pageUrl")))) {
                event.getProps().put("pageUrl", pageUrl);
            }
            Object keyword = event.getContext().get("searchTerm");
            if (keyword != null && StringUtils.isBlank(String.valueOf(event.getProps().get("keyword")))) {
                event.getProps().put("keyword", keyword);
            }
            events.add(event);
        }
        if (events.isEmpty()) {
            return Map.of("received", bo.getEvents().size(), "accepted", 0);
        }
        int accepted = TenantHelper.dynamic(site.getTenantId(), () -> eventPublisher.publishBatch(events));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", bo.getEvents().size());
        out.put("accepted", accepted);
        out.put("skipped", bo.getEvents().size() - accepted);
        return out;
    }
}
