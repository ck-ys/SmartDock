<template>
  <!-- 多根组件：宿主 :class 无法透传到本节点（Vue 对 fragment 不做 attribute 继承），
       状态类一律走 prop 在此内部绑定；面板让位已上移宿主父层（Q8/Q9 统一廊位机制） -->
  <div ref="flowRef" class="dcs-flow" :class="{ full }">
    <!-- 五需求批 R4b 演示模式进度条（sticky 顶）：进行中/暂停（HITL 等确认）/完成三态 + 停止钮；
         批17 Q7：速度档三键（步间等待时长）+ 完成态小结条（重播/收起，不再 3.2s 自灭） -->
    <div v-if="demoTotal" class="demo-bar" role="status" aria-live="polite">
      <IconSpan name="clapperboard" />
      <template v-if="demoDone">
        <b>演示完成</b>
        <span class="db-title">{{ demoTitle || '' }} · 共 {{ demoTotal }} 步{{ demoWaits ? ` · 等你把了 ${demoWaits} 次关` : '' }}</span>
      </template>
      <template v-else-if="demoPaused">
        <b>演示暂停</b><span class="db-title">{{ demoTitle || '剧本' }} · 完成上面的确认后自动继续 · 第 {{ demoStep }}/{{ demoTotal }} 步</span>
      </template>
      <template v-else>
        <b>演示进行中</b><span class="db-title">{{ demoTitle || '剧本' }} · 第 {{ demoStep }}/{{ demoTotal }} 步</span>
      </template>
      <span class="dbar"><i :style="{ width: Math.min(100, (demoStep / demoTotal) * 100) + '%' }" /></span>
      <span v-if="!demoDone" class="dspeed" role="group" aria-label="演示速度">
        <button
          v-for="s in DEMO_SPEEDS"
          :key="s.key"
          :class="{ on: demoSpeed === s.key }"
          :title="`演示速度：${s.label}（每步间隔约 ${s.gap / 1000} 秒，从下一步起生效）`"
          @click="setDemoSpeed(s.key)"
        >
          {{ s.label }}
        </button>
      </span>
      <button v-if="demoDone" @click="replayDemo">重播</button>
      <button v-if="demoDone" @click="stopDemo()">收起</button>
      <button v-if="!demoDone" @click="stopDemo(true)">停止演示</button>
    </div>
    <hero-welcome
      v-if="state.turns.length === 0 && state.queue.length === 0"
      :newbie-use="state.newbieUse"
      :snap="snap"
      @pick="onPick"
      @fill="(t) => emit('fill', t)"
      @demo-start="startDemo"
      @tour="emit('tour')"
      @open-help="emit('open-help')"
    />
    <template v-for="(turn, ti) in state.turns" :key="turn.id">
      <!-- 批17 Q2：压缩分隔条——本回合之前发生过上下文压缩（前文已打包成摘要） -->
      <div v-if="turn.compactedBefore" class="compact-mark" role="separator">
        <IconSpan name="inbox" :size="13" />
        已压缩 · 前面的对话打包成摘要{{ compactSavedText(turn.compactedBefore) }} · 关键结论与任务都在摘要里
      </div>
      <!-- 用户气泡（Q4A 纯文本；Q5A >270px 折叠 260px；Q3A hover 时间戳；BN-16 对齐原型=纯气泡无 hover 操作键；
           批J-2 补原型 .avatar：行右端 30px 首字圆标（row-reverse 下 DOM 首位=视觉最右） -->
      <div class="mrow-me">
        <span class="avatar" aria-hidden="true">{{ who }}</span>
        <div class="me-wrap">
          <div class="bub" :data-tid="turn.id" :class="{ clamp: clampAble.has(turn.id) && !expandSet.has(turn.id) }">
            {{ turn.question }}
          </div>
          <button v-if="clampAble.has(turn.id)" class="bexp" :aria-expanded="expandSet.has(turn.id)" @click="toggleExpand(turn.id)">
            {{ expandSet.has(turn.id) ? '收起' : '展开全文' }}
          </button>
          <span class="ts">{{ fmtTime(turn.startedAt) }}</span>
        </div>
      </div>
      <!-- H10：秒针只发给在途回合（sending 时末轮；单飞行回合设计）——历史回合传 0，
           否则每秒一次的 now 更新会带动全部回合的重算链路（长会话百回合纯浪费） -->
      <turn-block
        :turn="turn"
        :turn-index="ti"
        :now="ti === state.turns.length - 1 && state.sending ? now : 0"
        :thread-id="state.threadId"
        :show-think="state.showThink"
        @resume="emit('resume')"
        @stop="emit('stop')"
        @resolve-hitl="(a, r, e) => emit('resolve-hitl', a, r, e)"
        @new-session="emit('new-session')"
        @recover-interrupt="emit('recover-interrupt')"
        @resend="(t) => emit('resend', t)"
        @resend-run="(t) => emit('resend-run', t)"
        @branch="(t) => emit('branch', t)"
        @ask="(t) => emit('ask', t)"
        @fill="(t) => emit('fill', t)"
        @pgoto="(p) => emit('pgoto', p)"
        @mdnav="(u) => emit('mdnav', u)"
        @plan-cancel="emit('plan-cancel')"
      />
    </template>
    <!-- Q2A：排队消息上屏灰气泡（半透明+排队中；发送时转正=shift 出队）。
         业务批4 Q8：排队长消息同款折叠（260px+渐隐）——排队条撑爆流区实锤；
         dsflash P1 回修：折叠必须配展开钮，否则长排队消息永久看不全 -->
    <div v-for="q in state.queue" :key="'p' + q.pendingId" class="mrow-me pending">
      <div class="bub" :class="{ clamp: q.text.length > 190 && !expandSet.has('p' + q.pendingId) }">{{ q.text }}</div>
      <button v-if="q.text.length > 190" class="bexp" :aria-expanded="expandSet.has('p' + q.pendingId)" @click="toggleExpand('p' + q.pendingId)">
        {{ expandSet.has('p' + q.pendingId) ? '收起' : '展开全文' }}
      </button>
      <span class="p-tag">排队中</span>
    </div>
    <!-- 批17 Q2：尾部悬挂压缩标记——system 行之后暂无新回合（刚压缩完重拉的形态） -->
    <div v-if="state.turns.length > 0 && state.lastCompaction" class="compact-mark" role="separator">
      <IconSpan name="inbox" :size="13" />
      已压缩 · 前面的对话打包成摘要{{ compactSavedText(state.lastCompaction) }} · 关键结论与任务都在摘要里
    </div>
    <div class="flow-pad" />

    <!-- 回到底部钮（原型 tobot：距底>48px 且内容超一屏才出；点击回底自动恢复 followTail） -->
    <button v-show="showBack" class="tobot" aria-label="回到底部" title="回到底部（有新内容会自动跟随）" @click="backToBottom">
      <IconSpan name="arrow-down" />
    </button>
  </div>

  <!-- 选区悬浮操作条（原型 selbar：深色胶囊；选中回答文字→＋加入对话/🔍 追问。
       Q3（20261011 拍板A）：定位改选区上方（不盖选中行），贴 flow 顶自动回落下方、左右钳制在流内；
       滚动时按活选区重定位跟随（选区滚出可视区=隐藏），不再滞留视口原位脱钩） -->
  <div v-if="selBar" ref="selbarRef" class="selbar" :style="selbarStyle" role="toolbar" aria-label="选中文字操作">
    <button title="把选中文字追加到输入框" @click="onSelAppend"><IconSpan name="plus" :size="13" /> 加入对话</button>
    <button title="把选中文字填入输入框待编辑追问" @click="onSelAsk"><IconSpan name="search" :size="13" /> 单独追问</button>
  </div>
