import { describe, expect, it } from 'vitest';
import { pageNameOf, roleOf, roleLabelOf, suggestionsFor, taskCtxOf, turnStateWord } from './assistantCtx';

describe('assistantCtx 身份判定', () => {
  it('member 优先于其它角色', () => {
    expect(roleOf(['member', 'publisher'])).toBe('member');
  });
  it('admin/superadmin/通配权限=管理员', () => {
    expect(roleOf(['admin'])).toBe('admin');
    expect(roleOf(['superadmin'])).toBe('admin');
    expect(roleOf(['x'], ['*:*:*'])).toBe('admin');
  });
  it('其余（含 publisher）判发布人', () => {
    expect(roleOf(['publisher'])).toBe('publisher');
    expect(roleOf([])).toBe('publisher');
  });
  it('徽标文案三态', () => {
    expect(roleLabelOf('publisher')).toBe('发布人');
    expect(roleLabelOf('admin')).toBe('管理员');
    expect(roleLabelOf('member')).toBe('成员');
  });
});

describe('assistantCtx 页面名与任务上下文', () => {
  it('meta.title 优先，query.title 兜底，再兜底工作台', () => {
    expect(pageNameOf({ meta: { title: '任务管理' } })).toBe('任务管理');
    expect(pageNameOf({ meta: {}, query: { title: '通知中心' } })).toBe('通知中心');
    expect(pageNameOf({})).toBe('工作台');
  });
  it('任务详情页提取 missionId/名称，其余页为空', () => {
    expect(taskCtxOf({ path: '/dcs/mission/detail/123', query: { missionName: '追加测试' } })).toEqual({
      missionId: '123',
      missionName: '追加测试'
    });
    // path 参数形态无名称时只给 id（名称由宿主懒取）
    expect(taskCtxOf({ path: '/dcs/mission/detail/123' })).toEqual({ missionId: '123', missionName: undefined });
    expect(taskCtxOf({ path: '/dcs/mission/detail', query: { missionId: '456' } })).toEqual({ missionId: '456', missionName: undefined });
    expect(taskCtxOf({ path: '/dcs/todo' })).toEqual({});
  });
});

describe('assistantCtx 页面感知建议池', () => {
  it('命中页面池且 ≥6 张支撑换一批', () => {
    const pool = suggestionsFor('任务管理', 'publisher');
    expect(pool.length).toBeGreaterThanOrEqual(6);
    expect(pool.some((s) => s[1] === '怎么催办')).toBe(true);
  });
  it('未命中页面回落通用池', () => {
    expect(suggestionsFor('不存在的页面', 'publisher').some((s) => s[1] === '我的待办')).toBe(true);
  });
  it('成员池彻底无催办词（v12:76 断言守护口径）', () => {
    for (const page of ['任务管理', '通知中心', '随便一页']) {
      const pool = suggestionsFor(page, 'member');
      expect(pool.length).toBeGreaterThanOrEqual(4);
      for (const s of pool) {
        expect(s[3].includes('催办')).toBe(false);
        expect(s[1].includes('催')).toBe(false);
      }
    }
  });
});

describe('assistantCtx Q1 工作态词表 turnStateWord（2026-11-07 拍板，照 ZCode）', () => {
  it('进行中三相位收成一个词「工作中」；终态/等待各一词', () => {
    expect(turnStateWord('thinking')).toBe('工作中');
    expect(turnStateWord('acting')).toBe('工作中');
    expect(turnStateWord('writing')).toBe('工作中');
    expect(turnStateWord('waiting')).toBe('等你确认');
    expect(turnStateWord('done')).toBe('已工作');
    expect(turnStateWord('stopped')).toBe('已停止');
    expect(turnStateWord('error')).toBe('失败');
  });
  it('acting 的动宾补语不再拼进主词（阶段由思考行/工具行自己表达）', () => {
    expect(turnStateWord('acting', { actingVerb: '正在查询任务库' })).toBe('工作中');
  });
  it('旧词表全部消灭（思考中/执行中/撰写中/等确认/已完成/正在想/做完了 不得再出现）', () => {
    const all = ['thinking', 'acting', 'writing', 'waiting', 'done', 'stopped', 'error'].map((p) => turnStateWord(p)).join('|');
    for (const banned of ['思考中', '执行中', '撰写中', '等确认', '已完成', '正在想', '正在做', '做完了']) {
      expect(all.includes(banned)).toBe(false);
    }
  });
  it('未知相位兜底「进行中」（回放脏值不炸）', () => {
    expect(turnStateWord('')).toBe('进行中');
    expect(turnStateWord('weird')).toBe('进行中');
  });
});
