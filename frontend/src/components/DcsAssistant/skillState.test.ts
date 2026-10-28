/**
 * 能力① Q4（2026-10-20）：技能目录动态化纯函数单测；Q7 触发词匹配已随 5b 批题7 甲
 * （2026-10-28 拍板）拆除——助手什么时候用技能由后端一句话说明喂模型判断，前端不再匹配。
 * 模块级 skillCatalog ref 状态会影响 readSkillId/writeSkillId——各用例用完复位兜底目录。
 */
import { afterEach, describe, expect, it } from 'vitest';
import {
  SKILL_CATALOG,
  skillCatalog,
  applyRemoteCatalog,
  readSkillId,
  writeSkillId,
  type KVLike
} from './skillState';

/** Map 伪装 KVLike（getItem/setItem/removeItem 三件套） */
function kv(): KVLike & { store: Map<string, string> } {
  const store = new Map<string, string>();
  return {
    store,
    getItem: (k) => store.get(k) ?? null,
    setItem: (k, v) => store.set(k, v),
    removeItem: (k) => store.delete(k)
  };
}

afterEach(() => {
  skillCatalog.value = SKILL_CATALOG;
});

describe('技能目录动态化（applyRemoteCatalog）', () => {
  it('远端行替换目录：tile 按兜底补齐', () => {
    applyRemoteCatalog([
      { id: 'data-reading', name: '数据解读', desc: '新描述', minRole: 'member' },
      { id: 'brand-new', name: '全新技能', desc: '', minRole: 'publisher' }
    ]);
    expect(skillCatalog.value).toHaveLength(2);
    expect(skillCatalog.value[0]).toMatchObject({ id: 'data-reading', desc: '新描述', tile: '读' });
    expect(skillCatalog.value[1]).toMatchObject({ id: 'brand-new', tile: '全' });

  });

  it('空/畸形输入保持兜底目录不动', () => {
    applyRemoteCatalog(null);
    applyRemoteCatalog([]);
    expect(skillCatalog.value).toEqual(SKILL_CATALOG);

  });

  it('远端新技能 id 能落库选中（动态目录生效后 write/read 闭环）', () => {
    applyRemoteCatalog([{ id: 'brand-new', name: '全新技能', desc: '', minRole: 'member' }]);
    const s = kv();
    writeSkillId(1, 'th-x', 'brand-new', s);
    expect(readSkillId(1, 'th-x', s)).toBe('brand-new');
    // 目录回落后脏值读到即清（dsflash P2 口径不变）
    skillCatalog.value = SKILL_CATALOG;
    expect(readSkillId(1, 'th-x', s)).toBeNull();
    expect(s.store.has('dcs:assistant:skill:1:th-x')).toBe(false);
  });
});