</template>

<script setup lang="ts">
/**
 * 消息流（批E Task E2）：欢迎卡 / 用户气泡 / AI 回合块 / 排队灰气泡。
 * 滚动跟随：距底&lt;48px 才跟随（followTail 口径，父组件供给 follow 开关）。
 */
import type { AssistantState, AssistantTurn, HitlEdits } from './useAssistant';
import HeroWelcome, { type SnapItem } from './HeroWelcome.vue';
import TurnBlock from './TurnBlock.vue';
import IconSpan from './IconSpan.vue';
import { useUserStore } from '@/store/modules/user';
import { findVisibleHitlDock } from './util';

const props = defineProps<{
  state: AssistantState;
  cur: () => AssistantTurn | null;
  follow: boolean;
  full?: boolean;
  snap?: SnapItem[];
}>();

/** 原型 .avatar：用户气泡右端首字圆标（昵称>账号兜底） */
const who = computed(() => {
  const u = useUserStore();
  return (u.nickname || u.name || '我').trim().charAt(0) || '我';
});
const emit = defineEmits<{
  pick: [prompt: string];
  resume: [];
  /** Q12-② 停止思考（TurnBlock 思考胶囊旁中断入口，转发宿主 onStop） */
  stop: [];
  resend: [text: string];
  /** BN-7：重试直发（TurnBlock 转发） */
  'resend-run': [text: string];
  'resolve-hitl': [approved: boolean, remember?: boolean, edits?: HitlEdits];
  'new-session': [];
  /** AU-35：错误卡恢复挂起确认（TurnBlock 转发） */
  'recover-interrupt': [];
  branch: [turn: AssistantTurn];
  ask: [text: string];
  pgoto: [payload: { missionId: string; missionName: string }];
  /** 批3 Q6 站内链导航（TurnBlock 转发；抽屉宿主收抽屉再跳） */
  mdnav: [url: string];
  'plan-cancel': [];
  /** 选区条：填入输入框（append=true 追加；false 覆盖聚焦） */
  'draft-set': [text: string, append: boolean];
  /** BN-6：新手「帮我填一句」=回填输入框（不直接发，原型 stepsTry） */
  fill: [text: string];
  'scroll-lock': [el: HTMLElement | null];
  'open-help': [];
  /** 批17 Q7：界面导览（HeroWelcome 新手行 → 宿主 driver.js 聚光灯带看） */
  tour: [];
}>();

const flowRef = ref<HTMLElement>();
const expandSet = ref(new Set<string>());
const now = ref(Date.now());

/** 回底钮显隐（48px 容差+内容超一屏；与 Drawer followTail 同口径但独立本地态） */
const showBack = ref(false);
/** Q3（20261011 拍板A）：滚动时若选区悬浮条在场，rAF 节流重定位跟随活选区——
 *  选区滚出可视区由 onSelChange 的视口守卫自动隐藏，不再滞留原位脱钩 */
