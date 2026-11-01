/**
 * 助手会话标题兜底展示（2026-10-30 题3）。
 *
 * <p>后端已在 AssistantTitleService.cleanTitle 堵住「半截 JSON 当标题」写库，迁移脚本清历史残留；
 * 这里再挡一层显示：历史脏标题不许把 `{"title":"按周复盘适合的` 这种残片露在界面上。</p>
 *
 * <p>判据只认「结构性残片」：`{` 后紧跟 `"`、`[` 后紧跟 `{`/`"`，或正文含 `"title"` 键。
 * 不能按「以 `[` 开头」一刀切——重命名允许任意 1-60 字，用户手写的 `[草稿] 周报` 会被误吞
 * （三审 P1 实锤：界面上显示「未命名会话」，重命名输入框里却还是原文，用户会以为改名失败）。</p>
 */
const TITLE_FRAGMENT = /^\s*[\[{]\s*[[{"]|"title"\s*:/;

type TitleSource = { title?: string | null; lastMessageTime?: string | null };

/**
 * 列表/页头显示的标题；脏标题退回「（未命名会话·MM-DD）」，没有时间就给「（未命名会话）」。
 */
export function displayTitle(c: TitleSource): string {
  const raw = (c.title ?? '').trim();
  if (raw && !TITLE_FRAGMENT.test(raw)) {
    return raw;
  }
  const stamp = (c.lastMessageTime ?? '').slice(5, 10);
  return stamp ? `（未命名会话·${stamp}）` : '（未命名会话）';
}

/**
 * 重命名输入框的初值（2026-10-31 题2）：脏标题回填空串，别把 JSON 残片塞进输入框——
 * 否则用户看到的是「（未命名会话）」，编辑时看到的是残片，直接回车还会因为「没变化」被静默忽略，
 * 看起来就是改名失败。
 */
export function renameSeed(c: TitleSource): string {
  const raw = (c.title ?? '').trim();
  return raw && !TITLE_FRAGMENT.test(raw) ? raw : '';
}

/**
 * 提交改名前的「没变化」判据（2026-11-01 题8）：必须和回填用同一把尺子。
 * 回填写的是 `renameSeed`（脏标题给空串），若这里拿原文 `c.title` 比，
 * 用户对脏标题原样回车会被判成「有变化」而白发一次请求；反过来正常标题
 * 带首尾空白（`" 周报 "`）又会被判成「有变化」而改掉用户没改的名称。
 */
export function shouldRename(next: string | null | undefined, c: TitleSource): boolean {
  const t = (next ?? '').trim();
  return !!t && t !== renameSeed(c);
}
