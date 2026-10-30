import { describe, expect, it } from 'vitest';
import { displayTitle } from './assistantTitle';

/**
 * 题3 标题兜底（2026-10-30）：残片要藏，用户手写的标题一个字都不能动。
 */
describe('displayTitle', () => {
  it('挡住历史脏标题', () => {
    expect(displayTitle({ title: '{"title":"按周复盘适合的', lastMessageTime: '2026-10-29 08:50:00' }))
      .toBe('（未命名会话·10-29）');
    expect(displayTitle({ title: '[{"title":"数组残片' })).toBe('（未命名会话）');
    expect(displayTitle({ title: '复盘 {"title": 变量' })).toBe('（未命名会话）');
    expect(displayTitle({ title: '   { "title": "带空格"' })).toBe('（未命名会话）');
  });

  it('用户手写的标题原样显示（三审 P1：不能按「以 [ 开头」一刀切）', () => {
    expect(displayTitle({ title: '[草稿] 周报' })).toBe('[草稿] 周报');
    expect(displayTitle({ title: '[重要] 客户投诉' })).toBe('[重要] 客户投诉');
    expect(displayTitle({ title: '{ 待办 } 报销' })).toBe('{ 待办 } 报销');
    expect(displayTitle({ title: '周复盘适配改造' })).toBe('周复盘适配改造');
  });

  it('空标题与 null 都给兜底文案', () => {
    expect(displayTitle({ title: null })).toBe('（未命名会话）');
    expect(displayTitle({ title: '   ', lastMessageTime: '2026-10-29 08:50:00' })).toBe('（未命名会话·10-29）');
    expect(displayTitle({})).toBe('（未命名会话）');
  });
});