let selReposRaf = 0;
/** 条在场时 rAF 节流重定位（滚动/窗口缩放共用入口；选区滚出可视区由 onSelChange 视口守卫隐藏） */
function scheduleSelRepos() {
  if (selBar.value && !selReposRaf) {
    selReposRaf = requestAnimationFrame(() => {
      selReposRaf = 0;
      onSelChange();
    });
  }
}
function onFlowScroll() {
  const el = flowRef.value;
  if (!el) {
    return;
  }
  // 视觉席 U4-P0 回修：阈值 48→92——sticky 钮占底部 14~48px 带，旧阈值下末两行正被
  // 钮盖住时仍显示（"（"被压的实拍）；92 起 = 距底不足两行就隐钮，末行永不被盖
  showBack.value = el.scrollHeight - el.scrollTop - el.clientHeight > 92 && el.scrollHeight > el.clientHeight + 100;
  scheduleSelRepos();
}
function backToBottom() {
  const el = flowRef.value;
  if (el) {
    el.scrollTop = el.scrollHeight;
  }
  showBack.value = false;
}

/** 选区悬浮条：选区在流内且在流可视区（视口外守卫）才出。
 *  Q3（20261011 拍板A）：状态存锚点几何（选区上下沿/水平中心/流边界），最终位置由
 *  selbarStyle 按实测条尺寸解算——上方优先、贴顶回落下方、左右钳制；滚动重定位复用本函数 */
interface SelBarAnchor {
  anchorX: number;
  anchorTop: number;
  anchorBottom: number;
  flowLeft: number;
  flowRight: number;
  flowTop: number;
  text: string;
}
const selBar = ref<SelBarAnchor | null>(null);
const selbarRef = ref<HTMLElement>();
/** 条实测尺寸：量到前不显示（避免估值→实测首帧跳变），量到后跨选区复用，值不变不换引用防抖动 */
const selbarSize = ref<{ w: number; h: number } | null>(null);
watch(
  selBar,
  async (v) => {
    if (!v) {
      return;
    }
    await nextTick();
    const r = selbarRef.value?.getBoundingClientRect();
    if (r && r.width > 0 && (r.width !== selbarSize.value?.w || r.height !== selbarSize.value?.h)) {
      selbarSize.value = { w: r.width, h: r.height };
    }
  },
  { flush: 'post' }
);
const selbarStyle = computed(() => {
  const s = selBar.value;
  if (!s || !selbarSize.value) {
    return { visibility: 'hidden' };
  }
  const { w, h } = selbarSize.value;
  let top = s.anchorTop - h - 8;
  if (top < s.flowTop + 4) {
    top = s.anchorBottom + 8;
  }
  const minX = s.flowLeft + 8;
  const left = Math.min(Math.max(s.anchorX - w / 2, minX), Math.max(minX, s.flowRight - w - 8));
  return { left: left + 'px', top: top + 'px' };
});
function onSelChange() {
  const sel = document.getSelection();
  const flow = flowRef.value;
  if (!sel || sel.isCollapsed || !sel.toString().trim() || !flow) {
    selBar.value = null;
    return;
  }
  const anchor = sel.anchorNode;
  if (!anchor || !flow.contains(anchor.nodeType === 1 ? (anchor as HTMLElement) : anchor.parentElement)) {
    selBar.value = null;
    return;
  }
  const rect = sel.getRangeAt(0).getBoundingClientRect();
  const fr = flow.getBoundingClientRect();
  if (rect.bottom < fr.top || rect.top > fr.bottom || rect.width === 0) {
    selBar.value = null;
    return;
  }
  selBar.value = {
    anchorX: rect.left + rect.width / 2,
    anchorTop: rect.top,
    anchorBottom: rect.bottom,
    flowLeft: fr.left,
    flowRight: fr.right,
    flowTop: fr.top,
    text: sel.toString().trim().slice(0, 500)
  };
}
function clearSel() {
  document.getSelection()?.removeAllRanges();
  selBar.value = null;
}
function onSelAppend() {
  if (selBar.value) {
    // BN-13 原型 selAdd：追加带「引用：」前缀
    emit('draft-set', '引用：「' + selBar.value.text.slice(0, 400) + '」', true);
  }
  clearSel();
}
function onSelAsk() {
  if (selBar.value) {
    // BN-13 原型 selAsk：覆盖为「关于「…」再问：」句式（上限 400 字）
    emit('draft-set', '关于「' + selBar.value.text.slice(0, 400) + '」再问：', false);
  }
  clearSel();
}

function fmtTime(ts: number): string {
  const d = new Date(ts);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `今天 ${hh}:${mm}`;
}

