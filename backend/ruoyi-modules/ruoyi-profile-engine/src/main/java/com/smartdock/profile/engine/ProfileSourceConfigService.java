package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.smartdock.analytics.mapper.AnalyticsEventMapper;
import com.smartdock.profile.domain.ProfileSourceConfig;
import com.smartdock.profile.mapper.ProfileSourceConfigMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源显示配置(改名/停用)。
 *
 * <p>覆盖层设计: 真实源清单以数据为准(主体表/事件表现算), 本表只叠加显示名与启停;
 * 停用不删任何数据, 只让列源 SQL 把该源从全部下拉里滤掉。</p>
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

    /**
     * 改名/停用(覆盖式 upsert): 只允许对真实有过数据或已配置过的源建配置。
     *
     * <p>刻意不加 @Transactional(三审 glm53 P1): PG 里语句级错误(唯一键冲突)会把整个事务置为
     * aborted, 同事务内的并发兜底回查必然 25P02 报错——去掉事务让每条语句独立提交,
     * "insert 撞键 → 回查 → updateById" 的竞态兜底才真正走得通; 本方法全是单行幂等操作,
     * 无多语句一致性问题。</p>
     */
    public void save(String tenantId, String source, String displayName, String enabled) {
        String key = normalizeSource(source);
        String name = normalizeDisplayName(displayName);
        String flag = normalizeEnabled(enabled);
        // 只允许配置"真实有过数据的源"或"已有配置的源"——防手滑存进拼错的假源名变孤儿行
        boolean known = subjectMapper.selectSubjectSourcesRaw(tenantId).stream()
                .anyMatch((row) -> key.equals(String.valueOf(row.get("source"))))
            || eventMapper.selectEventSourcesRaw(tenantId).stream()
                .anyMatch((row) -> key.equals(String.valueOf(row.get("source"))));
        ProfileSourceConfig config = configMapper.selectOne(new LambdaQueryWrapper<ProfileSourceConfig>()
                .eq(ProfileSourceConfig::getTenantId, tenantId)
                .eq(ProfileSourceConfig::getSource, key)
                .last("LIMIT 1"));
        if (!known && config == null) {
            throw new ServiceException("来源 " + key + " 还没有任何数据或配置，不能为它建显示配置");
        }
        if (config == null) {
            config = new ProfileSourceConfig();
            config.setConfigId(IdWorker.getId());
            config.setTenantId(tenantId);
            config.setSource(key);
            config.setDisplayName(name);
            config.setEnabled(flag == null ? "1" : flag);
            try {
                configMapper.insert(config);
                return;
            } catch (DuplicateKeyException concurrent) {
                // 并发首存撞唯一键: 语句独立回滚后回查对方写入的行, 走 update 落自己的值
                config = configMapper.selectOne(new LambdaQueryWrapper<ProfileSourceConfig>()
                        .eq(ProfileSourceConfig::getTenantId, tenantId)
                        .eq(ProfileSourceConfig::getSource, source)
                        .last("LIMIT 1"));
                if (config == null) {
                    // 并发方已写入又极快删除的极端窗口——按"配置已存在"语义放弃本次
                    return;
                }
            }
        }
        config.setDisplayName(name);
        // enabled 缺省不碰原值(三审 dsflash P3): 只改名不传 enabled 的调用不会把停用源静默重新启用
        if (flag != null) {
            config.setEnabled(flag);
        }
        configMapper.updateById(config);
    }

    /** 当前生效租户(超管动态切换后管理面要跟着走), 缺失按未登录处理由调用方拦截 */
    public String currentTenant() {
        String tenantId = TenantHelper.getTenantId();
        return StringUtils.isBlank(tenantId) || "null".equals(tenantId) ? null : tenantId;
    }

    /** source 归一化: trim 后非空且 ≤64 字(varchar(64)), 超长在校验层报错而不是 500 */
    static String normalizeSource(String source) {
        String key = source == null ? "" : source.trim();
        if (key.isEmpty()) {
            throw new ServiceException("source 不能为空");
        }
        if (key.length() > 64) {
            throw new ServiceException("source 最长 64 字");
        }
        return key;
    }

    /** 显示名归一化: 空白视为清空(null), 其余 trim, 上限 64 字 */
    static String normalizeDisplayName(String displayName) {
        String name = StringUtils.isBlank(displayName) ? null : displayName.trim();
        if (name != null && name.length() > 64) {
            throw new ServiceException("显示名最长 64 字");
        }
        return name;
    }

    /** 启停归一化: "0"=停用, 其余非空=启用, null=未传(保留原值) */
    static String normalizeEnabled(String enabled) {
        if (enabled == null) {
            return null;
        }
        return "0".equals(enabled) ? "0" : "1";
    }
}
