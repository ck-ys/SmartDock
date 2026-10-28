package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.analytics.mapper.AnalyticsEventMapper;
import com.smartdock.profile.domain.ProfileSourceConfig;
import com.smartdock.profile.mapper.ProfileSourceConfigMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源显示配置(只读清单)。
 *
 * <p>覆盖层设计: 真实源清单以数据为准(主体表/事件表现算), 本表只叠加显示名与启停。
 * 单源重构后「来源管理」写入口(改名/停用)已随页签下线, 本服务只保留清单给
 * 引擎运维与 AI 助手工具读; 表结构与存量配置行原样保留。</p>
 *
 * @author smartdock
 */
@RequiredArgsConstructor
@Service
public class ProfileSourceConfigService {

    private final ProfileSourceConfigMapper configMapper;
    private final ProfileSubjectMapper subjectMapper;
    private final AnalyticsEventMapper eventMapper;

    /**
     * 管理面全量清单: 主体源 ∪ 事件源 ∪ 已配置源(含停用), 附主体数/事件数与显示配置。
     */
    public List<Map<String, Object>> listForAdmin(String tenantId) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : subjectMapper.selectSubjectSourcesRaw(tenantId)) {
            merged.computeIfAbsent(String.valueOf(row.get("source")), this::emptyRow).put("subjects", row.get("subjects"));
        }
        for (Map<String, Object> row : eventMapper.selectEventSourcesRaw(tenantId)) {
            merged.computeIfAbsent(String.valueOf(row.get("source")), this::emptyRow).put("events", row.get("events"));
        }
        for (ProfileSourceConfig cfg : configMapper.selectList(new LambdaQueryWrapper<ProfileSourceConfig>()
                .eq(ProfileSourceConfig::getTenantId, tenantId))) {
            Map<String, Object> row = merged.computeIfAbsent(cfg.getSource(), this::emptyRow);
            row.put("displayName", cfg.getDisplayName());
            row.put("enabled", cfg.getEnabled());
        }
        return new ArrayList<>(merged.values());
    }

    private Map<String, Object> emptyRow(String source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source", source);
        row.put("displayName", null);
        row.put("enabled", "1");
        row.put("subjects", 0);
        row.put("events", 0);
        return row;
    }

    /** 当前生效租户(超管动态切换后管理面要跟着走), 缺失按未登录处理由调用方拦截 */
    public String currentTenant() {
        String tenantId = TenantHelper.getTenantId();
        return StringUtils.isBlank(tenantId) || "null".equals(tenantId) ? null : tenantId;
    }
}