/** 批17 Q2：压缩分隔条的省量人话（前后 token 齐才有；缺数静默不显示） */
function compactSavedText(m: { beforeTokens?: number | null; afterTokens?: number | null }): string {
  if (typeof m.beforeTokens === 'number' && typeof m.afterTokens === 'number' && m.beforeTokens > m.afterTokens && m.beforeTokens > 0) {
    const pct = Math.round(((m.beforeTokens - m.afterTokens) / m.beforeTokens) * 100);
    if (pct >= 5) {
      return `（省下约 ${pct}% 空间）`;
    }
  }
  return '';
}
/**
 * 业务批4 Q8（ZCode 口径）：折叠判定从字符数改**高度测量**——190 字符在多换行/短句
 * 场景两头失准（短字符多换行超 260px 不折、长中文单行块 190 字才 5 行折了个寂寞）。
 * turns 变化后 nextTick 实测每条用户气泡 scrollHeight（>270px=需折），存 clampAble。
 */
const clampAble = ref(new Set<string>());
function measureClamp() {
  const root = flowRef.value;
  if (!root) {
    return;
  }
  const next = new Set<string>();
  root.querySelectorAll<HTMLElement>('.mrow-me .bub[data-tid]').forEach((el) => {
    if (el.scrollHeight > 270) {
      next.add(el.dataset.tid || '');
    }
  });
  clampAble.value = next;
}
/** m3 P1-6：resize 时选区重定位+折叠复测共用一个入口（都只读 DOM 挂类/标集合，幂等） */
function onWinResize() {
  scheduleSelRepos();
  nextTick(measureClamp);
}
watch(
  () => props.state.turns.length,
  () => {
    nextTick(measureClamp);
  },
  { immediate: true }
);
function toggleExpand(id: string) {
  const s = new Set(expandSet.value);
  if (s.has(id)) {
    s.delete(id);
  } else {
    s.add(id);
  }
  expandSet.value = s;
}
function onPick(prompt: string) {
  emit('pick', prompt);
}

/** 五需求批 R4b 演示模式续跑器：欢迎卡剧本装填队列，sending 收口后自动发下一步。
 *  发送通道复用 pick（与建议卡同路）；HITL 卡在场（末回合 phase=waiting）暂停等你
 *  确认——确认触发 resume 后 sending 再走一轮 true→false，本 watch 自然接续；
 *  停止/出错即中止整场演示（与用户手动停同效，不吞队列外消息）。 */
const demoQueue = ref<string[]>([]);
const demoTotal = ref(0);
const demoStep = ref(0); // 已发出步数（1 起）
const demoDone = ref(0); // 完成态时间戳：>0 显示「演示完成」，3.2s 后收条
let demoTimer = 0;
/** 三审回修（m3 P1）：最近一步演示剧本文本——sending 收口时比对末回合问题，
 *  用户中途自己插话（末回合≠剧本步）则演示让位终止，不再把剧本下一步塞进用户的对话 */
let demoLastSent = '';
/** 全量剧本快照（六审回修 m3/glm53）：停止演示时用来从宿主排队队列摘除剧本步——
 *  emit('pick') 在 sending 窗口被 send() 排进 state.queue，不清会变灰气泡逐条自动发出 */
const demoScript = ref<string[]>([]);
/** 业务批8 Q5：当前幕名（顶条预告「第 N 幕名 · 第 N/M 步」） */
const demoTitle = ref('');
/** 批17 Q7：暂停计数（HITL 等确认次数，完成小结条「等你把了 N 次关」） */
const demoWaits = ref(0);
/** 批17 Q7：速度档（步间等待 ms；持久化全局键，坏值回正常档） */
const DEMO_SPEEDS = [
  { key: 'slow', label: '慢速', gap: 2400 },
  { key: 'normal', label: '正常', gap: 1200 },
  { key: 'fast', label: '快速', gap: 500 }
] as const;
const DEMO_SPEED_KEY = 'dcs:assistant:demoSpeed';
const demoSpeed = ref<(typeof DEMO_SPEEDS)[number]['key']>(
  (() => {
    try {
      const saved = localStorage.getItem(DEMO_SPEED_KEY);
      return DEMO_SPEEDS.some((s) => s.key === saved) ? (saved as (typeof DEMO_SPEEDS)[number]['key']) : 'normal';
    } catch {
      return 'normal';
    }
  })()
);
function setDemoSpeed(key: (typeof DEMO_SPEEDS)[number]['key']) {
  demoSpeed.value = key;
  try {
    localStorage.setItem(DEMO_SPEED_KEY, key);
  } catch {
    /* 存储不可用忽略 */
  }
}
const demoGap = computed(() => DEMO_SPEEDS.find((s) => s.key === demoSpeed.value)?.gap ?? 1200);
const demoPaused = computed(() => {
  const last = props.state.turns.at(-1);
  // 六审回修补丁：末步等确认也算暂停（原 demoQueue>0 条件下末步亮"进行中"，卡在等却
  // 显示进行中——确认后本就自动收口，文案"完成上面的确认后自动继续"对末步同样成立）
  return demoTotal.value > 0 && last?.phase === 'waiting';
});
/** 剥掉 HITL 确认/取消补跑轮在问题前加的标记前缀（useAssistant resumeTurn），
 *  演示对账按用户可见原文比对——严格等号会把「（确认执行）xxx」当用户插话误杀演示 */
