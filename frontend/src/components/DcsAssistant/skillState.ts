/**
 * 技能会话级选择存取（五需求批 R1，2026-10-13 拍板 Q1-A/Q2-A）。
 *
 * 单一真源=localStorage `dcs:assistant:skill:{userId}:{threadId}`（存技能 id，无键=未选），
 * ➕面板技能组/输入区胶囊与 useAssistant.runTurn 出口注入（thinkTier/modelPin/webOn 同款
 * 「内部路径不丢参」模式）三处共用。选中技能的正文由后端 SkillInjectModelDecorator
 * 当轮注入（不进会话记忆），前端只负责选哪个——正文永不下发前端，token 省在服务端。
 *
 * 前端目录为编译期兜底：远端 /dcs/assistant/skills（B 批起读官方技能仓，且同过租户
 * 开关矩阵）正常时以远端为准；此处静态四技能仅在网络空/异常时兜底。
 * 5b 批题7 甲（2026-10-28 拍板）：触发词自动挂载拆除——助手什么时候用哪个技能由
 * 后端「一句话说明」喂模型自主判断（view_skill 自取路），前端不再做关键词匹配。
 * minRole 口径与 roleOf 对齐：member=成员可见，publisher=发布人/管理员，admin=仅管理员。
 * create-template 已随 R4a act_create_template 同批上（后端同款纪律）。
 */

/** storage 抽象：window.localStorage 或测试桩 */
export interface KVLike {
  getItem(k: string): string | null;
  setItem(k: string, v: string): void;
  removeItem(k: string): void;
}

/** 前端技能目录行（展示用五元组；正文在后端） */
export interface SkillCatalogItem {
  id: string;
  name: string;
  desc: string;
  /** 最低角色：'member'=全员，'publisher'=发布人/管理员，'admin'=仅管理员（三审回修：admin 档如实透出） */
  minRole: 'member' | 'publisher' | 'admin';
  tile: string;
}

export const SKILL_CATALOG: SkillCatalogItem[] = [
  { id: 'create-mission', name: '创建任务', desc: '把「帮我建任务/建个任务」引导成完整草稿', minRole: 'publisher', tile: '建' },
  { id: 'create-template', name: '创建模板', desc: '把「做个模板/建个表单」引导成字段齐全再建', minRole: 'publisher', tile: '板' },
  { id: 'data-reading', name: '数据解读', desc: '查到的数字讲成人话结论，口径说清', minRole: 'member', tile: '读' },
  { id: 'urge-wording', name: '催办话术', desc: '写不伤人的催办提醒', minRole: 'publisher', tile: '催' }
];

/* ===== 能力① Q4 轻量（2026-10-20）：目录动态化——编译期兜底之上按后端 /skills 刷新 =====
 * 模块级 ref（nameState 同模式）；tile 纯展示字，远端行缺省时按兜底目录或名称首字补。 */
import { ref } from 'vue';

export const skillCatalog = ref<SkillCatalogItem[]>(SKILL_CATALOG);

/** 远端目录行（/dcs/assistant/skills 出参形状） */
export interface RemoteSkillRow {
  id: string;
  name: string;
  desc: string;
  minRole: 'member' | 'publisher' | 'admin';
}

/** 喂远端目录（后端加/改技能无需前端发版；空/异常静默保持兜底目录） */
export function applyRemoteCatalog(rows: RemoteSkillRow[] | null | undefined): void {
  if (!Array.isArray(rows) || rows.length === 0) {
    return;
  }
  const fallback = new Map(SKILL_CATALOG.map((s) => [s.id, s]));
  skillCatalog.value = rows
    .filter((r) => r && typeof r.id === 'string' && r.id)
    .map((r) => ({
      id: r.id,
      name: r.name || fallback.get(r.id)?.name || r.id,
      desc: r.desc || fallback.get(r.id)?.desc || '',
      minRole: r.minRole === 'member' ? 'member' : r.minRole === 'admin' ? 'admin' : 'publisher',
      tile: fallback.get(r.id)?.tile || (r.name || r.id).slice(0, 1)
    }));
}

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
    if (skillCatalog.value.some((s) => s.id === v)) {
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
    if (id && skillCatalog.value.some((s) => s.id === id)) {
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
