package com.smartdock.assistant.service;

import com.smartdock.assistant.domain.model.DcsAssistantMemory;
import com.smartdock.assistant.domain.model.DcsAssistantMemoryPref;
import com.smartdock.assistant.domain.model.DigestResult;
import com.smartdock.assistant.mapper.AssistantMemoryMapper;
import com.smartdock.assistant.mapper.AssistantMemoryPrefMapper;
import com.smartdock.assistant.support.AssistantMemoryFacts;
import com.smartdock.assistant.support.AssistantPiiFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 助手长期记忆存储服务（批 6 题 2 案 1，2026-11-20 按表归属从 556 行旧服务拆出）。
 *
 * <p><b>边界</b>：只碰记忆自有两张表（{@code dcs_assistant_memory} /
 * {@code dcs_assistant_memory_pref}）——清单/搜索/行内编辑/删除/开关/归档/导出/清空，
 * 以及按问题相关性取数的注入摘要（30s 进程内缓存）。</p>
 *
 * <p><b>为什么摘要在这里</b>：缓存失效点 6 处（save / remove / edit / 开关 / 清空 / 归档），
 * 只有 save 一处在回合写路径（legacy 回合服务，旧→新允许）；若摘要在旧区，
 * remove/edit/开关/清空/归档 5 处都会变成新根→legacy，直接踩门禁第 8 条。</p>
 *
 * <p>写路径（save / saveReturningId / 回合统计）因要碰会话表与回合台账，留在
 * {@link com.smartdock.assistant.legacy.service.AssistantMemoryTurnService}；
 * 判定与打分等纯函数在 {@link AssistantMemoryFacts}。</p>
 *
 * <p>缓存口径：进程内 30s TTL（写后立即失效本进程；多节点部署下其它节点最迟 30s 过期——
 * 记忆注入对时效不敏感，可接受，注释钉死防误当 bug 排查）。缓存键含问题指纹；
 * 条目另存问题原文，命中时再比一次（防 hashCode 碰撞串味）；条目数封顶，写满先清过期再整体清空。</p> */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantMemoryService {

    private static final long DIGEST_TTL_MS = 30_000;
    /** 进程内缓存条目上限：正常量级（每用户几分钟内几个问题）远达不到，纯防长期机泄漏 */
    private static final int DIGEST_CACHE_MAX = 4096;

    private final AssistantMemoryMapper memoryMapper;
    private final AssistantMemoryPrefMapper prefMapper;
    private final AssistantPiiFilter piiFilter;

    /** digest 缓存：key=tenant:user:questionHash → [0]=过期时刻, [1]=DigestResult（null=查过无命中）, [2]=问题原文 */
    private final Map<String, Object[]> digestCache = new ConcurrentHashMap<>();

    /** 每用户代际号：写后失效 +1；读路径算完发现代际变了就不再回填（防 cache-aside 把旧值灌回） */
    private final Map<String, Long> digestEpoch = new ConcurrentHashMap<>();

    /** 生效中清单（新→旧；题7 主列表） */
    public List<DcsAssistantMemory> listForUser(String tenantId, Long userId) {
        return memoryMapper.listByUserStatus(tenantId, userId, AssistantMemoryFacts.ST_ACTIVE);
    }

    /** 历史清单（题7/题8：被替代 + 已归档，合并按 id 倒序） */
    public List<DcsAssistantMemory> listHistory(String tenantId, Long userId) {
        List<DcsAssistantMemory> out = new ArrayList<>();
        out.addAll(memoryMapper.listByUserStatus(tenantId, userId, AssistantMemoryFacts.ST_SUPERSEDED));
        out.addAll(memoryMapper.listByUserStatus(tenantId, userId, AssistantMemoryFacts.ST_ARCHIVED));
        out.sort((a, b) -> Long.compare(b.getId(), a.getId()));
        return out;
    }

    /** 容量/条数用量（题7「3 / 50 条」只算生效中） */
    public int countActive(String tenantId, Long userId) {
        return memoryMapper.countByUser(tenantId, userId);
    }

    /** 记忆页删除（属主谓词在 SQL；删别人=影响 0 行） */
    public boolean remove(String tenantId, Long userId, Long id) {
        boolean ok = memoryMapper.deleteOwn(tenantId, userId, id) > 0;
        if (ok) {
            evictAfterCommit(tenantId, userId);
        }
        return ok;
    }

    /**
     * 行内编辑（题7）：与保存口同一套闸——先 PII 脱敏再长度截断（顺序不可反：
     * 先截会把跨边界的 18 位身份证切出 17 位前缀，正则认不出=明文落库）+ 空内容拒绝 + 精确去重。
     * 属主谓词在 SQL（改他人=0 行）。类型/主题键随内容在同一条 UPDATE 里重算（两段写会留下
     * 「内容新、主题旧」的中间态）。
     */
    public boolean edit(String tenantId, Long userId, Long id, String raw) {
        String content = raw == null ? "" : raw.trim();
        if (content.isEmpty()) {
            return false;
        }
        content = piiFilter.mask(content, tenantId);
        if (content.length() > AssistantMemoryFacts.MAX_CONTENT) {
            content = content.substring(0, AssistantMemoryFacts.MAX_CONTENT);
        }
        DcsAssistantMemory row = memoryMapper.selectById(id);
        if (row == null || !tenantId.equals(row.getTenantId()) || !userId.equals(row.getUserId())) {
            return false;
        }
        if (content.equals(row.getContent())) {
            return true;
        }
        if (memoryMapper.countByContent(tenantId, userId, content) > 0) {
            return false;
        }
        boolean ok = memoryMapper.updateOwnContent(tenantId, userId, id, content,
            AssistantMemoryFacts.detectType(content), AssistantMemoryFacts.topicKeyOf(content),
            new Date()) > 0;
        if (ok) {
            evictAfterCommit(tenantId, userId);
        }
        return ok;
    }

    /**
     * 注入用摘要（题9 案1）：按当前问题给每条打分，取相关 TopK，再按字数预算截断。
     *
     * <p>无相关问题（全部 0 分）→ 返回 null，不注入。命中行回写 last_used_at（题10 归档判据）。</p>
     */
    public DigestResult digest(String tenantId, Long userId, String question) {
        String userKey = tenantId + ":" + userId;
        long epoch0 = digestEpoch.getOrDefault(userKey, 0L);
        String key = userKey + ":" + (question == null ? "" : question.hashCode());
        Object[] cached = digestCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && (Long) cached[0] > now && Objects.equals(cached[2], question)) {
            return (DigestResult) cached[1];
        }
        DigestResult result = buildDigest(tenantId, userId, question);
        if (result != null) {
            touchQuietly(tenantId, userId, result.ids());
        }
        // 算的过程中该用户发生过写（代际变了）→ 不回填，避免把写前快照盖在失效之后
        if (digestEpoch.getOrDefault(userKey, 0L) == epoch0) {
            if (digestCache.size() >= DIGEST_CACHE_MAX) {
                sweepExpired(now);
            }
            digestCache.put(key, new Object[]{now + DIGEST_TTL_MS, result, question});
        }
        return result;
    }

    /** 只读摘要（批A三审 P1）：/status/context 等读接口专用——不回写 last_used_at，
     *  不污染题10 的 90 天归档判据；也不走缓存（读接口低频，直接算）。
     *  2026-11-15 三审席1/席3 P1：带本轮最后一条用户提问——与注入路径同口径打分，
     *  不相关时同样返回 null（面板据此显示「本轮没注入记忆」，而不是记忆库总量）。 */
    public DigestResult digestReadOnly(String tenantId, Long userId, String question) {
        return buildDigest(tenantId, userId, question);
    }

    /** 无问题上下文版（旧调用面保留；/status/context 已改用带 question 的重载） */
    public DigestResult digestReadOnly(String tenantId, Long userId) {
        return buildDigest(tenantId, userId, null);
    }

    private void touchQuietly(String tenantId, Long userId, List<Long> ids) {
        try {
            memoryMapper.touchUsed(tenantId, userId, ids, new Date());
        } catch (RuntimeException e) {
            // 回写失败不影响注入（归档判据退化为「一直未使用」，不会误删）
            log.warn("[AssistantMemory] last_used_at 回写失败: {}", e.getMessage());
        }
    }

    /** 兼容旧签名（无问题上下文=全量按新→旧取，行为同升级前，仅用于无法拿到问题的路径） */
    public String digest(String tenantId, Long userId) {
        DigestResult r = digest(tenantId, userId, null);
        return r == null ? null : r.text();
    }

    private DigestResult buildDigest(String tenantId, Long userId, String question) {
        if (!isUserEnabled(tenantId, userId)) {
            return null;
        }
        List<DcsAssistantMemory> rows = memoryMapper.listByUserStatus(
            tenantId, userId, AssistantMemoryFacts.ST_ACTIVE);
        if (rows.isEmpty()) {
            return null;
        }
        List<DcsAssistantMemory> ranked;
        if (question == null || question.isBlank()) {
            ranked = rows; // 无问题上下文：保持旧口径（新→旧）
        } else {
            List<Scored> scored = new ArrayList<>();
            for (DcsAssistantMemory r : rows) {
                int s = AssistantMemoryFacts.scoreOf(r.getContent(), question);
                if (s > 0) {
                    scored.add(new Scored(r, s));
                }
            }
            if (scored.isEmpty()) {
                return null; // 题9：没有相关的不注入
            }
            scored.sort((a, b) -> b.score != a.score ? Integer.compare(b.score, a.score)
                : Long.compare(b.row.getId(), a.row.getId()));
            ranked = new ArrayList<>();
            for (Scored s : scored) {
                if (ranked.size() >= AssistantMemoryFacts.MAX_DIGEST_ITEMS) {
                    break;
                }
                ranked.add(s.row);
            }
        }

        List<String> contents = new ArrayList<>();
        List<Long> rankedIds = new ArrayList<>();
        for (DcsAssistantMemory r : ranked) {
            contents.add(r.getContent());
            rankedIds.add(r.getId());
        }
        return AssistantMemoryFacts.joinWithBudget(contents, rankedIds, AssistantMemoryFacts.MAX_DIGEST_CHARS);
    }

    /** 本人开关（题10）：缺行=默认开启；租户总闸在装配期判，不在这里 */
    public boolean isUserEnabled(String tenantId, Long userId) {
        DcsAssistantMemoryPref pref = prefMapper.selectOwn(tenantId, userId);
        return pref == null || !"0".equals(pref.getEnabled());
    }

    /** 设本人开关（题10）；返回是否开启 */
    public boolean setUserEnabled(String tenantId, Long userId, boolean enabled) {
        prefMapper.upsertOwn(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
            tenantId, userId, enabled ? "1" : "0", new Date());
        evictAfterCommit(tenantId, userId);
        return enabled;
    }

    /** 归档扫描（题10）：90 天没被注入过的 ACTIVE 条 → ARCHIVED；返回条数 */
    public int archiveStale() {
        Date before = AssistantMemoryFacts.archiveBefore(new Date(), AssistantMemoryFacts.ARCHIVE_DAYS);
        int n = memoryMapper.archiveStale(before, new Date());
        if (n > 0) {
            // 跨租户扫描拿不到受影响用户清单，直接整体清空（条目有上限，成本可忽略）
            digestCache.clear();
            log.info("[AssistantMemory] 归档 {} 条 90 天未使用的记忆", n);
        }
        return n;
    }

    /** 清空本人全部（题10；含历史），返回删除行数 */
    public int clearAll(String tenantId, Long userId) {
        int n = memoryMapper.clearAllOwn(tenantId, userId);
        evictAfterCommit(tenantId, userId);
        return n;
    }

    /** 写后立即失效本进程缓存（该用户全部问题指纹）+ 代际号 +1（让飞行中的旧快照放弃回填） */
    private void evict(String tenantId, Long userId) {
        digestEpoch.merge(tenantId + ":" + userId, 1L, Long::sum);
        String prefix = tenantId + ":" + userId + ":";
        digestCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 事务内调用时推迟到提交后失效——提交前失效会被另一个读请求用「还没提交的旧行」重新灌满缓存 */
    private void evictAfterCommit(String tenantId, Long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(tenantId, userId);
                }
            });
        } else {
            evict(tenantId, userId);
        }
    }

    /** 写满上限时先清过期条目；清完仍满则整体清空（宁可再生，不让 Map 无限长） */
    private void sweepExpired(long now) {
        digestCache.entrySet().removeIf(e -> (Long) e.getValue()[0] <= now);
        if (digestCache.size() >= DIGEST_CACHE_MAX) {
            digestCache.clear();
        }
    }

    /**
     * 供回合写路径调用（旧→新允许）：save 落库后让本服务立即丢弃该用户摘要缓存，
     * 否则新存的记忆最迟 30s 才出现在注入里。
     */
    public void invalidateDigestCache(String tenantId, Long userId) {
        evict(tenantId, userId);
    }

    /** 仅供单测/工具：暴露当前缓存大小（排查「改了不生效」时确认缓存行为） */
    public int cacheSize() {
        return digestCache.size();
    }

    /** 测试辅助：清空缓存 */
    public void clearCacheForTest() {
        digestCache.clear();
    }

    /** 导出用（题10）：全部条目按 id 倒序（含状态） */
    public List<DcsAssistantMemory> exportRows(String tenantId, Long userId) {
        List<DcsAssistantMemory> rows = new ArrayList<>(memoryMapper.listByUser(tenantId, userId));
        rows.sort((a, b) -> Long.compare(b.getId(), a.getId()));
        return rows.isEmpty() ? Collections.emptyList() : rows;
    }

    private record Scored(DcsAssistantMemory row, int score) {
    }
}
