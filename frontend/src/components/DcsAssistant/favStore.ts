/**
 * 常用问句收藏（原型 favBar：⭐ 收藏的回答问法，输入框上方常驻 chips+✕ 删；上限 6 条显 4 条）。
 *
 * C2 收藏跨设备（2026-10-07）：服务端为真相（dcs_assistant_favorite，用户级），
 * localStorage 存量首启一次性合并上传后弃用。导出签名保持同步语义（has/add/remove
 * 布尔返回）——add/remove 乐观更新本地，API 回传最新清单回写；失败回滚（网络断时
 * 表现为收藏闪回，刷新后以服务端为准）。
 */
import { ref } from 'vue';
import { addAssistantFavorite, listAssistantFavorites, removeAssistantFavorite } from '@/api/dcs/assistant';
import { unwrap } from './util';

const KEY = 'dcs:assistant:favs';
const MIGRATED_KEY = 'dcs:assistant:favs:migrated';
const MAX = 6;

function loadLegacy(): string[] {
  try {
    const raw = localStorage.getItem(KEY);
    const arr = raw ? (JSON.parse(raw) as unknown) : [];
    return Array.isArray(arr) ? (arr as string[]).filter((x) => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

const favs = ref<string[]>([]);

/** 服务端清单回写（API 返回即真相，统一截 MAX 前缀防超限脏数据） */
function applyRemote(list: string[] | null | undefined) {
  if (Array.isArray(list)) {
    favs.value = list.filter((x) => typeof x === 'string').slice(0, MAX);
  }
}

/** 首启：拉服务端清单 + 存量 localStorage 合并上传（一次性，成功后标记迁移完成） */
async function bootstrap() {
  try {
    applyRemote(unwrap<string[]>(await listAssistantFavorites()));
    const migrated = localStorage.getItem(MIGRATED_KEY) === '1';
    const legacy = migrated ? [] : loadLegacy();
    if (legacy.length > 0) {
      // 逐条合并（服务端上限/去重各自校验；失败中断保留原状，下次再试）
      for (const q of legacy) {
        applyRemote(unwrap<string[]>(await addAssistantFavorite(q)));
      }
    }
    if (!migrated) {
      localStorage.setItem(MIGRATED_KEY, '1');
    }
  } catch {
    /* 未登录/断网：chips 空，登录后可用；不回退 localStorage（跨设备语义） */
  }
}

let bootPromise: Promise<void> | null = null;
/** 惰性首拉（首个消费组件挂载时触发一次） */
function ensureBoot() {
  if (!bootPromise) {
    bootPromise = bootstrap();
  }
}

export function useFavs() {
  ensureBoot();
  return {
    favs,
    has: (q: string) => favs.value.includes(q),
    add: (q: string) => {
      const t = q.trim();
      if (!t || favs.value.includes(t) || favs.value.length >= MAX) {
        return false;
      }
      const before = [...favs.value];
      favs.value = [t, ...favs.value];
      addAssistantFavorite(t)
        .then((r) => applyRemote(unwrap<string[]>(r)))
        .catch(() => {
          favs.value = before;
        });
      return true;
    },
    remove: (q: string) => {
      const before = [...favs.value];
      favs.value = favs.value.filter((x) => x !== q);
      removeAssistantFavorite(q)
        .then((r) => applyRemote(unwrap<string[]>(r)))
        .catch(() => {
          favs.value = before;
        });
    }
  };
}
