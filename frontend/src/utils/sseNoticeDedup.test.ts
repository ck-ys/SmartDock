import { beforeEach, describe, expect, it } from 'vitest';
import { resetDcsNoticeDedupForTest, shouldDisplayDcsNotice } from './sseNoticeDedup';

describe('DCS SSE notice deduplication', () => {
  beforeEach(() => resetDcsNoticeDedupForTest());

  it('suppresses replay of the same durable outbox delivery', () => {
    const notice = { outboxId: 700, businessDedupKey: 'review:700' };
    expect(shouldDisplayDcsNotice(notice, 1_000)).toBe(true);
    expect(shouldDisplayDcsNotice(notice, 1_001)).toBe(false);
  });

  it('keeps legacy events without a stable delivery id compatible', () => {
    expect(shouldDisplayDcsNotice({ title: 'legacy' }, 1_000)).toBe(true);
    expect(shouldDisplayDcsNotice({ title: 'legacy' }, 1_001)).toBe(true);
  });

  it('allows a delivery again after the bounded ttl', () => {
    const notice = { businessDedupKey: 'review:701' };
    expect(shouldDisplayDcsNotice(notice, 1_000)).toBe(true);
    expect(shouldDisplayDcsNotice(notice, 601_001)).toBe(true);
  });
});