function stripDemoPrefix(q: string): string {
  return q.replace(/^（(确认执行|已取消)）/, '');
}
function startDemo(steps: string[], title?: string) {
  if (!steps.length) {
    return;
  }
  window.clearTimeout(demoTimer);
  demoDone.value = 0;
  demoTotal.value = steps.length;
  demoStep.value = 1;
  demoTitle.value = title || '';
  demoWaits.value = 0;
  demoQueue.value = steps.slice(1);
  demoScript.value = steps.slice();
  demoLastSent = steps[0];
  emit('pick', steps[0]);
}
/** 批17 Q7：重播=同剧本同幕名原样再跑（完成小结条的「重播」） */
function replayDemo() {
  if (demoScript.value.length) {
    startDemo(demoScript.value.slice(), demoTitle.value);
  }
}
/** @param abortRun true=用户点「停止演示」——同步中止在途一轮；让位/切会话路径只收剧本不打断用户 */
function stopDemo(abortRun = false) {
  window.clearTimeout(demoTimer);
  if (demoScript.value.length && props.state.queue.length) {
    const script = new Set(demoScript.value);
    // eslint-disable-next-line vue/no-mutating-props -- state 是 useAssistant 的共享 reactive 单例（Composer 改 banner 同款约定），跨组件摘队列是本组件唯一出口
    props.state.queue = props.state.queue.filter((q) => !script.has(q.text));
  }
  demoQueue.value = [];
  demoScript.value = [];
  demoTotal.value = 0;
  demoStep.value = 0;
  demoDone.value = 0;
  demoTitle.value = '';
  demoWaits.value = 0;
  if (abortRun) {
    emit('stop');
  }
}
watch(
  () => props.state.sending,
  (sending, wasSending) => {
    if (sending || !wasSending || !demoTotal.value) {
      return;
    }
    const last = props.state.turns.at(-1);
    if (last && (last.phase === 'stopped' || last.phase === 'error')) {
      stopDemo();
      return;
    }
    // 用户插话接管：末回合不是本场剧本步（HITL 确认不改 question，命中同串）→ 演示让位
    if (!last || stripDemoPrefix(last.question) !== demoLastSent) {
      stopDemo();
      return;
    }
    // HITL 卡在场：暂停等确认，resume 后本 watch 再次触发接续——判定先于"队列空=完成"：
    // 最后一步正等确认时亮"演示完成"是假完成（用户确认后演示已收条，没有接续感）
    if (last?.phase === 'waiting') {
      return;
    }
    if (demoQueue.value.length === 0) {
      // 最后一步收口（批17 Q7）：亮「演示完成」停留等用户收（不再 3.2s 自灭）——
      // 小结条=标题+总步数+把关次数+重播/收起；步数先到满格
      demoStep.value = demoTotal.value;
      demoDone.value = Date.now();
      return;
    }
    demoTimer = window.setTimeout(() => {
      // 六审回修（glm53 P2）：静默窗内可能已被停止/用户已插话/卡已到达——
      // 发出前再校验一次末回合仍是剧本步且无新发送，避免把下一步塞进用户的对话
      const cur = props.state.turns.at(-1);
      if (!demoTotal.value || props.state.sending || !cur || stripDemoPrefix(cur.question) !== demoLastSent || cur.phase === 'waiting') {
        return;
      }
      const next = demoQueue.value.shift();
      if (next) {
        demoStep.value += 1;
        demoLastSent = next;
        emit('pick', next);
      }
    }, demoGap.value);
  }
);
// 批17 Q7：暂停计数——演示期间每次进入 HITL 等确认（把关节点）记一次，完成小结条披露
watch(demoPaused, (paused) => {
  if (paused && demoTotal.value) {
    demoWaits.value += 1;
  }
});
// 切会话即终止演示：剧本步不能跟着发进新 thread（rebuild 清队列之外的前端闸）
watch(
  () => props.state.threadId,
  () => {
    if (demoTotal.value) {
      stopDemo();
    }
  }
);

/** 跟随滚动：新回合/打字推进时贴底（follow=false 时尊重用户上翻） */
watch(
  () => [
    props.state.turns.length,
    props.state.turns.at(-1)?.shownLen ?? 0,
    props.state.queue.length,
    // k3 终审 P2-2：HITL 卡到达无文本增长（interrupt 不产生 delta），不进 watch 键
    // 则流不滚底、确认按钮首屏不可见——把卡态挂进键，pending 到达即跟随滚到底
    props.state.turns.at(-1)?.hitl?.cardState ?? '',
    // 六审回修补丁（E2E 实锤）：帧序=cardState 先翻 pending、phase 后变 waiting——
    // 仅挂 cardState 时本 watch 在 phase 仍 writing 的瞬间贴底滚（把卡顶推出视口顶），
    // phase 翻转不再触发=卡顶定位从未执行；把 phase 也挂进键，翻转后重定位到卡顶
    props.state.turns.at(-1)?.phase ?? ''
  ],
  () => {
    const last = props.state.turns.at(-1);
    // HITL 卡到达=回合以「等待用户动作」收尾，即使此前上滚阅读历史（follow=false）
    // 也拉回底部——否则确认按钮不在首屏，用户不知道要确认（k3 终审 P2-2）
    const cardArrived = last?.phase === 'waiting' && last.hitl?.cardState === 'pending';
    if (!props.follow && !cardArrived) {
      return;
    }
    nextTick(() => {
      const el = flowRef.value;
      if (!el) {
        return;
      }
      // 待确认卡已移到输入框位置（HitlDock），流内只剩一行指路——贴底即可露出那一问
      el.scrollTop = el.scrollHeight;
    });
  }
);

