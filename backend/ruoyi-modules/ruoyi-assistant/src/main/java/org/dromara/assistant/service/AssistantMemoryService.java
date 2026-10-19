package org.dromara.assistant.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantMemory;
import org.dromara.assistant.mapper.AssistantMemoryMapper;
import org.dromara.assistant.security.AssistantPiiFilter;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 助手长期记忆服务（二站④ AU-53，2026-10-08）。
 *
 * <p>链路：save_memory 工具 → {@link #save}（PII 脱敏+去重+容量闸）→ 落库；
 * {@link org.dromara.assistant.core.MemoryInjectModelDecorator} 每次模型调用 →
 * {@link #digest}（30s 进程内缓存）注入「用户长期记忆」系统消息；记忆页 →
 * {@link #listForUser}/{@link #remove} 本人可查可删。</p>
 *
 * <p>缓存口径：进程内 30s TTL（写后立即 evict 本进程；多节点部署下其它节点
 * 最迟 30s 过期——记忆注入对时效不敏感，可接受，注释钉死防误当 bug 排查）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantMemoryService {

    /** 单条上限（与表列宽一致） */
    static final int MAX_CONTENT = 200;
    /** 每人容量上限（防模型滥存刷库；满=提示清理后再存） */
    static final int MAX_PER_USER = 50;
    private static final long DIGEST_TTL_MS = 30_000;

    private final AssistantMemoryMapper memoryMapper;
    private final AssistantPiiFilter piiFilter;

    /** digest 缓存：key=tenant:user → [0]=过期时刻, [1]=digest（null=查过无记忆） */
    private final Map<String, Object[]> digestCache = new ConcurrentHashMap<>();

    /** save_memory 工具入口：脱敏→截断→去重→容量闸→落库；返回给人话结果供模型转告 */
    public String save(String tenantId, Long userId, String raw) {
        String content = raw == null ? "" : raw.trim();
        if (content.isEmpty()) {
            return "内容是空的，没有保存";
        }
        if (content.length() > MAX_CONTENT) {
            content = content.substring(0, MAX_CONTENT);
        }
        // PII 脱敏（手机号/身份证不进长期记忆——记忆页用户可见，更要守住）
        content = piiFilter.mask(content, tenantId);
        if (memoryMapper.countByContent(tenantId, userId, content) > 0) {
            return "这条已经记过了，不重复保存";
        }
        if (memoryMapper.countByUser(tenantId, userId) >= MAX_PER_USER) {
            return "记忆最多存 " + MAX_PER_USER + " 条：请在「AI 助手记忆」页删掉不再需要的，再让我记新的";
        }
        DcsAssistantMemory row = new DcsAssistantMemory();
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setContent(content);
        row.setSource("AI");
        row.setCreateBy(userId);
        row.setCreateTime(new Date());
        memoryMapper.insert(row);
        evict(tenantId, userId);
        log.info("[AssistantMemory] 已保存 tenant={} user={} contentLen={}",
            tenantId, userId, content.length());
        return "已记住：" + content;
    }

    /** 记忆页清单（新→旧） */
    public List<DcsAssistantMemory> listForUser(String tenantId, Long userId) {
        return memoryMapper.listByUser(tenantId, userId);
    }

    /** 记忆页删除（属主谓词在 SQL；删别人=影响 0 行） */
    public boolean remove(String tenantId, Long userId, Long id) {
        boolean ok = memoryMapper.deleteOwn(tenantId, userId, id) > 0;
        if (ok) {
            evict(tenantId, userId);
        }
        return ok;
    }

    /** 注入用摘要：「c1」；「c2」（上限取最近 30 条防上下文膨胀）；无记忆返回 null */
    public String digest(String tenantId, Long userId) {
        String key = tenantId + ":" + userId;
        Object[] cached = digestCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && (Long) cached[0] > now) {
            return (String) cached[1];
        }
        List<DcsAssistantMemory> rows = memoryMapper.listByUser(tenantId, userId);
        String digest = null;
        if (!rows.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int n = Math.min(rows.size(), 30);
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append('；');
                }
                sb.append('「').append(rows.get(i).getContent()).append('」');
            }
            digest = sb.toString();
        }
        digestCache.put(key, new Object[]{now + DIGEST_TTL_MS, digest});
        return digest;
    }

    /** 写后立即失效本进程缓存 */
    private void evict(String tenantId, Long userId) {
        digestCache.remove(tenantId + ":" + userId);
    }
}
