import { describe, expect, it } from 'vitest';
import { getNoticeContent, getNoticeKey, getNoticeTitle, isNoticeUnread, markNoticeItemRead } from './notice-helpers';
import type { NoticeItem } from './notice';

describe('notice helpers', () => {
  it('marks persisted unread notices as read locally', () => {
    const notice = { noticeId: '1001', title: '任务催办', content: '请尽快提交', readStatus: '0' } as NoticeItem;

    const changed = markNoticeItemRead(notice);

    expect(changed).toBe(true);
    expect((notice as any).readStatus).toBe('1');
    expect(isNoticeUnread(notice)).toBe(false);
  });

  it('marks realtime notices as read locally', () => {
    const notice = { title: '实时消息', message: '有新任务', time: '2026-07-05 20:30:00' } as NoticeItem;

    const changed = markNoticeItemRead(notice);

    expect(changed).toBe(true);
    expect((notice as any).read).toBe(true);
    expect(isNoticeUnread(notice)).toBe(false);
  });

  it('normalizes display fields for persisted and realtime notices', () => {
    const persisted = { noticeId: '1002', title: '审核退回', content: '需要修改', readStatus: '1' } as NoticeItem;
    const realtime = { message: '新提醒', time: '2026-07-05 20:31:00' } as NoticeItem;

    expect(getNoticeKey(persisted)).toBe('1002');
    expect(getNoticeTitle(persisted)).toBe('审核退回');
    expect(getNoticeContent(persisted)).toBe('需要修改');
    expect(getNoticeTitle(realtime)).toBe('系统消息');
    expect(getNoticeContent(realtime)).toBe('新提醒');
  });
});
