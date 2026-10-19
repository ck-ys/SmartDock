import { describe, expect, it } from 'vitest';
import { humanizeAiError } from './aiErrorText';

/** 业务反馈批1015c Q9：英文错误原文→中文短语映射（聊天卡与用量页共用） */
describe('humanizeAiError', () => {
  it('库层流超时原文归类为超时（含无 code 的窄正则兜底）', () => {
    const raw =
      "HTTP transport error during streaming: Stream timeout: Did not observe any item or terminal signal within first signal from a Publisher in 'subscribeOn' (and no fallback has been configured)";
    const h = humanizeAiError(raw);
    expect(h.title).toBe('回答超时了，可能是网络或服务繁忙');
    expect(h.detail).toBe(raw);
    expect(h.chat).toContain('重试');
    expect(humanizeAiError(undefined, 'TIMEOUT_ERROR').title).toBe('回答超时了，可能是网络或服务繁忙');
  });

  it('常见厂商错误按规则归类，原文保留在 detail', () => {
    expect(humanizeAiError('Rate limit reached, 429').title).toBe('请求太频繁或额度受限，稍等片刻再试');
    expect(humanizeAiError('Connection refused to api.deepseek.com').title).toBe('网络连不上服务，请检查网络后重试');
    expect(humanizeAiError('Insufficient credit balance').title).toBe('余额或积分不足，请充值或订阅套餐');
    expect(humanizeAiError('Unauthorized: invalid api key').title).toBe('密钥或登录已失效，请联系管理员');
  });

  it('INTERNAL_ERROR 兜底码不误报网络问题，且优先级低于规则命中', () => {
    expect(humanizeAiError('some NPE', 'INTERNAL_ERROR').title).toBe('服务这边出了点问题，已记录');
    // 429 被框架包成 INTERNAL_ERROR 时按更具体的规则归类
    expect(humanizeAiError('429 too many requests', 'INTERNAL_ERROR').title).toBe('请求太频繁或额度受限，稍等片刻再试');
  });

  it('未知错误按长度/语种分流：短消息与中文原文直出（透传契约），长英文串收进兜底', () => {
    // 短英文/中文：原样直出（工具失败人话文案、契约错误原文等信息保真）
    expect(humanizeAiError('Agent run failed: unresolved interrupts').chat).toBe('Agent run failed: unresolved interrupts');
    expect(humanizeAiError('查询超时了').chat).toBe('查询超时了');
    // 长英文串（库层堆栈/厂商原文）：不裸出吓人，原文进折叠
    const longEn = 'java.lang.RuntimeException: ' + 'x'.repeat(120);
    expect(humanizeAiError(longEn).chat).toBe('服务开小差了，请稍后再试');
    expect(humanizeAiError(longEn).detail).toBe(longEn);
    expect(humanizeAiError('').title).toBe('服务开小差了，请稍后再试');
    expect(humanizeAiError('').detail).toBe('');
  });
});
