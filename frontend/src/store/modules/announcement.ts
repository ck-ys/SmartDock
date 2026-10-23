import { defineStore } from 'pinia';
import { reactive } from 'vue';
import { announcementUnreadCount } from '@/api/dcs/announcement';

/**
 * 公告未读角标（N3 2026-10-23）。
 *
 * 与消息铃铛（dcs_notice）是两套口径：这里只统计「公告」页签下未读的已发布公告。
 * 单飞 + 序号守卫：冷启动顶栏与公告页会并发刷，本地标读后旧快照不得覆盖新数字。
 */
export const useAnnouncementStore = defineStore('announcement', () => {
  let sequence = 0;
  let inFlight: Promise<void> | null = null;
  /** 单飞期间又来了一次刷新请求：等当前请求落地后补发，避免新校准值被丢弃。 */
  let pendingRefresh = false;

  const state = reactive({
    unreadCount: 0
  });

  const refreshUnreadCount = async (): Promise<void> => {
    if (inFlight) {
      pendingRefresh = true;
      return inFlight;
    }
    pendingRefresh = false;
    const current = ++sequence;
    inFlight = (async () => {
      try {
        const res: any = await announcementUnreadCount();
        if (current === sequence) {
          state.unreadCount = Number(res?.data ?? res ?? 0);
        }
      } catch {
        // 角标失败不打扰用户，保持旧值
      } finally {
        inFlight = null;
        if (pendingRefresh) {
          pendingRefresh = false;
          void refreshUnreadCount();
        }
      }
    })();
    return inFlight;
  };

  /** 读掉一条：先本地减一（角标立即响应），再向服务端校准。 */
  const consumeOne = () => {
    sequence += 1;
    state.unreadCount = Math.max(0, state.unreadCount - 1);
    void refreshUnreadCount().catch(() => undefined);
  };

  return { state, refreshUnreadCount, consumeOne };
});
