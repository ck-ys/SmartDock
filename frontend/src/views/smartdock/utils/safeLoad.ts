import { ElMessage } from 'element-plus';

/**
 * 统一异步加载错误治理工具。
 *
 * 用途: 替换"裸 catch {} 静默吞错"模式, 让数据加载失败可见。
 *
 * 行为:
 * - 成功: 返回数据
 * - 失败: 弹 ElMessage.error(默认文案) + console.error + 返回 fallback
 *
 * 用法:
 * ```ts
 * // 替换前: 失败静默, 用户看不到任何提示
 * try { stats.value = (await getStats()).data; } catch { stats.value = null; }
 *
 * // 替换后: 失败弹提示, fallback 仍为 null
 * const data = await safeLoad(() => getStats(), { fallback: null });
 * stats.value = data?.data ?? null;
 * ```
 */
export interface SafeLoadOptions<T> {
  /** 失败时返回的兜底值, 默认 null */
  fallback?: T;
  /** 自定义错误文案, 默认"数据加载失败" */
  errorMsg?: string;
  /** 设为 true 则不弹 ElMessage(仍 console.error), 默认 false */
  silent?: boolean;
}

export async function safeLoad<T>(fn: () => Promise<T>, options: SafeLoadOptions<T> = {}): Promise<T | null> {
  try {
    return await fn();
  } catch (e: any) {
    if (!options.silent) {
      const msg = options.errorMsg || '数据加载失败';
      // 后端业务错误(如 RuoYi 的 {code,msg} 结构)优先用 msg 字段
      const detail = e?.msg || e?.message;
      ElMessage.error(detail ? `${msg}: ${detail}` : msg);
    }
    console.error('[safeLoad]', e);
    return options.fallback ?? null;
  }
}
