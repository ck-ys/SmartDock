package org.dromara.assistant.persist;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 悬挂 interrupt 同生命周期缓存（二站 T1，2026-10-06，spec 悬挂 interrupt 毒化 thread 根治）。
 *
 * <p><b>为什么需要它</b>：框架 {@code AguiResumeCoordinator.pendingInterruptsByThread} 是
 * 进程内存+包私有——DCS 查不到「该 thread 挂了哪些 interrupt」，而悬挂 thread 的新 run 不带
 * resume 会被 0ms 拒（"resume must address all of them"）。本缓存由 RunRecorder 在
 * RUN_FINISHED(interrupt) 时写入，入口过滤器（AssistantResumeUnlockFilter）窥视后注入
 * resume=cancelled，实现「刷新丢卡→下一条消息自动解锁」。</p>
 *
 * <p><b>值为列表</b>：T0 探针实测同一 run 可挂 2 个 interrupt（call_00+call_01），部分覆盖
 * resume 会被拒（missing=[...]）——注入必须全量，存也必须全量存。</p>
 *
 * <p><b>与框架严格镜像、同寿到进程死</b>（三审回修 P0-2 定版）：框架 pending 只在「无错误的
 * RunFinished」移除、无任何过期机制（javap 亲证）——本缓存同样只在干净终态 clear、绝不做
 * 时间清扫，否则单边失配=丢卡用户永久毒化。内存量=真实挂起会话数×几十字节，源头有配额闸，
 * 无 DoS 面；staleThreadIds 只为 stepBuffers 配对回收报读超龄键，不删悬挂条目。</p>
 *
 * <p>键=threadId（与框架同键）；at=写入时刻毫秒（报读判龄用）。单实例部署口径
 * （与 stepBuffers 同一前提，批J 三审已登记）。</p>
 */
@Slf4j
@Component
public class AssistantPendingInterruptCache {

    /**
     * 悬挂 interrupt 快照：id=resume 用的复合键（threadId:toolCallId），expiresAt 框架原值。
     * 二站-B（C2，2026-10-07）：补 toolName/argsJson——换设备重建确认卡的展示数据源
     * （stepBuffers 按 toolCallId 对齐补全；args 同 400 上限截断口径）。
     */
    public record PendingInterrupt(String id, String toolCallId, String reason, String expiresAt, long at,
                                    String toolName, String argsJson) {

        /** 兼容构造（二站 T1 旧调用面：无卡展示数据，工具名/参数空串） */
        public PendingInterrupt(String id, String toolCallId, String reason, String expiresAt, long at) {
            this(id, toolCallId, reason, expiresAt, at, "", "");
        }
    }

    private final ConcurrentHashMap<String, List<PendingInterrupt>> byThread = new ConcurrentHashMap<>();

    /** 已报读过超龄的 thread（staleThreadIds 幂等用；store/clear 重挂时复位重新报读） */
    private final Set<String> reportedStale = ConcurrentHashMap.newKeySet();

    /**
     * RUN_FINISHED(interrupt outcome) 时整表替换该 thread 的悬挂列表。
     * 替换语义：同 thread 后续轮再挂新 interrupt，旧列表已随上一轮 resume 消费，覆盖安全。
     */
    public void store(String threadId, List<PendingInterrupt> pending) {
        if (threadId == null || pending == null || pending.isEmpty()) {
            return;
        }
        byThread.put(threadId, List.copyOf(pending));
        reportedStale.remove(threadId);
    }

    /** 注入前窥视（不清除——清除只发生在干净终态，与框架 remove 条件严格镜像）；无悬挂返回 null */
    public List<PendingInterrupt> peek(String threadId) {
        if (threadId == null) {
            return null;
        }
        return byThread.get(threadId);
    }

    /** 干净终态（非 interrupt 的 RUN_FINISHED=框架已消费全部悬挂）到达即清该 thread 键 */
    public void clear(String threadId) {
        if (threadId != null) {
            byThread.remove(threadId);
            reportedStale.remove(threadId);
        }
    }

    /**
     * 超龄 thread 报读（三审回修 P0-2：只查不删）。框架 pending 无任何过期机制（javap 亲证：
     * coordinator 无时间逻辑，expiresAt 不被消费）——悬挂条目必须与框架同寿到进程死，否则
     * 「清缓存+框架仍悬挂」单边失配=丢卡用户永久毒化（本批要根治的场景原样复发）。本方法
     * 仅供 RetentionJob 配对回收同 thread 的 stepBuffers（展示层滞留缓冲），幂等：同一悬挂
     * 周期每个 thread 只报一次，store/clear 重挂后重新计龄。
     */
    public List<String> staleThreadIds(long maxAgeMillis) {
        long deadline = System.currentTimeMillis() - maxAgeMillis;
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, List<PendingInterrupt>> e : byThread.entrySet()) {
            List<PendingInterrupt> list = e.getValue();
            if (list != null && !list.isEmpty() && list.get(0).at() < deadline
                && reportedStale.add(e.getKey())) {
                stale.add(e.getKey());
            }
        }
        return stale;
    }

    /** 空判（入口过滤器零成本快路径） */
    public boolean isEmpty() {
        return byThread.isEmpty();
    }

    /** 在册 thread 数（监控/测试用） */
    public int size() {
        return byThread.size();
    }
}
