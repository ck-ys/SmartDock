package org.dromara.assistant.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.mapper.AssistantSkillUsageMapper;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 技能使用统计服务（题2 案3，2026-10-31 拍板）。
 *
 * <p>只做两件事：取用一次 → 原子自增；管理列表一次读回全租户统计。
 * 不存调用明细、不做归档扫描——这是板上案3 与案1/案2 的边界。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantSkillUsageService {

    /** 与 ddl skill_id varchar(64) 对齐；超长直接丢弃（脏参数不该把统计写炸） */
    static final int SKILL_ID_MAX = 64;

    private final AssistantSkillUsageMapper usageMapper;

    /**
     * 记一次成功取用。写库失败只 debug 日志——统计是旁路，绝不允许反过来打断对话。
     * 调用线程可能是助手运行时线程（无 Sa-Token），租户由调用方显式传入。
     */
    public void record(String tenantId, String skillId) {
        if (tenantId == null || tenantId.isBlank() || skillId == null || skillId.isBlank()) {
            return;
        }
        String sid = skillId.trim();
        if (sid.length() > SKILL_ID_MAX) {
            return;
        }
        try {
            usageMapper.bump(IdWorker.getId(), tenantId, sid);
        } catch (Exception e) {
            log.debug("[SkillUsage] 统计写入失败 tenant={} skill={}: {}", tenantId, sid, e.getMessage());
        }
    }

    /** 管理列表：skillId → {useCount, lastUsedAt}；无统计的技能不在表里（调用方补 0/null）。
     *  读侧 fail-open（三席 P2）：新表未迁移/库抖动时统计为空，绝不连坐技能列表 500。 */
    public Map<String, Map<String, Object>> usageOf(String tenantId) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (tenantId == null || tenantId.isBlank()) {
            return out;
        }
        try {
            for (Map<String, Object> row : usageMapper.selectByTenant(tenantId)) {
                Object id = row.get("skillid");
                if (id != null) {
                    out.put(String.valueOf(id), row);
                }
            }
        } catch (Exception e) {
            log.debug("[SkillUsage] 统计读取失败（列表照常返回） tenant={}: {}", tenantId, e.getMessage());
        }
        return out;
    }
}
