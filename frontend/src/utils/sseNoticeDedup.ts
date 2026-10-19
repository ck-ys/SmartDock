const NOTICE_TTL_MS = 10 * 60 * 1000;
const MAX_RECENT_DELIVERIES = 500;
const recentDeliveries = new Map<string, number>();

type DurableNoticeIdentity = {
  outboxId?: string | number | null;
  businessDedupKey?: string | null;
};

export const shouldDisplayDcsNotice = (notice: DurableNoticeIdentity, now = Date.now()): boolean => {
  for (const [key, timestamp] of recentDeliveries) {
    if (now - timestamp > NOTICE_TTL_MS) recentDeliveries.delete(key);
  }
  const rawKey = notice?.outboxId ?? notice?.businessDedupKey;
  if (rawKey === undefined || rawKey === null || rawKey === '') return true;
  const key = String(rawKey);
  const previous = recentDeliveries.get(key);
  if (previous !== undefined && now - previous <= NOTICE_TTL_MS) return false;
  recentDeliveries.set(key, now);
  while (recentDeliveries.size > MAX_RECENT_DELIVERIES) {
    const oldest = recentDeliveries.keys().next().value;
    if (oldest === undefined) break;
    recentDeliveries.delete(oldest);
  }
  return true;
};

export const resetDcsNoticeDedupForTest = () => recentDeliveries.clear();
