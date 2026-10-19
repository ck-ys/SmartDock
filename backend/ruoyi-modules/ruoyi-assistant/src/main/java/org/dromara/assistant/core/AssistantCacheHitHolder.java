package org.dromara.assistant.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级缓存命中聚合器（Q9，2026-10-15 拍板A，ZCode 同构实证 turn-model-step-usage.ts）。
 *
 * <p>每回合模型返回真实 usage 后由 {@code BillingModelDecorator.record} 顺手累加
 * [请求数, 总输入, 缓存读]（口径=缓存读÷总输入——总输入已含缓存读，分母不重复加，
 * ZCode 源码注释两处强调同款）；状态卡带 threadId 查询时取回「本会话命中率」。
 * 只记主链真实回传（估算行/侧车/失败行不计），重启后从当前会话重新累计
 * （agent=会话生命周期，ZCode 同款行为）。</p>
 *
 * <p>上限保护与 {@link AssistantModelChoiceHolder} 同款：超限整体清空（丢的只是
 * 展示数字，下一回合起重新累计，可接受）。排错兜底：usage 落账 scene 尾部带
 * 8 位会话标识（同批改动），按会话查库仍可复盘。</p>
 */
public final class AssistantCacheHitHolder {

    /** 会话聚合快照：requests=计入的真实回传数；ratePct=null 表示尚无有效样本 */
    public record Aggregate(long requests, long promptTokens, long cachedTokens) {
        public Integer ratePct() {
            if (promptTokens <= 0 || requests <= 0) {
                return null;
            }
            // 钳制（对齐 AiBillingCalculator 口径）：异常回传 cached>prompt 时按 100% 封顶，不出 >100%
            long effective = Math.min(cachedTokens, promptTokens);
            return (int) Math.round(effective * 100.0 / promptTokens);
        }
    }

    /** threadId → [请求数, 总输入, 缓存读]；long[3] 就地累加（ConcurrentHashMap.compute 原子） */
    private static final Map<String, long[]> BY_THREAD = new ConcurrentHashMap<>();
    private static final int CAP = 50_000;

    private AssistantCacheHitHolder() {
    }

    /** 主链每回合真实回传后累加（估算/侧车/失败由调用方过滤，不入此） */
    public static void record(String threadId, long promptTokens, long cachedTokens) {
        if (threadId == null || threadId.isBlank() || promptTokens <= 0) {
            return;
        }
        if (BY_THREAD.size() > CAP) {
            BY_THREAD.clear();
        }
        BY_THREAD.compute(threadId, (k, v) -> {
            long[] a = v == null ? new long[3] : v;
            a[0] += 1;
            a[1] += promptTokens;
            a[2] += Math.max(0, cachedTokens);
            return a;
        });
    }

    /** 本会话聚合快照；无记录返回 requests=0 的空聚合（ratePct=null） */
    public static Aggregate aggregate(String threadId) {
        long[] a = threadId == null ? null : BY_THREAD.get(threadId);
        return a == null ? new Aggregate(0, 0, 0) : new Aggregate(a[0], a[1], a[2]);
    }
}