/** 秒级时钟（tstat 耗时/思考秒数；sending 期间 1s 心跳，静止即停） */
let clock: ReturnType<typeof setInterval> | null = null;
watch(
  () => props.state.sending,
  (v) => {
    if (v && !clock) {
      clock = setInterval(() => (now.value = Date.now()), 1000);
    } else if (!v && clock) {
      clearInterval(clock);
      clock = null;
    }
  },
  { immediate: true }
);
onMounted(() => {
  emit('scroll-lock', flowRef.value ?? null);
  flowRef.value?.addEventListener('scroll', onFlowScroll, { passive: true });
  // m3 P1-6：resize 一并复测长消息折叠判定（气泡宽度变化→scrollHeight 变化，旧判定过期）
  window.addEventListener('resize', onWinResize, { passive: true });
  document.addEventListener('selectionchange', onSelChange);
});
onBeforeUnmount(() => {
  if (clock) {
    clearInterval(clock);
  }
  window.clearTimeout(demoTimer);
  if (selReposRaf) {
    cancelAnimationFrame(selReposRaf);
  }
  flowRef.value?.removeEventListener('scroll', onFlowScroll);
  window.removeEventListener('resize', onWinResize);
  document.removeEventListener('selectionchange', onSelChange);
  emit('scroll-lock', null);
});

/** DP-3 原型 jumpToStep：统计面板步骤行→流内对应工具行；目标被回合折叠藏起时先展开再跳 */
function flashTo(el: HTMLElement) {
  el.classList.remove('flash');
  void el.offsetWidth;
  el.classList.add('flash');
  el.scrollIntoView({ behavior: 'smooth', block: 'center' });
  window.setTimeout(() => el.classList.remove('flash'), 1500);
}
function jumpToStep(stepId: string) {
  const flow = flowRef.value;
  if (!flow) {
    return;
  }
  const tryFlash = () => {
    const el = flow.querySelector<HTMLElement>(`[data-step-id="${stepId}"]`);
    if (!el) {
      return false;
    }
    if (!el.offsetParent) {
      // 回合处于折叠态（v-show 藏起）：通知该回合展开（TurnBlock 根监听，userFolded 防自动再收）
      el.closest('.dcs-turn')?.dispatchEvent(new CustomEvent('dcs-expand-fold'));
      nextTick(() => flashTo(el));
    } else {
      flashTo(el);
    }
    return true;
  };
  if (tryFlash()) {
    return;
  }
  // 批1审查 P1：目标可能落在 >5 步回合的中段折叠区（DOM 缺席）——按 turns 序定位所属
  // 回合，先令其展开全部步骤（TurnBlock 根监听 dcs-expand-steps 置 stepsAll）再重查；
  // 回合若还整体折叠，重查命中后走上面的 dcs-expand-fold 分支
  const idx = props.state.turns.findIndex((t) => t.steps.some((s) => s.id === stepId));
  const turnEl = idx >= 0 ? flow.querySelectorAll<HTMLElement>('.dcs-turn')[idx] : null;
  if (turnEl) {
    turnEl.dispatchEvent(new CustomEvent('dcs-expand-steps'));
    nextTick(() => tryFlash());
  }
}
/** DP-3 原型 statJumpConfirm：面板 waiting 态点头→待确认停靠区
 *  确认卡已移到输入框位置（HitlDock），流内只有一行指路——
 *  跳转=先把流贴底（露出触发确认的一问），再聚焦停靠区（键盘可直接操作） */
function jumpToConfirm() {
  const flow = flowRef.value;
  if (flow) {
    flow.scrollTop = flow.scrollHeight;
  }
  const el = findVisibleHitlDock();
  if (el) {
    el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    el.focus({ preventScroll: true });
  }
}
defineExpose({ jumpToStep, jumpToConfirm });
</script>

