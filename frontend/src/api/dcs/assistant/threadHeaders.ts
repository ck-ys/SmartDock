/**
 * 会话粘性请求头（多实例批 0）。
 *
 * <p>负载均衡按 `X-Thread-Id` 做一致性哈希；缺少这个头，同一会话可能落到另一台
 * 实例，HITL 确认卡/反问/续看/停止生成都会失效。所有带 threadId 的助手请求
 * 必须走这个 helper，禁止各调用点手写头名。</p>
 */
export function threadHeaders(threadId?: string | null): Record<string, string> {
  return threadId ? { 'X-Thread-Id': threadId } : {};
}
