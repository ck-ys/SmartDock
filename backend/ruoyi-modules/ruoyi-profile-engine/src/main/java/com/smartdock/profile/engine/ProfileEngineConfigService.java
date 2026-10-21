package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.smartdock.profile.domain.ProfileEngineConfig;
import com.smartdock.profile.mapper.ProfileEngineConfigMapper;
import com.smartdock.profile.query.ProfileEngineConfigBo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 画像引擎配置: 自动重算开关 / 复核开关与阈值 / 合并窗口。
 *
 * <p>事件链路每个事件都会读配置, 用 30 秒本地缓存挡住高频查询;
 * 管理面更新时主动失效缓存。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileEngineConfigService {

    private static final long CACHE_TTL_MS = 30_000L;
    private static final BigDecimal DEFAULT_THRESHOLD = BigDecimal.valueOf(60);

    private final ProfileEngineConfigMapper configMapper;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ProfileEngineConfig getOrCreate(String source) {
        return getOrCreate(currentTenant(), source);
    }

    public ProfileEngineConfig getOrCreate(String tenantId, String source) {
        String tenant = StringUtils.isBlank(tenantId) ? "000000" : tenantId;
        ProfileEngineConfig config = select(tenant, source);
        if (config != null) {
            return config;
        }
        ProfileEngineConfig created = new ProfileEngineConfig();
        created.setConfigId(IdWorker.getId());
        created.setTenantId(tenant);
        created.setSource(source);
        created.setAutoRecalc("1");
        created.setReviewEnabled("1");
        created.setReviewThreshold(DEFAULT_THRESHOLD);
        created.setDebounceMs(2000);
        created.setReviewSlaDays(7);
        created.setRemark("默认配置");
        created.setCreateBy(currentUser());
        try {
            configMapper.insert(created);
            return created;
        } catch (DuplicateKeyException concurrent) {
            return select(tenant, source);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileEngineConfig update(ProfileEngineConfigBo bo) {
        if (bo == null || StringUtils.isBlank(bo.getSource())) {
            throw new ServiceException("source 不能为空");
        }
        ProfileEngineConfig config = getOrCreate(bo.getSource());
        if (bo.getAutoRecalc() != null) {
            config.setAutoRecalc("0".equals(bo.getAutoRecalc()) ? "0" : "1");
        }
        if (bo.getReviewEnabled() != null) {
            config.setReviewEnabled("0".equals(bo.getReviewEnabled()) ? "0" : "1");
        }
        if (bo.getReviewThreshold() != null) {
            if (bo.getReviewThreshold().signum() < 0
                || bo.getReviewThreshold().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new ServiceException("复核阈值必须在 0~100 之间");
            }
            config.setReviewThreshold(bo.getReviewThreshold());
        }
        if (bo.getDebounceMs() != null) {
            if (bo.getDebounceMs() < 0 || bo.getDebounceMs() > 60_000) {
                throw new ServiceException("合并窗口必须在 0~60000 毫秒之间");
            }
            config.setDebounceMs(bo.getDebounceMs());
        }
        if (bo.getReviewSlaDays() != null) {
            if (bo.getReviewSlaDays() < 1 || bo.getReviewSlaDays() > 365) {
                throw new ServiceException("复核 SLA 天数必须在 1~365 之间");
            }
            config.setReviewSlaDays(bo.getReviewSlaDays());
        }
        if (bo.getEventRetentionDays() != null) {
            if (bo.getEventRetentionDays() == 0) {
                // 0=清空自定义, 回到继承全局默认(前端空输入即传 0; 不传字段则不动现值)
                config.setEventRetentionDays(null);
            } else if (bo.getEventRetentionDays() < 1 || bo.getEventRetentionDays() > 36_500) {
                throw new ServiceException("事件保留天数必须在 0~36500 之间(0=继承全局默认)");
            } else {
                config.setEventRetentionDays(bo.getEventRetentionDays());
            }
        }
        if (bo.getEventArchive() != null) {
            config.setEventArchive("0".equals(bo.getEventArchive()) ? "0" : "1");
        }
        if (bo.getRemark() != null) {
            config.setRemark(StringUtils.substring(bo.getRemark(), 0, 500));
        }
        config.setUpdateBy(currentUser());
        configMapper.updateById(config);
        cache.remove(key(config.getTenantId(), config.getSource()));
        return config;
    }

    public boolean autoRecalc(String tenantId, String source) {
        return "1".equals(config(tenantId, source).getAutoRecalc());
    }

    public boolean reviewEnabled(String tenantId, String source) {
        return "1".equals(config(tenantId, source).getReviewEnabled());
    }

    public BigDecimal reviewThreshold(String tenantId, String source) {
        BigDecimal threshold = config(tenantId, source).getReviewThreshold();
        return threshold == null ? DEFAULT_THRESHOLD : threshold;
    }

    public int debounceMs(String tenantId, String source) {
        Integer value = config(tenantId, source).getDebounceMs();
        return value == null ? 0 : value;
    }

    private ProfileEngineConfig config(String tenantId, String source) {
        String key = key(tenantId, source);
        Cached hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.loadedAt < CACHE_TTL_MS) {
            return hit.config;
        }
        ProfileEngineConfig loaded = getOrCreate(tenantId, source);
        cache.put(key, new Cached(loaded, now));
        return loaded;
    }

    private ProfileEngineConfig select(String tenantId, String source) {
        return configMapper.selectList(new LambdaQueryWrapper<ProfileEngineConfig>()
                .eq(ProfileEngineConfig::getTenantId, tenantId)
                .eq(ProfileEngineConfig::getSource, source)
                .last("LIMIT 1"))
            .stream().findFirst().orElse(null);
    }

    private String key(String tenantId, String source) {
        return (StringUtils.isBlank(tenantId) ? "000000" : tenantId) + "|" + source;
    }

    private Long currentUser() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentTenant() {
        try {
            String t = TenantHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        try {
            String t = LoginHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 非登录上下文
        }
        return "000000";
    }

    private record Cached(ProfileEngineConfig config, long loadedAt) {
    }
}
