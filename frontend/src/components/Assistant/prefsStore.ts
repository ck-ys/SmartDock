import { nextTick, reactive, ref, watch } from 'vue';
import type { AssistantUserPref } from '@/api/smartdock/assistant';

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
  return `assistant:lastUse:${userId || 'anon'}`;
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
    // 上次拉取失败过（pulled 已被清掉）：再进页面时补一次，不靠刷新整页
    if (!pulled.has(id)) void pullPrefs(id, hit);
    // 上次推送失败过：同一账号再进页面时把当前值补推上去（换账号不串）
    flushPendingPush(id, hit);
    return hit;
  }
  const prefs = reactive<AgentPrefs>({ ...DEFAULTS, ...read(id) });
  // detached scope：watch 不能挂在「首个调用它的组件」上——那个组件卸载后 watch 会停，
  // cache 里的这份 reactive 就成了哑对象（改了不落盘、不上云）。模块级 scope 与组件同生共死。
  syncScope.run(() => {
    watch(
      prefs,
      (v) => {
        try {
          localStorage.setItem(`${PREFIX}:${id}`, JSON.stringify(v));
        } catch {
          /* 存储不可用忽略；本次会话内仍生效 */
        }
        if (applyingRemote.has(id)) {
          return; // 服务端赋回的值：不回推、不算本机改动
        }
        localEditSeq.set(id, (localEditSeq.get(id) ?? 0) + 1);
        // 批C Q8：改动防抖推账号层（断网时静默——localStorage 已存，下次改动或再次进页面时再推）
        if (id !== 'anon') {
          pushPrefs(v as AgentPrefs, id);
        }
      },
      { deep: true }
    );
  });
  cache.set(id, prefs);
  // 首次取到这个用户时拉一次服务端；拉不到就继续用本机（不打断使用）
  void pullPrefs(id, prefs);
  flushPendingPush(id, prefs);
  return prefs;
}

/* ===== 批C Q8（2026-11-15，2026-11-13 待办板已拍）：七行上账号 =====
   本机 localStorage 保留为离线兜底；登录用户额外与服务端同步：
   - 第一次取到某用户 → 异步拉一次；服务端没存过 → 把本机存量推上去（首次上云不丢设置）
   - 之后每次改动 800ms 防抖推服务端（全量覆盖，不合并——两台机器各改一半时以最后一次保存为准；
     这是板上写明的取舍：仅靠整份 PUT，不做逐字段合并/冲突检测）
   - 从服务端赋回来的值不再反向推一次（applyingRemote 按 userId 记），避免自激回环
   匿名用户（未登录）只走 localStorage，不推服务端。 */
const SYNC_DELAY_MS = 800;
/** 模块级 detached scope：watch 不随调用组件卸载（否则 cache 里的 reactive 会变哑） */
const syncScope = effectScope(true);
const pulled = new Set<string>();
/** 正在应用服务端值的 userId（按人分桶：两个人并发拉取时互不干扰） */
const applyingRemote = new Set<string>();
/** 每人一个防抖计时器：一个人改动不会把另一个人的待推送挤掉 */
const syncTimers = new Map<string, ReturnType<typeof setTimeout>>();
/** 本机改动计数（按人）：拉取在途时用户改了东西，回来的旧值不许覆盖 */
const localEditSeq = new Map<string, number>();
/** 推送失败后待补推的那一份（带 userId，换账号不会串到别人名下） */
let pendingPush: { userId: string; payload: Omit<AssistantUserPref, 'saved'> } | null = null;

function toPayload(p: AgentPrefs): Omit<AssistantUserPref, 'saved'> {
  return {
    sendKey: p.sendKey,
    streamOutput: p.streamOutput,
    toolsOpen: p.toolsOpen,
    autoRetry: p.autoRetry,
    motion: p.motion,
    answerSize: p.answerSize,
    askAutoContinue: p.askAutoContinue
  };
}

function pushPrefs(p: AgentPrefs, userId: string) {
  const prev = syncTimers.get(userId);
  if (prev) {
    clearTimeout(prev);
  }
  syncTimers.set(
    userId,
    setTimeout(() => {
      syncTimers.delete(userId);
      const fail = () => {
        // 断网 / 接口暂不可用（含动态 chunk 加载失败）：本机已存；记下来，下次进页面补推
        pendingPush = { userId, payload: toPayload(p) };
      };
      // 动态 import：本模块在 node 单测里也要能加载（静态 import 会把 axios/request 链一起拉进来）
      void import('@/api/smartdock/assistant')
        .then(({ saveAssistantUserPref }) =>
          saveAssistantUserPref(toPayload(p)).then(() => {
            // 这次成功了：同一个人的待补推作废（别人名下的不动）
            if (pendingPush?.userId === userId) {
              pendingPush = null;
            }
          })
        )
        .catch(fail); // import 自己和请求失败都走这里——外层不兜会变成 unhandledRejection
    }, SYNC_DELAY_MS)
  );
}

/** 同一账号再次进页面时，把上次没推成功的补上（payload 用当前值，比失败时那份更新） */
function flushPendingPush(id: string, prefs: AgentPrefs) {
  if (id === 'anon' || !pendingPush || pendingPush.userId !== id) {
    return;
  }
  pendingPush = null;
  pushPrefs(prefs, id);
}

async function pullPrefs(id: string, prefs: AgentPrefs) {
  if (id === 'anon' || pulled.has(id)) {
    return;
  }
  pulled.add(id);
  const seq0 = localEditSeq.get(id) ?? 0;
  try {
    const { getAssistantUserPref } = await import('@/api/smartdock/assistant');
    // 与 Composer 取构成数据同一套写法：拦截器可能已剥一层，两种形态都接住
    const raw = (await getAssistantUserPref()) as unknown as { data?: AssistantUserPref } | AssistantUserPref;
    const remote = (((raw as { data?: AssistantUserPref })?.data ?? raw) as AssistantUserPref);
    if (!remote.saved) {
      // 服务端还没有这人的行：把本机存量推上去（换设备前在这台机器设过的，第一次登录不丢）
      pushPrefs(prefs, id);
      return;
    }
    if ((localEditSeq.get(id) ?? 0) !== seq0) {
      // 拉取在途时用户自己改过：本地优先，这次回来的旧值直接作废（本地改动已触发推送）
      return;
    }
    applyingRemote.add(id);
    try {
      prefs.sendKey = remote.sendKey;
      prefs.streamOutput = remote.streamOutput;
      prefs.toolsOpen = remote.toolsOpen;
      prefs.autoRetry = remote.autoRetry;
      prefs.motion = remote.motion;
      prefs.answerSize = remote.answerSize;
      prefs.askAutoContinue = remote.askAutoContinue;
    } finally {
      // watch 默认 flush='pre'，回调在微任务里跑：同步块内撤闸等于没设过——
      // 必须等这一拍 watcher 真跑完再撤闸，否则每次拉取都会把刚拉到的值原样推回去。
      await nextTick();
      applyingRemote.delete(id);
    }
  } catch {
    /* 拉不到就继续用本机；下次进页面会重试（pulled 已清掉，getAgentPrefs 命中缓存时补拉） */
    pulled.delete(id);
  }
}
