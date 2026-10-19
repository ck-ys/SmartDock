import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { myNotices, markNoticeRead } from '@/api/dcs/core/notice';
import { useNoticeStore } from './notice';

vi.mock('@/api/dcs/core/notice', () => ({
  myNotices: vi.fn(),
  markNoticeRead: vi.fn(() => Promise.resolve())
}));

// PERF-HOME(2026-09-19): 未读数 in-flight 单飞——冷启动顶栏与首页并发刷, 只允许发一次请求
// 批5: 未读数改由 /my?readFlag=0&pageSize=1 的 total 推导(微核心无 unread-count 专端点)
describe('notice store unread single-flight', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.mocked(myNotices).mockReset();
  });

  it('并发两次 refreshUnreadCount 只发一次请求, 两调用方都拿到结果', async () => {
    let resolveApi: (v: { total: number }) => void = () => {};
    vi.mocked(myNotices).mockReturnValue(
      new Promise((r) => {
        resolveApi = r;
      }) as any
    );
    const store = useNoticeStore();
    const p1 = store.refreshUnreadCount();
    const p2 = store.refreshUnreadCount();
    resolveApi({ total: 7 });
    await Promise.all([p1, p2]);
    expect(myNotices).toHaveBeenCalledTimes(1);
    expect(store.state.unreadCount).toBe(7);
  });

  it('失败后 inFlight 清空, 下次调用可重试', async () => {
    vi.mocked(myNotices)
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ total: 3 } as any);
    const store = useNoticeStore();
    await expect(store.refreshUnreadCount()).rejects.toThrow('boom');
    await store.refreshUnreadCount();
    expect(myNotices).toHaveBeenCalledTimes(2);
    expect(store.state.unreadCount).toBe(3);
  });

  // 三审 P1(2026-09-19): 本地增减前作废在飞响应——单飞共享的在飞请求携带旧快照,
  // 落地时不得覆盖 addNotice(+1)/readAll(置0) 刚写好的本地值
  it('在飞期间 addNotice 的 +1 不被旧快照覆盖', async () => {
    let resolveR: (v: { total: number }) => void = () => {};
    vi.mocked(myNotices).mockReturnValue(
      new Promise((r) => {
        resolveR = r;
      }) as any
    );
    const store = useNoticeStore();
    const p = store.refreshUnreadCount(); // 发出旧请求(此刻服务端还没收到新通知)
    store.addNotice({ message: {}, time: '' }); // 推送到达: 本地 0→1 且作废在飞
    resolveR({ total: 5 }); // 旧快照落地(推送前口径), 须被守卫丢弃
    await p;
    expect(store.state.unreadCount).toBe(1);
  });

  it('在飞期间 readAll 的置 0 不被旧快照覆盖', async () => {
    let resolveR: (v: { total: number }) => void = () => {};
    vi.mocked(myNotices).mockReturnValue(
      new Promise((r) => {
        resolveR = r;
      }) as any
    );
    const store = useNoticeStore();
    store.state.unreadCount = 8;
    const p = store.refreshUnreadCount();
    await store.readAll();
    resolveR({ total: 8 }); // 点击前快照落地
    await p;
    expect(store.state.unreadCount).toBe(0);
  });

  it('fetchRecent 把 /my 行映射为铃铛统一形状(noticeId/readStatus)', async () => {
    vi.mocked(myNotices).mockResolvedValue({
      total: 1,
      rows: [{ id: '194001', noticeType: 'TASK_SUBMIT', title: '新提交', readFlag: '0', createTime: '2026-10-18 10:00:00' }]
    } as any);
    const store = useNoticeStore();
    await store.fetchRecent();
    expect(store.state.notices).toHaveLength(1);
    const item: any = store.state.notices[0];
    expect(item.noticeId).toBe('194001');
    expect(item.readStatus).toBe('0');
    expect(store.state.unreadCount).toBe(1);
  });
});
