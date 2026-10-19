import { describe, expect, it } from 'vitest';
import {
  CAPTCHA_CHALLENGE_REQUIRED_CODE,
  captchaActionForChat,
  ensureChatClientMsgId,
  isCaptchaChallengeRequired,
  resolveCaptchaPassToken,
  withCaptchaPass
} from './captchaChallengeFlow';

describe('captchaChallengeFlow', () => {
  it('把挑战绑定到发起发送的精确会话', () => {
    expect(captchaActionForChat('2080185813045399554')).toBe('chat:2080185813045399554');
  });

  it('把一次性通行令牌注入同一 clientMsgId 的重试请求且不修改原对象', () => {
    const payload = {
      convId: '2080185813045399554',
      msgType: 'TEXT' as const,
      content: '待验证消息',
      clientMsgId: 'client-001'
    };

    const retry = withCaptchaPass(payload, 'pass-token');

    expect(retry).toEqual({ ...payload, captchaPassToken: 'pass-token' });
    expect(retry.clientMsgId).toBe('client-001');
    expect(payload).not.toHaveProperty('captchaPassToken');
  });

  it('入队前分配稳定 clientMsgId，已有编号保持不变', () => {
    const payload = { convId: '1', msgType: 'TEXT' as const, content: '消息' };
    const assigned = ensureChatClientMsgId(payload);

    expect(assigned.clientMsgId).toMatch(/^cli_/);
    expect(ensureChatClientMsgId(assigned)).toBe(assigned);
  });

  it('以后端 4601 为权威挑战信号', () => {
    expect(CAPTCHA_CHALLENGE_REQUIRED_CODE).toBe(4601);
    expect(isCaptchaChallengeRequired({ code: 4601 })).toBe(true);
    expect(isCaptchaChallengeRequired({ response: { data: { code: 4601 } } })).toBe(true);
    expect(isCaptchaChallengeRequired({ code: 500 })).toBe(false);
  });

  it('L4 图片阶段通过后仍需 OTP，不能提前返回通行令牌', () => {
    expect(resolveCaptchaPassToken({ verified: true, needOtp: true })).toBeUndefined();
  });

  it('只有完整验证通过且服务端返回令牌时才允许重试', () => {
    expect(resolveCaptchaPassToken({ verified: false, needOtp: false, captchaPassToken: 'unexpected' })).toBeUndefined();
    expect(resolveCaptchaPassToken({ verified: true, needOtp: false })).toBeUndefined();
    expect(resolveCaptchaPassToken({ verified: true, needOtp: false, captchaPassToken: 'pass-token' })).toBe('pass-token');
  });
});
