import { defineStore } from 'pinia';
import { reactive } from 'vue';
import { myNotices, markNoticeRead, type DcsNoticeRow } from '@/api/dcs/core/notice';
import { isPersistedNotice, isNoticeUnread, markNoticeItemRead } from './notice-helpers';

interface RealtimeNoticeItem {
  title?: string;
  read?: boolean;
  message: any;
  time: string;
}

/** 持久化通知(铃铛侧统一形状): /dcs/notice/my 行映射为 helpers 认识的 noticeId/readStatus */
export interface PersistedNoticeItem {
  noticeId: string;
  noticeType?: string;
  title?: string;
  content?: string;
  taskId?: string;
  refUrl?: string;
  readStatus: string;
  createTime?: string;
}

export type NoticeItem = PersistedNoticeItem | RealtimeNoticeItem;

const toPersisted = (row: DcsNoticeRow): PersistedNoticeItem => ({
  noticeId: row.id,
  noticeType: row.noticeType,
  title: row.title,
  content: row.content,
  taskId: row.taskId,
  refUrl: row.refUrl,
  readStatus: row.readFlag,
  createTime: row.createTime
});

export const useNoticeStore = defineStore('notice', () => {
  let unreadRequestSequence = 0;
  // PERF-HOME(2026-09-19): in-flight 单飞——冷启动时顶栏与首页并发刷未读数, 共享同一请求;
  // finally 清空保证失败后下次调用能重试(不能把失败的 promise 缓存住)
  let unreadInFlight: Promise<void> | null = null;
  const state = reactive({
    notices: [] as NoticeItem[],
    unreadCount: 0,
    loading: false
  });

  // 微核心无 unread-count 专端点(批5), 未读数 = /my?readFlag=0 的 total
  const refreshUnreadCount = async () => {
    if (unreadInFlight) return unreadInFlight;
    const sequence = ++unreadRequestSequence;
    unreadInFlight = (async () => {
      try {
        const res: any = await myNotices({ readFlag: '0', pageNum: 1, pageSize: 1 });
        if (sequence === unreadRequestSequence) {
          state.unreadCount = Number(res?.total ?? res?.data?.total ?? 0);
        }
      } finally {
        unreadInFlight = null;
      }
    })();
    return unreadInFlight;
  };

  const fetchRecent = async () => {
    state.loading = true;
    try {
      // PERF-HOME: 未读数不依赖通知列表, 并行拉取让铃铛数字早一拍出现;
      // allSettled 防连坐——未读数单点失败不得拖垮通知列表(列表成功就照常渲染, 三审 P1)
      const [listResult] = await Promise.allSettled([myNotices({ pageNum: 1, pageSize: 8 }) as any, refreshUnreadCount()]);
      if (listResult.status === 'fulfilled') {
        const rows: DcsNoticeRow[] = listResult.value?.rows || [];
        state.notices = rows.map(toPersisted);
      }
    } finally {
      state.loading = false;
    }
  };

  // 三审 P1(2026-09-19): 本地增减未读数前作废在飞响应——单飞下并发调用共享同一请求且
  // 不递增序列号, 在飞旧快照落地会把刚做的 +1/-1/置0 覆盖回旧值; 递增序列号让守卫丢弃它
  const invalidateInFlightUnread = () => {
    unreadRequestSequence += 1;
  };

  const addNotice = (notice: RealtimeNoticeItem) => {
    state.notices.unshift(notice);
    invalidateInFlightUnread();
    state.unreadCount += 1;
  };

  const removeNotice = (notice: NoticeItem) => {
    const index = state.notices.indexOf(notice);
    if (index >= 0) {
      state.notices.splice(index, 1);
    }
  };

  const readNotice = async (notice: NoticeItem) => {
    if (isPersistedNotice(notice)) {
      const wasUnread = isNoticeUnread(notice);
      await markNoticeRead(notice.noticeId);
      markNoticeItemRead(notice);
      if (wasUnread) {
        invalidateInFlightUnread();
        state.unreadCount = Math.max(0, state.unreadCount - 1);
      }
      await fetchRecent();
      return;
    }
    if (markNoticeItemRead(notice)) {
      invalidateInFlightUnread();
      state.unreadCount = Math.max(0, state.unreadCount - 1);
    }
  };

  // 微核心无 read-all 专端点(批5): 对当前列表里未读的持久化通知逐条 /read
  const readAll = async () => {
    const targets = state.notices.filter((n) => isPersistedNotice(n) && isNoticeUnread(n));
    await Promise.allSettled(targets.map((n) => markNoticeRead((n as PersistedNoticeItem).noticeId)));
    state.notices.forEach((item) => markNoticeItemRead(item));
    invalidateInFlightUnread();
    state.unreadCount = 0;
    // recent 只装最近 8 条, 之外的未读没被逐条标读——发一次校准但不阻塞置 0:
    // 有在飞请求时校准被序号守卫丢弃(旧快照不得覆盖置 0), 空闲时以服务端 total 收口
    void refreshUnreadCount().catch(() => undefined);
  };

  const clearNotice = () => {
    state.notices = [];
    invalidateInFlightUnread();
    state.unreadCount = 0;
  };

  return {
    state,
    addNotice,
    removeNotice,
    readNotice,
    readAll,
    clearNotice,
    fetchRecent,
    refreshUnreadCount
  };
});
