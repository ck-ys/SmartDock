import { beforeEach, describe, expect, it } from 'vitest';
import { resetNoticeDedupForTest, shouldDisplayNotice } from './sseNoticeDedup';

describe('DCS SSE notice deduplication', () => {
  beforeEach(() => resetNoticeDedupForTest());

  it('suppresses replay of the same durable outbox delivery', () => {
    const notice = { outboxId: 700, businessDedupKey: 'review:700' };
    expect(shouldDisplayNotice(notice, 1_000)).toBe(true);
    expect(shouldDisplayNotice(notice, 1_001)).toBe(false);
  });

  it('keeps legacy events without a stable delivery id compatible', () => {
    expect(shouldDisplayNotice({ title: 'legacy' } as Parameters<typeof shouldDisplayNotice>[0], 1_000)).toBe(true);
    expect(shouldDisplayNotice({ title: 'legacy' } as Parameters<typeof shouldDisplayNotice>[0], 1_001)).toBe(true);
  });

  it('allows a delivery again after the bounded ttl', () => {
    const notice = { businessDedupKey: 'review:701' };
    expect(shouldDisplayNotice(notice, 1_000)).toBe(true);
    expect(shouldDisplayNotice(notice, 601_001)).toBe(true);
  });
});
