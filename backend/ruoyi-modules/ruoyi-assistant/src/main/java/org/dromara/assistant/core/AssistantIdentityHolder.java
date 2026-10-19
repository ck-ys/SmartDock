package org.dromara.assistant.core;

import org.dromara.common.core.domain.model.LoginUser;

/**
 * 请求内身份桥（批A A3 补丁，2026-10-04）。
 *
 * <p>背景：AGUI 控制器是响应式链，registry 工厂 supplier 在异步线程池执行，
 * 该线程没有 Sa-Token 上下文（12机 冒烟实证：SaTokenContext 上下文尚未初始化）。
 * 而 {@link AssistantRuntimeContextResolver} 在同一条同步链上先执行、手里有
 * 裸 token 解析出的 LoginUser——两者同线程顺序执行（ThreadSessionManager 用
 * Map.compute 同步调 supplier，字节码实证），用 ThreadLocal 桥一次一递。</p>
 *
 * <p>安全口径：consume 即清除；supplier 取不到（线程被 reactor 换乘等罕见情形）
 * 一律抛 401 fail-closed，绝不回落到别人或匿名。</p>
 *
 * <p>三审回修（glm53 P1-1 / dspro P2-1）：会话命中路径（同 userId+threadId 追问）
 * supplier 不执行，set 的身份会残留在 cachedThreadPool 线程上——当前版本靠
 * "resolve 必先于 supplier 且同线程"的框架时序保证不被利用，属脆弱纵深。
 * 收紧为带时效+同线程校验：过期或跨线程的残留一律按无身份处理（fail-closed 401）。</p>
 */
final class AssistantIdentityHolder {

    /** 残留身份最大可消费窗口：resolve 与 supplier 同步链路，正常毫秒级 */
    private static final long MAX_AGE_NANOS = 30_000_000_000L;

    /** 包私有：工厂（同包）取 aguiThreadId 构造附件工具；runId 供建会话失败终局化（Q4） */
    record Entry(LoginUser user, String aguiThreadId, String runId, long threadId, long at) {
    }

    private static final ThreadLocal<Entry> HOLDER = new ThreadLocal<>();

    /** 兜底空条目（过期/跨线程残留按无身份处理，fail-closed 口径不变） */
    private static final Entry EMPTY = new Entry(null, null, null, -1L, 0L);

    private AssistantIdentityHolder() {
    }

    /** 带本轮 AGUI threadId（附件工具按会话取附件；threadId 空=旧客户端，工具自然查空）；
     *  runId 同桥（Q4：工厂建会话失败时据此同步终局化 run 行） */
    static void set(LoginUser user, String aguiThreadId, String runId) {
        HOLDER.set(new Entry(user, aguiThreadId, runId, Thread.currentThread().getId(), System.nanoTime()));
    }

    /** 读取并清除——一次性传递；过期或跨线程的残留按无身份处理，防串人 */
    static LoginUser consume() {
        return consumeEntry().user();
    }

    /** 同 consume，一并取 AGUI threadId（工厂构造附件工具用） */
    static Entry consumeEntry() {
        Entry entry = HOLDER.get();
        HOLDER.remove();
        if (entry == null
            || entry.threadId() != Thread.currentThread().getId()
            || System.nanoTime() - entry.at() > MAX_AGE_NANOS) {
            return EMPTY;
        }
        return entry;
    }
}
