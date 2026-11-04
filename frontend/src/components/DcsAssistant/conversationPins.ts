import { ref } from 'vue';

/**
 * 会话置顶（localStorage 一期，跨设备同步登记二站）。
 *
 * 2026-11-04 批C 三席交叉实证：会话页与抽屉各自 `ref` 初始化、各自把整数组写回，
 * 同一标签页不触发 storage 事件 → A 处置顶、B 处再置顶会把 A 覆盖掉（静默丢用户数据）。
 * 收成一个模块级单例：两宿主读同一个 ref，写盘也只剩一处。
 */
const PIN_KEY = 'dcs:assistant:pins';

export function loadPins(): string[] {
  try {
    const raw = JSON.parse(localStorage.getItem(PIN_KEY) ?? '[]');
    // 只认字符串数组：旧版本/手工改过的对象形态会让 includes 直接抛，一脏值炸整列
    return Array.isArray(raw) ? raw.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

export const convPins = ref<string[]>(loadPins());

export function toggleConvPin(id: string) {
  const key = String(id);
  convPins.value = convPins.value.includes(key) ? convPins.value.filter((x) => x !== key) : [key, ...convPins.value];
  try {
    localStorage.setItem(PIN_KEY, JSON.stringify(convPins.value));
  } catch {
    // 隐私模式/配额满：内存态仍生效，不阻断置顶动作
  }
}

// 另一标签页改了置顶 → 本页同步（同页双宿主由上面的单例 ref 保证）
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === PIN_KEY) {
      convPins.value = loadPins();
    }
  });
}
