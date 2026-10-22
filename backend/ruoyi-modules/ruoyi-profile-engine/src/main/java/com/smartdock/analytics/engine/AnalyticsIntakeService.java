package com.smartdock.analytics.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.analytics.domain.AnalyticsSite;
import com.smartdock.analytics.mapper.AnalyticsSiteMapper;
import com.smartdock.analytics.mapper.AnalyticsVisitorFirstSeenMapper;
import com.smartdock.analytics.query.AnalyticsSiteCreateBo;
import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.event.ProfileEvent;
import com.smartdock.profile.event.ProfileEventBo;
import com.smartdock.profile.event.ProfileSubject;
import com.smartdock.profile.identity.ProfileIdentityLink;
import com.smartdock.profile.mapper.ProfileEventMapper;
import com.smartdock.profile.mapper.ProfileIdentityLinkMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据接入页服务（2026-10-22 拍板·全按推荐）：新建站点（起名即建）、
 * 模拟访客流量（带标记可清）、检测接入（三绿探针）。
 * 事件全部走 {@link ProfileEventPublisher} 正规链路（幂等/自动汇总不变）。
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsIntakeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AnalyticsSiteMapper siteMapper;
    private final ProfileEventMapper eventMapper;
    private final ObjectProvider<ProfileEventPublisher> eventPublisherProvider;
    private final AnalyticsService analyticsService;
    private final ProfileSubjectMapper subjectMapper;
    private final ProfileIdentityLinkMapper identityLinkMapper;
    private final AnalyticsVisitorFirstSeenMapper firstSeenMapper;

    /** 起名即建：siteKey 系统随机签发（32 位十六进制），source 固定 web，白名单空=不限 */
    public AnalyticsSite createSite(AnalyticsSiteCreateBo bo) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String key = IntakeEventFactory.generateSiteKey(RANDOM);
            Long exists = TenantHelper.ignore(() -> siteMapper.selectCount(
                new LambdaQueryWrapper<AnalyticsSite>().eq(AnalyticsSite::getSiteKey, key)));
            if (exists != null && exists > 0) {
                continue;
            }
            AnalyticsSite site = new AnalyticsSite();
            site.setTenantId(TenantHelper.getTenantId());
            site.setSource("web");
            site.setSiteKey(key);
            site.setSiteName(bo.getSiteName().trim());
            site.setAllowedDomains(bo.getAllowedDomains() == null ? "" : bo.getAllowedDomains().trim());
            site.setEnabled("1");
            site.setRemark("数据接入页创建");
            try {
                siteMapper.insert(site);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 全局唯一键兜底：预检窗口外的极小概率碰撞并入重试
                continue;
            }
            site.setSignSecret(null);
            return site;
        }
        throw new ServiceException("站点钥匙生成冲突，请重试");
    }

    /** 模拟 N 个访客（1-20 夹紧）：事件带 sim- 前缀与 simulated=true，走正规发布链路；半成功直接报错不静默 */
    public Map<String, Object> simulate(int visitors) {
        int count = Math.min(Math.max(visitors, 1), 20);
        List<ProfileEventBo> events = IntakeEventFactory.buildSimulatedEvents(count, "web", new Date(), RANDOM);
        Map<String, Object> result = publisher().publishBatchDetailed(events);
        int written = ((Number) result.getOrDefault("written", 0)).intValue();
        if (written < events.size()) {
            throw new ServiceException("模拟事件仅写入 " + written + "/" + events.size()
                + " 条（Schema 拒收等）： " + result.get("failures"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visitors", count);
        out.put("events", events.size());
        out.put("written", written);
        return out;
    }

    /**
     * 清掉模拟数据：事件按服务端写入的 props.simulated 标记删（eventUid 是客户端可控值，
     * 不能当删除键），画像主体/身份链接/访客首见按服务端生成的 sim- 主体前缀清，
     * 再重建 web 源汇总（会话/日汇总从事件回放，口径随之复原）。
     */
    public Map<String, Object> clearSimulated() {
        String tenantId = TenantHelper.getTenantId();
        int deleted = eventMapper.delete(new LambdaQueryWrapper<ProfileEvent>()
            .eq(ProfileEvent::getSource, "web")
            .apply("props->>'simulated' = 'true'"));
        int links = identityLinkMapper.delete(new LambdaQueryWrapper<ProfileIdentityLink>()
            .eq(ProfileIdentityLink::getSource, "web")
            .likeRight(ProfileIdentityLink::getSubjectId, IntakeEventFactory.SIM_UID_PREFIX));
        int subjects = subjectMapper.delete(new LambdaQueryWrapper<ProfileSubject>()
            .eq(ProfileSubject::getSource, "web")
            .likeRight(ProfileSubject::getSubjectId, IntakeEventFactory.SIM_UID_PREFIX));
        int firstSeen = firstSeenMapper.deleteByVisitorKeyPrefix(tenantId, "web", IntakeEventFactory.SIM_UID_PREFIX);
        Map<String, Object> rebuild = analyticsService.rebuild("web", 7);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", deleted);
        out.put("links", links);
        out.put("subjects", subjects);
        out.put("firstSeen", firstSeen);
        out.put("rebuild", rebuild);
        return out;
    }

    /** 检测接入三问：钥匙有效？测试事件收到了？看板已有（非模拟）数据？ */
    public Map<String, Object> detect(String siteKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        // siteKey 全局唯一须跨租户找站点；但别家租户的钥匙对本租户等同无效（探针也不发，不留跨租户痕迹）
        String currentTenant = TenantHelper.getTenantId();
        AnalyticsSite site = TenantHelper.ignore(() -> siteMapper.selectOne(
            new LambdaQueryWrapper<AnalyticsSite>()
                .eq(AnalyticsSite::getSiteKey, siteKey)
                .eq(AnalyticsSite::getEnabled, "1")
                .last("LIMIT 1")));
        if (site == null || !site.getTenantId().equals(currentTenant)) {
            out.put("keyValid", false);
            out.put("testReceived", false);
            out.put("hasData", false);
            return out;
        }
        String uid = IntakeEventFactory.SIM_UID_PREFIX + "detect-" + UUID.randomUUID();
        boolean written = publisher().publish(IntakeEventFactory.buildDetectEvent(site.getSource(), uid));
        Long probe = eventMapper.selectCount(new LambdaQueryWrapper<ProfileEvent>()
            .eq(ProfileEvent::getEventUid, uid));
        // 探针即用即删：验证完写链路立刻撤走，不留事件/主体/首见——统计口径零污染
        // （2s 去抖的重建按事件表回放，此刻行已删，不会生成幽灵会话）
        if (written && probe != null && probe > 0) {
            eventMapper.delete(new LambdaQueryWrapper<ProfileEvent>().eq(ProfileEvent::getEventUid, uid));
            subjectMapper.delete(new LambdaQueryWrapper<ProfileSubject>()
                .eq(ProfileSubject::getSource, site.getSource())
                .eq(ProfileSubject::getSubjectId, "sim-detect-probe"));
            identityLinkMapper.delete(new LambdaQueryWrapper<ProfileIdentityLink>()
                .eq(ProfileIdentityLink::getSource, site.getSource())
                .eq(ProfileIdentityLink::getSubjectId, "sim-detect-probe"));
            firstSeenMapper.deleteByVisitorKeyPrefix(currentTenant, site.getSource(), "sim-detect-probe");
        }
        // 近 30 天窗口：演示/存量数据多在历史日期，只看今天会把有数据的看板误报成"暂无数据"
        Date since = Date.from(java.time.LocalDate.now().minusDays(30)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        Long total = eventMapper.selectCount(new LambdaQueryWrapper<ProfileEvent>()
            .eq(ProfileEvent::getSource, site.getSource())
            .ge(ProfileEvent::getOccurredTime, since));
        Long simulated = eventMapper.selectCount(new LambdaQueryWrapper<ProfileEvent>()
            .eq(ProfileEvent::getSource, site.getSource())
            .ge(ProfileEvent::getOccurredTime, since)
            .apply("props->>'simulated' = 'true'"));
        out.put("keyValid", true);
        out.put("testReceived", written && probe != null && probe > 0);
        out.put("hasData", total != null && simulated != null && total - simulated > 0);
        return out;
    }

    private ProfileEventPublisher publisher() {
        return eventPublisherProvider.getObject();
    }
}