<style lang="scss" scoped>
.dcs-flow {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  scroll-behavior: smooth;
  /* 抽屉↔全屏形态切换时 padding 14↔24 平滑过渡（让位过渡已随廊位机制上移宿主父层） */
  transition: padding 0.3s ease;
  /* UI 专项批1（5.3fh 审查「什么都白」）：消息区铺第一层浅灰底，衬托用户气泡与 AI 卡片 */
  background: var(--el-fill-color-lighter, #fafafa);

  /* 原型 #msgFlow 细滚动条（L298-300）：6px/thumb 透明 hover 显 */
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
  &::-webkit-scrollbar {
    width: 6px;
  }
  &::-webkit-scrollbar-thumb {
    background: transparent;
    border-radius: 3px;
  }
  &::-webkit-scrollbar-track {
    background: transparent;
  }
  &:hover {
    scrollbar-color: rgba(31, 35, 40, 0.18) transparent;

    &::-webkit-scrollbar-thumb {
      background: rgba(31, 35, 40, 0.18);
    }
  }

  /* 全屏形态（原型 #fullFlow L503：900px 限宽居中）；UI 专项批1：全屏画布加深一档（lighter→light）——
     大面积下 #fafafa 与白卡明度差不足（视觉评审 ui3「白上白」实锤），拉开三层背景层次 */
  &.full {
    padding: 24px;
    align-items: center;
    background: var(--el-fill-color-light, #f5f7fa);

    /* Q7（拍板 2026-10-10）：限宽只作用于内容块——tobot 圆钮保持 34px
       （session 页补 :full 后 >* 会把它撑成 900 宽的条，实测回归）
       Q5（ZCode 对齐，2026-10-10）：内容块左贴会话列表（align-self 替代父层 center——
       列左缘恒 24px，与 Composer .box/chips 同几何；批A Q7 2026-10-11 起 tobot
       水平基准改「内容列中线」——帽豁免只为保 34px 正圆，几何见 .tobot margin 公式）
       宽屏加宽（2026-10-11 用户拍板）：帽改读宿主变量（Composer 观测宿主写入的
       --dcs-col-max，默认 900；宿主 ≥1380 时 1080）——答案区与输入框同一条列几何 */
    > *:not(.tobot) {
      width: 100%;
      max-width: var(--dcs-col-max, 900px);
      align-self: flex-start;
    }
    .mrow-me .me-wrap {
      max-width: 76%;
    }
  }

  /* Q8/Q9（拍板 2026-10-10）：面板廊位让出（胶囊态 124 / 展开态 248）已上移宿主父层
     （会话页 .main / 抽屉 .dw-body）——消息与输入框共用一条几何，本组件不再持有让位 padding；
     ZCode 同构：宽度公式由列的父级统一裁决，面板浮层与内容互不侵占 */

  /* 原型 .mrow 入场动画（L174-175） */
  .mrow-me,
  :deep(.dcs-turn) {
    animation: dcs-min 0.25s ease;
  }
}
@keyframes dcs-min {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
}
.mrow-me {
  display: flex;
  flex-direction: row-reverse;
  align-items: flex-end;
  gap: 9px;
  /* UI 专项批1：34px→24px——问答组内收紧，组间节奏交给 dcs-turn 的上边距，8pt 节奏 */
  margin-top: 24px;
  animation: dcs-min 0.25s ease;

  &:first-of-type {
    margin-top: 0;
  }
  /* 原型 .avatar（L177）：30px 首字圆标（主色浅底+主色字） */
  .avatar {
    flex: none;
    width: 30px;
    height: 30px;
    border-radius: 50%;
    background: var(--el-color-primary-light-9);
    border: 1px solid var(--el-color-primary-light-7);
    color: var(--el-color-primary);
    font-size: 13px;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
    user-select: none;
  }
  .me-wrap {
    position: relative;
    max-width: 94%;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
  }
  .bub {
    /* UI 专项批1（5.3fh 审查「用户气泡无身份色」）：白底灰边→主色浅底身份气泡——
       与 AI 灰底卡片形成双向对比，扫一眼可辨谁说的（EP 体系非自定义色） */
    background: var(--el-color-primary-light-9);
    border: 1px solid var(--el-color-primary-light-7);
    border-radius: 12px 4px 12px 12px;
    padding: 10px 13px;
    font-size: 14px;
    line-height: 1.65;
    color: var(--dcs-text-primary);
    white-space: pre-wrap;
    word-break: break-word;

    &.clamp {
      max-height: 260px;
      overflow: hidden;
      position: relative;
      mask-image: linear-gradient(#000 78%, transparent);
    }
  }
  .ts {
    position: absolute;
    top: -20px;
    right: 2px;
    font-size: 11px;
    color: var(--dcs-text-placeholder);
    opacity: 0;
    transition: opacity 0.15s;
    pointer-events: none;
    white-space: nowrap;
  }
  .me-wrap:hover .ts,
  .me-wrap:focus-within .ts {
    opacity: 1;
  }
  .bexp {
    /* 业务批4 Q8（ZCode 口径）：居中胶囊钮叠在渐隐末端（原右下 ⋯ 圆钮易被当气泡装饰） */
    position: absolute;
    bottom: 6px;
    left: 50%;
    transform: translateX(-50%);
    border-radius: 999px;
    border: 1px solid var(--dcs-border-color-light);
    background: var(--el-bg-color);
    color: var(--dcs-text-regular);
    cursor: pointer;
    font-size: 12px;
    line-height: 1;
    padding: 0 12px;
    height: 22px;
    box-shadow: var(--el-box-shadow-light);

    &:hover {
      color: var(--el-color-primary);
      border-color: var(--el-color-primary-light-5);
    }
  }
  &.pending {
    opacity: 0.55;
    .bub {
      border-style: dashed;
    }
    .p-tag {
      position: absolute;
      top: -18px;
      right: 2px;
      font-size: 11px;
      color: var(--dcs-text-secondary);
    }
    position: relative;
  }
}
.flow-pad {
  flex: none;
  height: 2px;
}

/* 五需求批 R4b 演示模式进度条：sticky 顶随流滚动常驻（长回合不丢进度感），
   白卡+主色描边与 .dcs-form-card 家族同语言；进度线 4px 主色轨灰 */
/* 批17 Q2：压缩分隔条（绿色系弱打扰；role=separator） */
.compact-mark {
  flex: none;
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 10px 2px;
  font-size: 12px;
  color: var(--el-color-success, #67c23a);
  &::before,
  &::after {
    content: '';
    flex: 1;
    border-top: 1px dashed var(--el-color-success-light-5, #b3e19d);
  }
}

.demo-bar {
  position: sticky;
  top: 0;
  z-index: 6;
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border-radius: 10px;
  border: 1px solid var(--el-color-primary-light-7);
  background: var(--el-bg-color);
  box-shadow: var(--el-box-shadow-light);
  font-size: 12px;
  color: var(--dcs-text-regular);

  b {
    color: var(--el-color-primary);
    font-size: 12px;
    white-space: nowrap;
  }
  span:not(.dbar) {
    white-space: nowrap;
  }
  /* step5 P1-2 回修：幕名+步数文案可收缩——长幕名(暂停态最长)在抽屉窄流里把
     「停止演示」挤出可视区并诱发横向滚动条；min-width:0 解锁 flex 收缩+省略号 */
  .db-title {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  /* 批17 Q7：速度档三键（分段控件形态，克制蓝高亮当前档） */
  .dspeed {
    flex: none;
    display: inline-flex;
    border: 1px solid var(--el-border-color);
    border-radius: 6px;
    overflow: hidden;

    button {
      padding: 2px 7px;
      border-radius: 0;
      color: var(--dcs-text-secondary);

      &.on {
        background: var(--el-color-primary-light-9);
        color: var(--el-color-primary);
        font-weight: 600;
      }

      & + button {
        border-left: 1px solid var(--el-border-color-lighter);
      }
    }
  }
  .dbar {
    flex: 1;
    min-width: 40px;
    height: 4px;
    border-radius: 2px;
    background: var(--el-fill-color);
    overflow: hidden;

    i {
      display: block;
      height: 100%;
      background: var(--el-color-primary);
      border-radius: 2px;
      transition: width 0.4s ease;
    }
  }
  button {
    flex: none;
    border: none;
    background: none;
    color: var(--dcs-text-secondary);
    cursor: pointer;
    font-size: 12px;
    padding: 2px 8px;
    border-radius: 6px;

    &:hover {
      color: var(--el-color-danger);
      background: var(--el-color-danger-light-9);
    }
  }
}

/* 回到底部钮（流内 sticky 尾元素；原型 tobot L546-548：34px/bd2 边/深投影/16px）。
   批2-1 Q2-2（拍板 2026-10-10）：bottom 10→14px——浮钮离消息过近压字（评审两轮点名），
   三框架浮钮均悬在内容区上方留空，DCS 流内 sticky 下的等价优化 */
.tobot {
  position: sticky;
  bottom: 14px;
  /* 批A Q7（拍板 2026-10-11）：水平基准从「滚动容器中线」改「内容列中线」——宽形态列帽
     限宽靠左时按钮不再钉死整宽正中；窄抽屉无列帽（流宽<900）时 50% 项胜出=原居中不变。
     margin 百分比按流容器内容宽解析：列中心 = min(容器宽, 列宽)/2，减 17px=按钮半宽 */
  align-self: flex-start;
  margin-left: min(calc(50% - 17px), calc(var(--dcs-col-max, 900px) / 2 - 17px));
  margin-top: -34px;
  /* Q7（拍板 2026-10-10）：流是纵向 flex 容器，末位子项默认 flex-shrink:1 会被长会话
     纵向压缩成 34×21 扁椭圆——flex:none 保住 34×34 正圆（ZCode 同款居中圆钮） */
  flex: none;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 1px solid var(--dcs-border-color-light);
  background: var(--el-bg-color);
  box-shadow: 0 6px 20px rgba(16, 24, 40, 0.16);
  color: var(--dcs-text-secondary);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  z-index: 5;

  &:hover {
    color: var(--el-color-primary);
    border-color: var(--el-color-primary-light-5);
  }
}

/* 选区悬浮操作条（原型 selbar L444-446：深色胶囊 #1f2937/白字/radius 999；Q3 20261011
   起 left 由 selbarStyle 按左缘语义解算，此处不再 translateX 居中） */
.selbar {
  position: fixed;
  display: flex;
  gap: 2px;
  background: #1f2937;
  border-radius: 999px;
  box-shadow: 0 4px 14px rgba(16, 24, 40, 0.3);
  padding: 3px;
  z-index: 40;

  button {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    border: none;
    background: none;
    font-size: 12px;
    color: #d7dce3;
    padding: 3px 10px;
    border-radius: 999px;
    cursor: pointer;
    white-space: nowrap;

    &:hover {
      background: rgba(255, 255, 255, 0.16);
      color: #fff;
    }
  }
}
@media (prefers-reduced-motion: reduce) {
  .dcs-flow {
    scroll-behavior: auto;
  }
}

/* DP-3 跳转闪烁（原型 .flash：面板步骤行/确认卡跳转命中处高亮渐隐） */
.dcs-flow :deep(.tool.flash),
.dcs-flow :deep(.dcs-confirm.flash) {
  animation: dcs-jump-flash 1.5s ease-out;
}
@keyframes dcs-jump-flash {
  0%,
  40% {
    background: var(--el-color-primary-light-8);
    box-shadow: 0 0 0 3px var(--el-color-primary-light-9);
  }
  100% {
    background: transparent;
    box-shadow: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .dcs-flow :deep(.tool.flash),
  .dcs-flow :deep(.dcs-confirm.flash) {
    animation: none;
    background: var(--el-color-primary-light-8);
  }
}
</style>
