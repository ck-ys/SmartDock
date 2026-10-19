/**
 * 技能会话级选择存取（五需求批 R1，2026-10-13 拍板 Q1-A/Q2-A）。
 *
 * 单一真源=localStorage `dcs:assistant:skill:{userId}:{threadId}`（存技能 id，无键=未选），
 * ➕面板技能组/输入区胶囊与 useAssistant.runTurn 出口注入（thinkTier/modelPin/webOn 同款
 * 「内部路径不丢参」模式）三处共用。选中技能的正文由后端 SkillInjectModelDecorator
 * 当轮注入（不进会话记忆），前端只负责选哪个——正文永不下发前端，token 省在服务端。
 *
 * 前端目录与后端 SkillAssets.SKILLS 同源人工同步（id/名称/一句话描述/最低角色）；
 * minRole 口径与 roleOf 对齐：member=成员可见，publisher=发布人/管理员可见。
 * create-template 已随 R4a act_create_template 同批上（后端同款纪律）。
 */

/** storage 抽象：window.localStorage 或测试桩 */
export interface KVLike {
  getItem(k: string): string | null;
  setItem(k: string, v: string): void;
  removeItem(k: string): void;
}

/** 前端技能目录行（展示用四元组；正文在后端） */
export interface SkillCatalogItem {
  id: string;
  name: string;
  desc: string;
  /** 最低角色：'member'=全员，'publisher'=发布人/管理员（与 roleOf 的 RoleKey 对齐） */
  minRole: 'member' | 'publisher';
  tile: string;
}

export const SKILL_CATALOG: SkillCatalogItem[] = [
  { id: 'create-mission', name: '创建任务', desc: '把「帮我建任务」引导成完整草稿', minRole: 'publisher', tile: '建' },
  { id: 'create-template', name: '创建模板', desc: '把「做个采集模板」引导成字段清单再建', minRole: 'publisher', tile: '板' },
  { id: 'data-reading', name: '数据解读', desc: '查到的数字讲成人话结论，口径说清', minRole: 'member', tile: '读' },
  { id: 'urge-wording', name: '催办话术', desc: '写不伤人的催办提醒', minRole: 'publisher', tile: '催' }
];

export function skillKey(userId: string | number, threadId: string): string {
  return `dcs:assistant:skill:${userId}:${threadId}`;
}

/** 读选中技能 id（键缺失/storage 异常=未选；返回值必属目录内 id，脏值剔除）
 *  六审回修（dsflash P2）：脏值（目录后续版本下架的 id）只返回 null 不清键——
 *  存量键永远留在库里每次读都白校验，跨 tab storage 事件还会反复触发；读到即清收敛 */
export function readSkillId(userId: string | number, threadId: string, storage: KVLike): string | null {
  if (!threadId) {
    return null;
  }
  try {
    const key = skillKey(userId, threadId);
    const v = storage.getItem(key);
    if (!v) {
      return null;
    }
    if (SKILL_CATALOG.some((s) => s.id === v)) {
      return v;
    }
    storage.removeItem(key);
    return null;
  } catch {
    return null;
  }
}

/** 写选中技能（id=null 即删键）；未知 id 不落库（与后端 Holder 清理同口径） */
export function writeSkillId(userId: string | number, threadId: string, id: string | null, storage: KVLike): void {
  if (!threadId) {
    return;
  }
  try {
    const key = skillKey(userId, threadId);
    if (id && SKILL_CATALOG.some((s) => s.id === id)) {
      storage.setItem(key, id);
    } else {
      storage.removeItem(key);
    }
  } catch {
    /* 存储不可用忽略（内存 ref 仍是即时状态源，仅持久化失败） */
  }
}

/** storage 跨标签页变更监听用的键匹配（Composer 已有同款 webOn 守卫，别复制字符串） */
export function isSkillKey(k: string): boolean {
  return k.startsWith('dcs:assistant:skill:');
}
