/**
 * 租户下拉的显示名（2026-10-31 题7）。
 *
 * <p>原先内联在 AI 模型管理页里，回退顺序（公司名 → 联系人 → 「-」）没有任何测试；
 * 抽成纯函数后三条分支各有一条断言，改顺序会立刻报红。</p>
 */
export type TenantLabelSource = {
  tenantId: string | number;
  companyName?: string | null;
  contactUserName?: string | null;
};

/** 显示成「租户编号 / 名字」，两个名字都空时退回「-」。 */
export function tenantLabel(item: TenantLabelSource): string {
  return `${item.tenantId} / ${item.companyName || item.contactUserName || '-'}`;
}
