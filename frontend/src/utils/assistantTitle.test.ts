import { describe, expect, it } from 'vitest';
import { displayTitle, renameSeed, shouldRename } from './assistantTitle';

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

/**
 * 题2（2026-10-31）：重命名输入框的初值——脏标题回填空串，正常标题原样回填。
 */
describe('renameSeed', () => {
  it('脏标题回填空串（不让残片进输入框）', () => {
    expect(renameSeed({ title: '{"title":"按周复盘适合的' })).toBe('');
    expect(renameSeed({ title: '[{"title":"数组残片' })).toBe('');
    expect(renameSeed({ title: '复盘 {"title": 变量' })).toBe('');
  });

  it('正常标题原样回填，用户手写的方括号标题不动', () => {
    expect(renameSeed({ title: '查询待办事项' })).toBe('查询待办事项');
    expect(renameSeed({ title: '[草稿] 周报' })).toBe('[草稿] 周报');
  });

  it('空标题回填空串', () => {
    expect(renameSeed({ title: null })).toBe('');
    expect(renameSeed({ title: '   ' })).toBe('');
  });
});

/**
 * 题8（2026-11-01）：提交前的「没变化」判据必须和回填用同一把尺子（renameSeed），
 * 否则脏标题原样回车会白发一次请求、带首尾空白的正常标题会被当成改名。
 */
describe('shouldRename', () => {
  it('和回填同源：脏标题原样回车不发请求', () => {
    expect(shouldRename('', { title: '{"title":"按周复盘适合的' })).toBe(false);
    expect(shouldRename('   ', { title: '[{"title":"数组残片' })).toBe(false);
  });

  it('正常标题没改就不发请求（首尾空白不算改动）', () => {
    expect(shouldRename('查询待办事项', { title: '查询待办事项' })).toBe(false);
    expect(shouldRename('  查询待办事项  ', { title: '查询待办事项' })).toBe(false);
    expect(shouldRename('[草稿] 周报', { title: '[草稿] 周报' })).toBe(false);
  });

  it('真改了才发请求（脏标题也能被改成正常名字）', () => {
    expect(shouldRename('周复盘', { title: '查询待办事项' })).toBe(true);
    expect(shouldRename('新名字', { title: '{"title":"按周复盘适合的' })).toBe(true);
    expect(shouldRename('新名字', { title: null })).toBe(true);
  });

  it('空输入一律不发请求（清空标题不是合法改名）', () => {
    expect(shouldRename('', { title: null })).toBe(false);
    expect(shouldRename(undefined, { title: '查询待办事项' })).toBe(false);
    expect(shouldRename(null, { title: '查询待办事项' })).toBe(false);
  });
});
