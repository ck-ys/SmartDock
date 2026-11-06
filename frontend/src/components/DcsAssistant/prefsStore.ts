import { reactive, ref, watch } from 'vue';

/** 常规 · 聊天行为（C 七行）：按用户存 localStorage，改完立即生效。 */
export type SendKey = 'enter' | 'ctrl-enter';
/** 动效强度：跟随系统 / 始终标准 / 始终减弱 */
export type MotionLevel = 'system' | 'full' | 'reduced';
export type AnswerSize = 'standard' | 'large';

export interface AgentPrefs {
  /** Enter 发送 / Ctrl+Enter 发送（Shift+Enter 始终换行） */
  sendKey: SendKey;
  /** 边生成边显示；关=等这一轮结束一次性出 */
  streamOutput: boolean;
  /** 工具调用/过程明细默认展开 */
  toolsOpen: boolean;
  /** 失败时自动重试（最多 1 次） */
  autoRetry: boolean;
  /** 动效强度：跟随系统 / 减弱 */
  motion: MotionLevel;
  /** 回答字号：标准 / 大 */
  answerSize: AnswerSize;
  /** 提问自动继续：5 分钟没答就继续（关=一直等） */
  askAutoContinue: boolean;
}

const PREFIX = 'smartdock-agent-prefs:v1';

/** 「上次使用时间」键：按用户分开——全局键会让甲看到乙的使用时间（E-10）。 */
export function lastUseKey(userId: string | number): string {
  return `dcs:assistant:lastUse:${userId || 'anon'}`;
}

const DEFAULTS: AgentPrefs = {
  sendKey: 'enter',
  streamOutput: true,
  toolsOpen: false,
  autoRetry: true,
  motion: 'system',
  answerSize: 'standard',
  askAutoContinue: true
};

const cache = new Map<string, AgentPrefs>();

/* 跟随系统：只在「跟随系统」档生效；系统偏好变了要当场翻转，不做成读取时一次性算死。 */
const mql =
  typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-reduced-motion: reduce)')
    : null;
export const systemReducedMotion = ref(!!mql?.matches);
mql?.addEventListener?.('change', (e) => {
  systemReducedMotion.value = e.matches;
});

/** 当前是否该走「减弱」：减弱档恒真；跟随系统看 OS；标准档恒假。 */
export function motionReduced(prefs: AgentPrefs): boolean {
  return prefs.motion === 'reduced' || (prefs.motion === 'system' && systemReducedMotion.value);
}

function read(userId: string): Partial<AgentPrefs> {
  try {
    const raw = localStorage.getItem(`${PREFIX}:${userId}`);
    if (!raw) {
      return {};
    }
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

/** 同一 userId 共用一份 reactive；写入自动落盘。 */
export function getAgentPrefs(userId: string | number): AgentPrefs {
  const id = String(userId || 'anon');
  const hit = cache.get(id);
  if (hit) {
    return hit;
  }
  const prefs = reactive<AgentPrefs>({ ...DEFAULTS, ...read(id) });
  watch(
    prefs,
    (v) => {
      try {
        localStorage.setItem(`${PREFIX}:${id}`, JSON.stringify(v));
      } catch {
        /* 存储不可用忽略；本次会话内仍生效 */
      }
    },
    { deep: true }
  );
  cache.set(id, prefs);
  return prefs;
}
