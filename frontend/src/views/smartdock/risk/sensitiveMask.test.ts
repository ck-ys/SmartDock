import { describe, expect, it } from 'vitest';
import { maskSensitiveDetail, maskToken } from './sensitiveMask';

describe('maskToken', () => {
  it('长词保留首字+掩码', () => {
    expect(maskToken('abcdefgh')).toBe('a**');
    expect(maskToken('xyz')).toBe('x**');
  });

  it('两字词全掩码', () => {
    expect(maskToken('ab')).toBe('**');
    expect(maskToken('中词')).toBe('**');
  });

  it('三字符(含符号)保留首字符', () => {
    expect(maskToken('_ab')).toBe('_**');
  });

  it('一字词补足两星(不泄露唯一字符)', () => {
    expect(maskToken('a')).toBe('**');
  });

  it('中文与混合字符同样处理', () => {
    expect(maskToken('敏感词样本')).toBe('敏**');
    expect(maskToken('a中c')).toBe('a**');
  });
});

describe('maskSensitiveDetail', () => {
  it('中括号内词条被打码, 其余文字保留', () => {
    const detail = '命中敏感词: [sampleA][sampleB]';
    expect(maskSensitiveDetail(detail)).toBe('命中敏感词: [s**][s**]');
  });

  it('多个词条与中文词条混合', () => {
    // '词甲'为两字词→全掩码; 'abcde'五字词→保留首字
    expect(maskSensitiveDetail('命中敏感词: [词甲][abcde]')).toBe('命中敏感词: [**][a**]');
  });

  it('无中括号文本原样返回', () => {
    expect(maskSensitiveDetail('登录连续失败 5 次')).toBe('登录连续失败 5 次');
    expect(maskSensitiveDetail('')).toBe('');
  });

  it('冒号后缀单词条格式(库内实测)整体打码', () => {
    // 两字词全掩码; 长词保留首字
    expect(maskSensitiveDetail('命中敏感词: 傻逼')).toBe('命中敏感词: **');
    expect(maskSensitiveDetail('命中敏感词: 违禁短语样例')).toBe('命中敏感词: 违**');
    expect(maskSensitiveDetail('命中敏感词:abcde')).toBe('命中敏感词:a**');
  });

  it('空括号不误伤', () => {
    expect(maskSensitiveDetail('边界[]测试')).toBe('边界[]测试');
  });
});
