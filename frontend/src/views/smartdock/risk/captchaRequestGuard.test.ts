import { describe, expect, it } from 'vitest';
import { createCaptchaRequestGuard } from './captchaRequestGuard';

describe('captchaRequestGuard', () => {
  it('关闭挑战后丢弃迟到的生成响应', () => {
    const guard = createCaptchaRequestGuard();
    const requestId = guard.begin();

    guard.invalidate();

    expect(guard.isCurrent(requestId)).toBe(false);
  });

  it('新上下文开始后仅接受最新请求', () => {
    const guard = createCaptchaRequestGuard();
    const oldRequestId = guard.begin();
    const newRequestId = guard.begin();

    expect(guard.isCurrent(oldRequestId)).toBe(false);
    expect(guard.isCurrent(newRequestId)).toBe(true);
    expect(guard.capture()).toBe(newRequestId);
  });
});
