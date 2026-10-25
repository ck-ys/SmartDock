import { defineStore } from 'pinia';
import { getFileReviewPendingCount } from '@/api/dcs/fileReview';
import { useUserStore } from './user';

/**
 * 文件审核待审徽标（12b，Q7-A）
 *
 * 仅有 dcs:fileReview:list 权限的用户启动 60s 轮询；无权限者 ensureStarted 直接空跑。
 * 轮询失败静默（徽标是非关键信息，不打扰用户）；审核动作后可调 decrement 本地修正，
 * 下轮轮询自然对齐真值。
 */
export const useFileReviewBadgeStore = defineStore('fileReviewBadge', () => {
  const count = ref(0);
  let timer: ReturnType<typeof setInterval> | null = null;
  let started = false;

  const canView = () => {
    const userStore = useUserStore();
    const perms: string[] = userStore.permissions || [];
    return perms.includes('*:*:*') || perms.includes('dcs:fileReview:list');
  };

  const fetchPendingCount = async () => {
    if (!canView()) return;
    try {
      const res = await getFileReviewPendingCount();
      count.value = Number(res.data) || 0;
    } catch {
      // 静默失败：徽标轮询不打扰用户
    }
  };

  /**
   * 启动轮询（幂等）：多个 SidebarItem 实例都会调用，store 内 started 保证只起一个定时器
   */
  const ensureStarted = () => {
    if (started || !canView()) return;
    started = true;
    fetchPendingCount();
    timer = setInterval(fetchPendingCount, 60_000);
  };

  /**
   * 徽标取数（声明式，题3-④）：SidebarItem 只带 meta.badge 键来问数，
   * 键到数据的映射收敛在这里；目前只有文件审核一种，以后加徽标在此扩展
   */
  const badgeCount = (badge: string): number => (badge === 'fileReview' ? count.value : 0);

  // 题3-⑤ 切换单位后立即对齐新单位的待审数（原来最长要等 60s 轮询）
  if (typeof window !== 'undefined' && !(window as any).__dcsBadgeTenantListener) {
    // store 重建（HMR/测试）会重复挂监听导致一次事件多次拉数，模块级旗子防重
    (window as any).__dcsBadgeTenantListener = true;
    window.addEventListener('dcs:tenant-changed', () => {
      fetchPendingCount();
    });
  }

  /** 审核通过 N 条后的本地即时修正（下一轮轮询对齐真值） */
  const decrement = (n = 1) => {
    count.value = Math.max(0, count.value - n);
  };

  const stop = () => {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
    started = false;
    count.value = 0;
  };

  return { count, badgeCount, fetchPendingCount, ensureStarted, decrement, stop };
});
