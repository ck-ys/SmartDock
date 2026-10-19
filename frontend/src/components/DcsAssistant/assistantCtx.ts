/**
 * 助手上下文纯函数（PROTO PARITY 批A）：身份判定 / 页面名 / 页面感知建议池。
 *
 * <p>为什么独立成文件：三宿主（抽屉/全屏/会话页）与 hero/头部都要用，且需要单测覆盖
 * （成员池剔除催办词是原型 v12:76 断言守护口径）。</p>
 */

/** 建议卡四元组：emoji / 短标题 / 口语补语 / 实际发送指令（v3.17） */
export type SugItem = [string, string, string, string];

export type RoleKey = 'publisher' | 'admin' | 'member';

/** 身份判定：与 detail.vue 既有口径一致（member 优先；admin/superadmin=管理员；其余发布人） */
export function roleOf(roles: string[], permissions: string[] = []): RoleKey {
  if (roles.includes('member')) {
    return 'member';
  }
  if (roles.includes('admin') || roles.includes('superadmin') || permissions.includes('*:*:*')) {
    return 'admin';
  }
  return 'publisher';
}

export function roleLabelOf(role: RoleKey): string {
  return { publisher: '发布人', admin: '管理员', member: '成员' }[role];
}

/** 当前页面中文名：route.meta.title 兜底 route.query.title（InnerLink 场景），再兜底「工作台」 */
export function pageNameOf(route: { meta?: { title?: string }; query?: { title?: unknown } }): string {
  const meta = typeof route.meta?.title === 'string' ? route.meta.title.trim() : '';
  if (meta) {
    return meta;
  }
  const q = typeof route.query?.title === 'string' ? (route.query.title as string).trim() : '';
  return q || '工作台';
}

/** 任务详情页上下文：ctxLine 变「任务名 ▾」chip 的判定（详情页才有单一任务可锚定） */
export function taskCtxOf(route: { path?: string; query?: Record<string, unknown>; params?: Record<string, unknown> }): {
  missionId?: string;
  missionName?: string;
} {
  const path = route.path ?? '';
  // 实际路由形态①：/dcs/mission/detail/:id（任务列表 goDetailPage）；②：/dcs/mission/detail?missionId=（深链）
  const m = path.match(/^\/dcs\/mission\/detail\/(\d+)/);
  const id = m ? m[1] : (route.query?.missionId ?? route.params?.missionId ?? route.query?.id);
  if (!id) {
    return {};
  }
  const name = route.query?.missionName ?? route.query?.name ?? route.query?.title;
  return {
    missionId: String(id),
    missionName: typeof name === 'string' && name.trim() ? name.trim() : undefined
  };
}

/** 通用池（v3.17 全员通用；成员侧由 suggestionsFor 剔除催办词） */
const GENERIC_POOL: SugItem[] = [
  ['clipboard', '我的待办', '今天该干什么', '我今天有哪些待办？'],
  ['alarm', '谁逾期了', '名下任务逾期情况', '我名下的任务有谁逾期了吗？'],
  ['map-pin', '任务进度', '某个任务交了多少', '我发布的任务提交进度怎么样？'],
  ['megaphone', '发催办', '给没交的人提个醒', '帮我对还没提交的人发一次催办提醒。'],
  ['search', '查提交明细', '某人交没交、交的什么', '帮我查一下某个成员的提交状态。'],
  ['clock', '截止提醒', '最近要到期的事', '最近三天有哪些任务要截止了？'],
  ['file', '怎么操作', '不会用的功能问它', '退回重做该怎么操作？'],
  ['chart', '看板口径', '数字对不上时问它', '看板里的"已提交"是怎么算的？']
];

