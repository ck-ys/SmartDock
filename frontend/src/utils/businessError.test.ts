import { describe, expect, it } from 'vitest';
import { BusinessError, getBusinessErrorCode, isBusinessErrorCode } from './businessError';

describe('businessError', () => {
  it('保留后端业务错误码与响应数据', () => {
    const error = new BusinessError(4601, '需要安全验证', { level: 4, totalScore: 92 });

    expect(error.message).toBe('需要安全验证');
    expect(error.code).toBe(4601);
    expect(error.data).toEqual({ level: 4, totalScore: 92 });
  });

  it('兼容直接错误码和 Axios 响应中的错误码', () => {
    expect(getBusinessErrorCode({ code: 4601 })).toBe(4601);
    expect(getBusinessErrorCode({ response: { data: { code: 4602 } } })).toBe(4602);
    expect(getBusinessErrorCode({ code: 'ERR_BAD_RESPONSE', response: { data: { code: 4601 } } })).toBe(4601);
    expect(getBusinessErrorCode(new Error('普通错误'))).toBeUndefined();
  });

  it('仅匹配指定业务错误码', () => {
    expect(isBusinessErrorCode({ code: 4601 }, 4601)).toBe(true);
    expect(isBusinessErrorCode({ code: 4602 }, 4601)).toBe(false);
  });
});