/** 页面感知池：按页面名出建议（每池 ≥6 张支撑「换一批」轮换；未命中回落通用池） */
const PAGE_POOLS: Record<string, SugItem[]> = {
  任务管理: [
    ['clipboard', '我的待办', '今天该干什么', '我今天有哪些待办？'],
    ['alarm', '谁逾期了', '名下任务逾期情况', '我名下的任务有谁逾期了吗？'],
    ['map-pin', '任务进度', '某个任务交了多少', '我发布的任务提交进度怎么样？'],
    ['megaphone', '怎么催办', '未交成员三步催', '怎么给还没提交的成员发催办？'],
    ['search', '查提交明细', '某人交没交、交的什么', '帮我查一下某个成员的提交状态。'],
    ['file', '广播任务是什么', '30 秒讲清', '广播任务是什么？和普通任务有什么区别？']
  ],
  我的待办: [
    ['clipboard', '我的待办', '按轻重排个序', '我今天有哪些待办？按紧急程度排一下。'],
    ['alarm', '逾期任务', '哪些已经晚了', '我有哪些待办已经逾期了？'],
    ['clock', '截止提醒', '最近要到期的事', '最近三天有哪些任务要截止了？'],
    ['file', '怎么操作', '不会用的功能问它', '退回重做该怎么操作？'],
    ['search', '查提交明细', '某人交没交、交的什么', '帮我查一下某个成员的提交状态。'],
    ['chart', '看板口径', '数字对不上时问它', '看板里的"已提交"是怎么算的？']
  ],
  统计看板: [
    ['chart', '看板口径', '数字对不上时问它', '看板里的"已提交"是怎么算的？'],
    ['map-pin', '任务进度', '某个任务交了多少', '我发布的任务提交进度怎么样？'],
    ['alarm', '逾期分析', '哪些晚了为什么', '我发布的任务里逾期情况怎么样？'],
    ['clipboard', '我的待办', '今天该干什么', '我今天有哪些待办？'],
    ['clock', '截止提醒', '最近要到期的事', '最近三天有哪些任务要截止了？'],
    ['file', '指标解释', '口径一句话讲清', '"一次通过率"这个指标是怎么算的？']
  ],
  通知中心: [
    ['megaphone', '怎么催办', '未交成员三步催', '怎么给还没提交的成员发催办？'],
    ['clipboard', '我的待办', '今天该干什么', '我今天有哪些待办？'],
    ['file', '广播任务是什么', '30 秒讲清', '广播任务是什么？和普通任务有什么区别？'],
    ['alarm', '谁逾期了', '名下任务逾期情况', '我名下的任务有谁逾期了吗？'],
    ['clock', '截止提醒', '最近要到期的事', '最近三天有哪些任务要截止了？'],
    ['search', '查提交明细', '某人交没交、交的什么', '帮我查一下某个成员的提交状态。']
  ],
  首页: [
    ['clipboard', '我的待办', '今天该干什么', '我今天有哪些待办？'],
    ['alarm', '谁逾期了', '名下任务逾期情况', '我名下的任务有谁逾期了吗？'],
    ['clock', '截止提醒', '最近要到期的事', '最近三天有哪些任务要截止了？'],
    ['file', '怎么操作', '不会用的功能问它', '退回重做该怎么操作？'],
    ['chart', '看板口径', '数字对不上时问它', '看板里的"已提交"是怎么算的？'],
    ['map-pin', '任务进度', '某个任务交了多少', '我发布的任务提交进度怎么样？']
  ]
};

/** 成员侧剔除催办类建议（原型断言守护：成员面彻底没有催办词，不可见≠置灰） */
function stripForRole(pool: SugItem[], role: RoleKey): SugItem[] {
  if (role !== 'member') {
    return pool;
  }
  return pool.filter((s) => !s[3].includes('催办') && !s[1].includes('催'));
}

/** 页面感知建议池：页面名命中出页面池，否则通用池；成员剔除催办 */
export function suggestionsFor(pageName: string, role: RoleKey): SugItem[] {
  const pool = PAGE_POOLS[pageName] ?? GENERIC_POOL;
  return stripForRole(pool, role);
}

/** 回合相位（useAssistant.AssistantTurn.phase 的字面量族；字符串宽松收参防回放脏值） */
export type TurnPhase = 'thinking' | 'acting' | 'writing' | 'waiting' | 'done' | 'stopped' | 'error';

/**
 * Q10 统一状态词表（2026-10-09 拍板，全站唯一口径）：
 * 思考中 → 执行中(正在查询任务库…) → 撰写中 → 等确认 → 已完成/已停止/失败。
 * TurnBlock 状态条与 StatPanel 面板头共用本函数，杀掉「正在想/正在做/做完了/进程/出了点问题」
 * 两套历史各说各的词（同批两次实现各写各的历史产物）。
 *
 * @param extra.actingVerb acting 态的动宾补语（如「正在查询任务库」），无则只显示主词
 */
export function turnStateWord(phase: TurnPhase | string, extra?: { actingVerb?: string }): string {
  switch (phase) {
    case 'thinking':
      return '思考中';
    case 'acting':
      return extra?.actingVerb ? `执行中（${extra.actingVerb}…）` : '执行中';
    case 'writing':
      return '撰写中';
    case 'waiting':
      return '等确认';
    case 'done':
      return '已完成';
    case 'stopped':
      return '已停止';
    case 'error':
      return '失败';
    default:
      return '进行中';
  }
}
