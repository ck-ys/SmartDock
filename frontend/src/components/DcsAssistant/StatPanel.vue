<template>
  <transition name="dcs-sp">
    <!-- Q7（ZCode 对齐，2026-10-10）：胶囊与展开是同一元素的两个形态——capsule 常驻做
         透明度淡切（ZCode mini 行常驻子元素），full-body 常驻做 max-height+opacity 过渡；
         原 v-if 双分支卸载重建=宽度/高度/内容三重跳变，是「动画怪」的根因 -->
    <div v-if="turn" ref="panelRoot" class="stat-panel" :class="panelCls" role="status">
      <button class="capsule" :class="{ ghost: !mini }" :tabindex="mini ? 0 : -1" @click="expandMini()">
        <span class="spdot" :class="dotCls" />
        <span class="cnt">{{ doneSteps }}/{{ turn.steps.length || 1 }}</span>
        <span class="c-last">{{ capsuleText }}</span>
      </button>
      <div class="full-body" :class="{ fold: mini }">
        <!-- DP-3 原型 sph：waiting 态点头=跳到确认卡；展开态只留收起钮（原型 L398/L403：btn-max 仅 mini 显示） -->
        <div
          class="sph"
          :class="{ clickable: turn.phase === 'waiting' }"
          :title="turn.phase === 'waiting' ? '点这里跳到确认卡' : undefined"
          @click="onSphClick"
        >
          <span class="spdot" :class="dotCls" />
          <span class="sp-name">{{ stateWord }}</span>
          <!-- k3 P2-4 回修：标注「步骤」——与下方「任务清单 N/N」（todo 口径）相邻，不标注读不出两数各是什么 -->
          <span class="cnt">步骤 {{ doneSteps }}/{{ turn.steps.length || 1 }}</span>
          <span v-if="sphLast" class="c-last">{{ sphLast }}</span>
          <span v-if="durText" class="sp-dur">{{ durText }}</span>
          <span class="sp-act">
            <button class="btn-min" title="收起为胶囊" aria-label="收起为胶囊" @click.stop="collapseMini()">
              <IconSpan name="chevron-down" :size="13" />
            </button>
          </span>
        </div>
        <!-- 任务清单（ZCode 对齐 Q2）：todo_write 整表替换维护；划线+计数+>6 条收 3 条焦点窗 -->
        <div v-if="todoView.items.length" class="todo">
          <div class="todo-hd">
            <span class="todo-name">任务清单</span>
            <span class="todo-cnt" :class="{ all: todoView.done === todoView.items.length }">{{ todoView.done }}/{{ todoView.items.length }}</span>
          </div>
          <!-- Q2（20261011 拍板A）：面板与聊天的联动此前"有而不显"——常驻提示让可点性可发现；
               批1审查 P2：无可跳目标（回放旧会话无 todo_write 步骤）时行本就不可点，提示随之隐藏 -->
          <div v-if="todoStepId" class="todo-hint">↗ 点击清单行 / 步骤行，可在对话中定位到对应消息</div>
          <button v-if="todoView.pre > 0 && !todoOpen" class="todo-fold" @click="todoOpen = true">⋯ 前面 {{ todoView.pre }} 步，点开看全部</button>
          <div
            v-for="(it, i) in todoView.focus"
            :key="i"
            class="todo-it"
            :class="['st-' + it.status, { jump: todoStepId }]"
            :role="todoStepId ? 'button' : undefined"
            :tabindex="todoStepId ? 0 : undefined"
            :title="todoStepId ? '在对话中定位写这份清单的步骤' : undefined"
            @click="onTodoJump"
            @keydown.enter="onTodoJump"
          >
            <span class="tico">
              <IconSpan v-if="it.status === 'completed'" name="check" :size="11" />
            </span>
            <span class="tx">{{ it.content }}</span>
          </div>
          <button v-if="todoView.post > 0 && !todoOpen" class="todo-fold" @click="todoOpen = true">⋯ 还有 {{ todoView.post }} 步待办</button>
        </div>
        <div class="spb">
          <!-- DP-3 原型 foldrow 双向：折叠 ⇄ 展开全部步骤 -->
          <button v-if="turn.steps.length > SPFOCUS" class="spr foldrow" @click="foldOpen = !foldOpen">
            {{ foldOpen ? `⋯ 收起早期步骤，只看最近 ${SPFOCUS} 步` : `⋯ 已折叠前 ${turn.steps.length - SPFOCUS} 步 · 点击展开` }}
          </button>
          <div
            v-for="s in shownSteps"
            :key="s.id"
            class="spr link"
            role="button"
            tabindex="0"
            title="在对话中定位这一步"
            @click="emit('step-jump', s.id)"
            @keydown.enter="emit('step-jump', s.id)"
          >
            <span class="kring" :class="'k-' + s.status">
              <IconSpan v-if="s.status === 'err'" name="x" :size="10" />
              <IconSpan v-else-if="s.status !== 'run'" name="check" :size="11" />
            </span>
            <span class="k-name">{{ s.verb }}{{ s.status === 'err' ? ' · 失败' : '' }}</span>
          </div>
          <div v-if="shownSteps.length === 0" class="spr empty">暂无进行中的步骤</div>
        </div>
        <!-- eslint-disable-next-line vue/no-v-html —— stamp 源串保持 emoji（⚡/🧠/✓），渲染层按 EMO2IC 换 Lucide -->
        <div class="spf" :class="{ sumok }" v-html="emoToSvgHtml(spfText)"></div>
      </div>
    </div>
  </transition>
</template>

<script setup lang="ts">
/**
 * 运行统计面板（v3.16 区域3 + 批I-4 DP-3 对齐原型 statRender）。
 * 全屏形态专属（原型 statdock 只挂 #full）；mini 胶囊手动收/完成后 1.6s 自动收（waiting 不收）。
 * 四交互：sph 双钮 / 步骤行点击跳流内工具行 / waiting 点头跳确认卡 / foldrow 双向展开。
 */
import type { AssistantTurn } from './useAssistant';
import type { AssistantTodoItem } from '@/api/dcs/assistant';
import IconSpan from './IconSpan.vue';
import { emoToSvgHtml } from './icons';
import { turnStateWord } from './assistantCtx';
import { assistantName } from './nameState';

const props = defineProps<{
  turn: AssistantTurn | null;
  todo?: AssistantTodoItem[];
  /** Q2（20261011 拍板A）：todo 行跳转目标=全会话最近一次 todo_write 步骤 id（清单是会话级，
   *  步骤可能在更早回合——由宿主从 state.turns 跨回合解算后传入；无目标=行不可点） */
  todoJumpId?: string | null;
}>();
const emit = defineEmits<{
  /** 步骤行点击：宿主转 MsgFlow.jumpToStep 定位对应工具行 */
  'step-jump': [stepId: string];
  /** sph waiting 态点击：宿主转 MsgFlow.jumpToConfirm 定位确认卡 */
  'jump-confirm': [];
}>();

const SPFOCUS = 4;
/** Q6-B：容器够宽才自动展开（窄容器自动收胶囊，ZCode auto 同语义）。
 *  宽屏加宽（2026-10-11 用户拍板）后阈值不再写死 1150——列帽由宿主变量
 *  --dcs-col-max 动态给（默认 900，宿主 ≥1380 时 1080），展开阈随列帽联动：
 *  colMax + 面板 248 + 边距 28 上下（1080 档=1380 与档位阈同源，不再叠列） */
const YIELD_PANEL_W = 250;

const panelRoot = ref<HTMLElement | null>(null);
const containerWide = ref(true);
/** 追随解算输入：父层（.main/.dw-body）borderBox 宽 + 面板自身实宽（胶囊 min-width 后随内容微变） */
const containerW = ref(0);
const selfW = ref(220);
/** 列宽节拍（宽屏加宽批）：列帽档位切换/宿主 resize 会改 .box 实宽——只听宿主宽时
 *  面板不会重算（预览注入实证列变面板不动）。观察 .box 触发本 ref，进 effect 依赖。 */
const boxTick = ref(0);
const mini = ref(false);
/** 三席审查回修（m3 P1-1 + dspro P2-3）：用户手动开/收过胶囊——live 相位跳变、
 *  1.6s 自动收、容器宽跨阈三类自动行为均不得覆盖手动选择；新回合重置 */
const userMini = ref(false);
function expandMini() {
  mini.value = false;
  userMini.value = true;
}
function collapseMini() {
  mini.value = true;
  userMini.value = true;
}
const foldOpen = ref(false);
let collapseTimer: ReturnType<typeof setTimeout> | null = null;
// Q6-B：观测主列宽度驱动 auto 展开/收胶囊（ResizeObserver 而非 window resize——全屏模态/独立页容器宽 ≠ 窗口宽）
let widthObserver: ResizeObserver | null = null;
let selfObserver: ResizeObserver | null = null;
/** 宽屏加宽批：观测输入卡 .box 实宽（列帽档位切换时触发追随重算） */
let boxObserver: ResizeObserver | null = null;
/** 卸载清理兜底（glm53 P2-5）：挂载期捕获宿主引用——v-if 先摘根（turn 置空）再卸载时
 *  panelRoot 已 null，live 读会 no-op 残留 --sp-strip 让正文列持续让位 */
let cleanupHost: HTMLElement | null = null;
/** 观测条 borderBox 宽（让位 padding 落在被观测父层上时 contentRect 会被自身 padding 缩小，
 *  有「展开→变窄→自动收→变宽」振荡风险——borderBox 不含 padding 语义天然免疫）。
 *  规范坑：ResizeObserverSize 的字段是 inlineSize/blockSize，没有 .width——写 .width 恒
 *  undefined 会静默回退 contentRect（selfW 恒初始值的实锤根因） */
function borderBoxW(entry: ResizeObserverEntry | undefined | null): number {
  const b = entry?.borderBoxWidth ?? entry?.borderBoxSize;
  const single = Array.isArray(b) ? b[0] : b;
  return single?.inlineSize ?? entry?.contentRect.width ?? 0;
}
/** 列帽实值（宽屏加宽批）：从宿主内输入卡 computed max-width 解算（--dcs-col-max
 *  变量在此处已解成 px）。抽屉窄宿主 .box 无帽=none → parseFloat NaN → 900 兜底
 *  （与抽屉现行几何一致：strip 判定只看「放不下」方向，兜底值偏大不改变结论）。 */
function readColMax(host: HTMLElement | null): number {
  const box = host?.querySelector('.dcs-comp .box');
  const v = parseFloat(box ? getComputedStyle(box).maxWidth : '');
  return Number.isFinite(v) && v > 0 ? Math.round(v) : 900;
}

onMounted(() => {
  const target = panelRoot.value?.parentElement;
  cleanupHost = target ?? null;
  if (target && typeof ResizeObserver !== 'undefined') {
    widthObserver = new ResizeObserver((entries) => {
      const w = borderBoxW(entries[0]);
      containerW.value = Math.round(w);
      containerWide.value = w >= readColMax(target) + YIELD_PANEL_W;
    });
    // 三席审查 P2-1：本组件读的就是 borderBox 宽，按 border-box 观测语义一致，
    // 也免父层 --sp-strip 过渡期间 padding 变化触发无谓回调
    widthObserver.observe(target, { box: 'border-box' });
    // 宽屏加宽批：观测输入卡实宽——档位切换（宿主跨 1380）改列帽时 bump boxTick
    // 触发追随 effect 重算（宿主宽此时未必变，只听宿主会漏）
    const box = target.querySelector('.dcs-comp .box');
    if (box) {
      boxObserver = new ResizeObserver(() => {
        boxTick.value += 1;
      });
      boxObserver.observe(box, { box: 'border-box' });
    }
  }
  if (panelRoot.value && typeof ResizeObserver !== 'undefined') {
    selfObserver = new ResizeObserver((entries) => {
      const w = borderBoxW(entries[0]);
      if (w > 0) {
        selfW.value = Math.round(w);
      }
    });
    selfObserver.observe(panelRoot.value, { box: 'border-box' });
  }
});
onBeforeUnmount(() => {
  widthObserver?.disconnect();
  selfObserver?.disconnect();
  boxObserver?.disconnect();
  (cleanupHost ?? panelRoot.value?.parentElement)?.style.removeProperty('--sp-strip');
});

/**
 * 追随几何（Q5 ZCode 对齐，2026-10-10 二修；2026-10-11 宽屏加宽批三修）：主列左贴 24
 * / 帽=宿主变量 --dcs-col-max（默认 900，宿主 ≥1380 时 1080，Composer 观测写入）——
 * 列右缘不写死（宿主嵌套 padding 各异：会话页实测列右=949 而非 924，写死会把面板叠
 * 进正文列 12px），改为写完 strip 后实测 .box 右缘：
 *  · 中屏（W < 列左+colMax+14+P，放不下「列+面板+缝」）：容器右 padding 让位
 *    （strip=P+28，列被压窄、面板贴容器右缘 r=14，与列右恒 gap 14）——让位阈随列帽
 *    动态（原写死 952=24+900+28，加宽档位下会误判放不下）
 *  · 宽屏（放得下）：strip=0（列按帽封顶），面板贴列右缘外 14px（面板跟列走，
 *    2026-10-11 拍板：不钉窗右缘）
 * strip 写父层 CSS 变量 --sp-strip（消息列与输入框共用）；right 写本组件内联。
 * 实测时机=写完 strip 再读 getBoundingClientRect（强制同步布局，读到的是让位后的列右）。
 */
watchEffect(() => {
  // 依赖必须无条件先读（Vue 教训级坑）：若在读取响应式依赖之前因宿主为空早退，
  // 本效应零依赖登记=死效应，宿主就位后也永不重跑（--sp-strip 恒空的实锤根因）。
  // 宿主取 live 读（panelRoot 反向解 parentElement）而非 onMounted 一次性捕获——
  // 三席审查回修：捕获式在挂载瞬间根不在场时会永久丢宿主且无从补救
  // boxTick=列宽节拍（宽屏加宽批）：档位切换改 .box 实宽时本效应须重算（预览实证漏听）
  const w = containerW.value;
  const p = selfW.value;
  void boxTick.value;
  const hasTurn = !!props.turn;
  const root = panelRoot.value;
  const host = root?.parentElement ?? null;
  if (!host || !root) {
    return;
  }
  if (!hasTurn) {
    host.style.removeProperty('--sp-strip');
    root.style.removeProperty('right');
    return;
  }
  const box = host.querySelector('.dcs-comp .box');
  // 让位阈用「列左+列帽」的自然列右（帽是 cap 属性，不受 strip 影响→决策无反馈环）
  const boxLeft = box ? Math.round(box.getBoundingClientRect().left - host.getBoundingClientRect().left) : 24;
  const colMax = readColMax(host);
  const strip = w > 0 && w < boxLeft + colMax + 14 + p ? p + 28 : 0;
  host.style.setProperty('--sp-strip', `${strip}px`);
  const colRight = box ? box.getBoundingClientRect().right - host.getBoundingClientRect().left : 948;
  const right = Math.max(14, Math.round(w - colRight - 14 - p));
  root.style.right = `${right}px`;
});
// 历史回放场景：回合一开始就是终态（无 live 过渡不触发 watch）——挂载即胶囊收起
if (props.turn && !['thinking', 'acting', 'writing', 'waiting'].includes(props.turn.phase)) {
  mini.value = true;
}

const live = computed(() => props.turn != null && ['thinking', 'acting', 'writing'].includes(props.turn.phase));
/** AU-31: live 态秒级时钟——durText 里的 Date.now() 无响应式依赖，computed 永不重算=耗时定格 */
const now = ref(Date.now());
let nowTimer: number | null = null;
watch(
  live,
  (on) => {
    if (on && nowTimer === null) {
      nowTimer = window.setInterval(() => {
        now.value = Date.now();
      }, 1000);
    } else if (!on && nowTimer !== null) {
      window.clearInterval(nowTimer);
      nowTimer = null;
    }
  },
  { immediate: true }
);
const doneSteps = computed(() => props.turn?.steps.filter((s) => s.status !== 'run').length ?? 0);
const shownSteps = computed(() => {
  if (!props.turn) {
    return [];
  }
  return foldOpen.value || props.turn.steps.length <= SPFOCUS ? props.turn.steps : props.turn.steps.slice(-SPFOCUS);
});

/* ===== 任务清单（ZCode 对齐 Q2）：>6 条收 3 条焦点窗 ===== */
/** ZCode ConversationStatusPanel 同款常量：COMPACT_TODO_THRESHOLD=6 / TODO_FOCUS_WINDOW_SIZE=3 */
const TODO_COMPACT = 6;
const TODO_WINDOW = 3;
const todoOpen = ref(false);

/** Q2（20261011 拍板A）：todo 行可点——目标由宿主跨回合解算（清单会话级、步骤可能在
 *  任意回合）；回放/无 steps 的会话无目标时优雅 no-op（行渲染为不可点态） */
const todoStepId = computed(() => props.todoJumpId ?? null);
function onTodoJump() {
  if (todoStepId.value) {
    emit('step-jump', todoStepId.value);
  }
}
/** 清单长度变化时重置展开态（k3 P2-7 回修口径：只 watch length——同长度整表替换
 * （如状态翻转帧）不重置展开，帧更新不打扰用户当前的展开选择，属有意行为） */
watch(
  () => props.todo?.length,
  () => {
    todoOpen.value = false;
  }
);
const todoView = computed(() => {
  const items = props.todo ?? [];
  const done = items.filter((it) => it.status === 'completed').length;
  if (items.length <= TODO_COMPACT || todoOpen.value) {
    return { items, done, focus: items, pre: 0, post: 0 };
  }
  // 焦点=进行中条目（无则首个未完成，再无则尾部窗口）——ZCode getStatusPanelTodoFocusWindow 同算法
  let fi = items.findIndex((it) => it.status === 'in_progress');
  if (fi < 0) {
    fi = items.findIndex((it) => it.status !== 'completed');
  }
  if (fi < 0) {
    fi = items.length - TODO_WINDOW;
  }
  const start = Math.max(0, Math.min(fi, items.length - TODO_WINDOW));
  return {
    items,
    done,
    focus: items.slice(start, start + TODO_WINDOW),
    pre: start,
    post: Math.max(0, items.length - start - TODO_WINDOW)
  };
});

/** Q10 统一词表（turnStateWord 合一）：等确认/思考中/执行中/撰写中/已完成/已停止/失败——
 *  与 TurnBlock 状态条同源，杀掉「正在做/做完了/进程/出了点问题」旧词（执行中动词在尾注 c-last） */
const stateWord = computed(() => (props.turn ? turnStateWord(props.turn.phase) : ''));
const dotCls = computed(() => (live.value ? 'run' : props.turn?.phase === 'error' ? 'err' : props.turn?.phase === 'waiting' ? 'wait' : 'ok'));
/** 面板三态类（批J-2 原型 L405-409）：running/waiting/finish 驱动边框与文案色 */
const panelCls = computed(() => ({
  mini: mini.value,
  running: live.value,
  waiting: props.turn?.phase === 'waiting',
  finish: !live.value && ['done', 'stopped'].includes(props.turn?.phase ?? '')
}));
/** 原型 sph 尾注 c-last：运行=最后一步动词；完成=回合 stamp 摘要 */
const sphLast = computed(() => {
  const t = props.turn;
  if (!t) {
    return '';
  }
  if (live.value) {
    return t.steps.at(-1)?.verb ?? '';
  }
  return t.stamp || '';
});
const capsuleText = computed(() => {
  const t = props.turn;
  if (!t) {
    return '';
  }
  if (t.phase === 'waiting') {
    return turnStateWord('waiting');
  }
  const last = t.steps[t.steps.length - 1];
  if (live.value) {
    return t.phase === 'thinking' ? '思考中' : t.phase === 'writing' ? '撰写中' : (last?.verb ?? '执行中');
  }
  return turnStateWord(t.phase);
});
const durText = computed(() => {
  const t = props.turn;
  if (!t) {
    return '';
  }
  const ms = t.durMs > 0 ? t.durMs : live.value ? now.value - t.startedAt : 0;
  return ms >= 2100 ? (ms < 60_000 ? `${(ms / 1000).toFixed(0)}s` : `${Math.floor(ms / 60_000)}m`) : '';
});
/** 原型 spf 尾行五态：等待你确认/已停止/进行中/✓ 已完成+stamp/本轮完成（无工具=未调用工具）——Q10 词表对齐 */
const spfText = computed(() => {
  const t = props.turn;
  let tail = '';
  if (t?.phase === 'waiting') {
    tail = ' · 等待你确认';
  } else if (t?.phase === 'stopped') {
    tail = ' · 已停止';
  } else if (live.value) {
    tail = ' · 进行中';
  } else if (t?.stamp) {
    tail = ` · ✓ 已完成 · ${t.stamp}`;
  } else if (t?.steps.length) {
    tail = ' · 本轮完成';
  } else {
    tail = ' · 本轮未调用工具';
  }
  return `智能体 · ${assistantName.value}${tail}`;
});
const sumok = computed(() => !!props.turn?.stamp && !live.value && props.turn.phase !== 'waiting' && props.turn.phase !== 'stopped');

function onSphClick() {
  // 原型 sph onclick：非 mini 且 waiting → statJumpConfirm
  if (props.turn?.phase === 'waiting') {
    emit('jump-confirm');
  }
}

/** 新回合重置折叠行与手动胶囊标记；live 展开面板；离开 live 1.6s 自动收胶囊（waiting 期间不收，原型 !statWait 守卫） */
watch(
  () => props.turn?.id,
  () => {
    foldOpen.value = false;
    userMini.value = false;
  }
);
watch(
  () => props.turn?.phase,
  (phase, old) => {
    if (collapseTimer) {
      clearTimeout(collapseTimer);
      collapseTimer = null;
    }
    const livePhases = ['thinking', 'acting', 'writing'];
    const liveNow = !!phase && livePhases.includes(phase);
    const wasLive = !!old && livePhases.includes(old);
    if (liveNow) {
      // Q6-B：进入 live 自动展开（容器够宽才有让位廊位）；live 相位之间的跳变不再重置
      // mini——用户可能已手动收成胶囊（三席审查 dspro P2-3）
      if (!wasLive && !userMini.value) {
        mini.value = !containerWide.value;
      }
    } else if (wasLive && phase !== 'waiting') {
      // 离开 live 1.6s 自动收胶囊；窗口内用户手动展开过则尊重选择（三席审查 m3 P1-1）；
      // 回合已换（极端竞态）也不收
      const turnAtSchedule = props.turn?.id;
      collapseTimer = setTimeout(() => {
        if (props.turn?.id === turnAtSchedule && !userMini.value) {
          mini.value = true;
        }
      }, 1600);
    }
  }
);
// Q6-B：live 期间容器宽度跨阈值时跟随 auto 语义（仅 live 态、且用户未手动选过；手动展开/收起不被覆盖）
watch(containerWide, (wide) => {
  if (live.value && !userMini.value) {
    mini.value = !wide;
  }
});
onBeforeUnmount(() => {
  if (collapseTimer) {
    clearTimeout(collapseTimer);
  }
  if (nowTimer !== null) {
    window.clearInterval(nowTimer);
  }
});
</script>

<style lang="scss" scoped>
.stat-panel {
  position: absolute;
  top: 60px;
  right: 14px;
  width: 220px;
  /* Q9（拍板 2026-10-10）：胶囊与展开卡同锚点（ZCode 原装）——mini 只是高度塌缩，
     不换地方；批G 时代「胶囊退流内防遮挡」由宿主父层的常备廊位接棒（sp-capsule/sp-panel） */
  /* Q7（ZCode 对齐，2026-10-10）：同一元素两形态全可插值——mini 显式宽 168（原 auto
     不可插值=宽度跳变根因）；壳过渡补 border-radius/padding（ZCode 外观四件套+几何）；
     overflow:hidden 让 full-body 的 max-height 塌缩不撑壳 */
  overflow: hidden;
  &.mini {
    width: 168px;
    border: none;
    box-shadow: none;
    background: transparent;
  }
  /* 原型 L376：320px/radius 16/深投影/四属性过渡（mini 变形用）；批J-2 对齐 */
  border-radius: 16px;
  background: var(--el-bg-color);
  border: 1px solid var(--dcs-border-color-lighter);
  box-shadow: 0 8px 28px rgb(16 24 40 / 10%);
  font-size: 12.5px;
  z-index: 5;
  max-height: min(64vh, 32rem);
  display: flex;
  flex-direction: column;
  transition:
    width 0.3s,
    right 0.3s,
    border-radius 0.3s,
    padding 0.3s,
    border-color 0.3s,
    box-shadow 0.3s,
    background 0.3s;
  /* 原型三态（L405-409）：running 点环琥珀脉冲 / waiting 边框+文案琥珀 / finish 绿 */
  &.running {
    .spdot.run {
      background: var(--dcs-color-warning, #e6a23c);
      animation: dcs-ppring 1s ease infinite;
    }
  }
  &.waiting {
    border-color: #f3d19e;

    .sp-name {
      color: #a0640a;
    }
  }
  &.finish .spdot.ok {
    background: var(--dcs-color-success);
  }

  &.mini {
    border-radius: 999px;
    padding: 0;
  }
}
/* Q7：胶囊常驻淡切（ZCode mini 行 150ms opacity）——非 mini 态绝对定位退出流+隐交互 */
.capsule {
  /* 三反馈③（拍板 2026-10-10）：定最小宽+内容居中——live 期文本随阶段切换（思考中→查任务→…）
     宽度反复伸缩，右锚定下左缘每秒跳；定宽后宽度稳定，跟随几何（hug）的解算输入也随之稳定 */
  min-width: 136px;
  justify-content: center;
  display: flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: var(--el-bg-color);
  border-radius: 999px;
  padding: 5px 12px;
  cursor: pointer;
  font-size: 12px;
  color: var(--dcs-text-regular);
  box-shadow: 0 2px 8px rgb(0 0 0 / 12%);
  transition: opacity 0.15s;

  &.ghost {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    opacity: 0;
    visibility: hidden;
    pointer-events: none;
  }

  .c-last {
    max-width: 110px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
/* Q7：展开体常驻，max-height+opacity 过渡（同元素可插值的另一半） */
.full-body {
  display: flex;
  flex-direction: column;
  min-height: 0;
  max-height: min(64vh, 32rem);
  opacity: 1;
  visibility: visible;
  transition:
    max-height 0.3s ease,
    opacity 0.2s ease,
    /* k3 P2-6 回修：visibility 延迟到收起过渡末帧——立即 hidden 会把 0.2s 淡出整个吞掉 */ visibility 0s linear 0.3s;

  &.fold {
    max-height: 0;
    opacity: 0;
    visibility: hidden;
    pointer-events: none;
  }
}
.spdot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;

  &.run {
    background: var(--el-color-primary);
    animation: dcs-dot 1.2s ease-in-out infinite;
  }
  &.ok {
    background: var(--dcs-color-success);
  }
  &.wait {
    background: var(--dcs-color-warning);
  }
  &.err {
    background: var(--dcs-color-danger);
  }
}
@keyframes dcs-dot {
  50% {
    opacity: 0.35;
  }
}
.sph {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--dcs-border-color-lighter);
  font-size: 13px;

  &.clickable {
    cursor: pointer;
  }

  .sp-name {
    font-weight: 600;
    color: var(--dcs-text-primary);
    flex: none;
  }
  /* 原型 L382：展开态计数绿 600（mini 胶囊恒灰） */
  .cnt {
    color: var(--dcs-color-success);
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    flex: none;
  }
  .sp-dur {
    color: var(--dcs-text-secondary);
    font-variant-numeric: tabular-nums;
    flex: none;
  }
  .c-last {
    min-width: 0;
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--dcs-text-secondary);
  }
  .sp-act {
    margin-left: auto;
    display: flex;
    gap: 2px;
    flex: none;

    .btn-min,
    .btn-max {
      border: none;
      background: none;
      cursor: pointer;
      color: var(--dcs-text-secondary);
      padding: 3px;
      border-radius: 6px;
      display: flex;

      &:hover {
        background: var(--el-fill-color-light);
        color: var(--dcs-text-primary);
      }
    }
  }
}
/* mini 胶囊内计数恒灰（原型 L404） */
.mini .cnt {
  color: var(--dcs-text-secondary);
  font-weight: 400;
}
/* 任务清单（ZCode 对齐 Q2，EP 企业风：克制状态色+tabular 计数+划线完成态） */
.todo {
  padding: 6px 6px 4px;
  border-bottom: 1px solid var(--dcs-border-color-lighter);
  display: flex;
  flex-direction: column;
  gap: 2px;

  .todo-hd {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 2px 6px 4px;

    .todo-name {
      font-size: 12px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
    .todo-cnt {
      font-size: 11.5px;
      color: var(--dcs-text-secondary);
      font-variant-numeric: tabular-nums;

      &.all {
        color: var(--dcs-color-success);
      }
    }
  }
  .todo-hint {
    padding: 0 6px 2px;
    font-size: 11px;
    color: var(--dcs-text-secondary);
    opacity: 0.85;
  }
  .todo-it {
    display: flex;
    align-items: center;
    gap: 7px;
    padding: 4px 6px;
    border-radius: 6px;
    font-size: 12px;

    /* Q2（20261011 拍板A）：有跳转目标时可点——hover 手势+浅底让"点得动"可发现 */
    &.jump {
      cursor: pointer;
      transition: background-color 0.15s;

      &:hover,
      &:focus-visible {
        background: var(--el-fill-color-light);

        .tx {
          color: var(--el-color-primary);
        }
      }
      &:focus-visible {
        outline: 1px solid var(--el-color-primary);
      }
    }

    .tico {
      width: 14px;
      height: 14px;
      border-radius: 50%;
      border: 1.5px solid var(--dcs-border-color);
      flex: none;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .tx {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      color: var(--dcs-text-regular);
    }
    &.st-completed {
      .tico {
        border-color: var(--dcs-color-success);
        color: var(--dcs-color-success);
      }
      .tx {
        color: var(--dcs-text-placeholder);
        text-decoration: line-through;
      }
    }
    &.st-in_progress {
      .tico {
        border-color: var(--dcs-color-warning, #e6a23c);
        background: var(--dcs-color-warning, #e6a23c);
        animation: dcs-dot 1.2s ease-in-out infinite;
      }
      .tx {
        color: var(--dcs-text-primary);
        font-weight: 500;
      }
    }
  }
  .todo-fold {
    border: none;
    background: none;
    padding: 3px 6px;
    font: inherit;
    font-size: 11.5px;
    color: var(--dcs-text-secondary);
    cursor: pointer;
    text-align: left;

    &:hover {
      color: var(--el-color-primary);
    }
  }
}
.spb {
  /* 原型 L389：滚动承载+flex 1（展开全部步骤不撑破面板） */
  padding: 4px 6px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
  flex: 1;

  .spr {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 7px 6px;
    border-radius: 8px;
    color: var(--dcs-text-regular);
    min-width: 0;

    &.link {
      cursor: pointer;

      &:hover {
        background: var(--el-fill-color-light);
      }
      &:hover .k-name {
        color: var(--el-color-primary);
      }
      &:focus-visible {
        outline: 2px solid var(--el-color-primary-light-5);
        outline-offset: 1px;
        border-radius: 4px;
      }
    }
    &.foldrow {
      border: none;
      background: none;
      padding: 5px 6px;
      font: inherit;
      font-size: 12px;
      color: var(--dcs-text-secondary);
      cursor: pointer;
      text-align: left;

      &:hover {
        color: var(--el-color-primary);
      }
    }
    &.empty {
      color: var(--dcs-text-placeholder);
    }

    .k-name {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .kring {
      width: 16px;
      height: 16px;
      border-radius: 50%;
      border: 1.5px solid var(--dcs-border-color);
      font-size: 9px;
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--dcs-text-placeholder);
      flex: none;

      &.k-ok {
        border-color: var(--dcs-color-success);
        color: var(--dcs-color-success);
      }
      &.k-err {
        border-color: var(--dcs-color-danger);
        color: var(--dcs-color-danger);
      }
      /* 原型 L392：运行步骤环琥珀脉冲 */
      &.k-run {
        border-color: var(--dcs-color-warning, #e6a23c);
        color: var(--dcs-color-warning, #e6a23c);
        animation: dcs-ppring 1s ease infinite;
      }
    }
  }
}
@keyframes dcs-ppring {
  50% {
    box-shadow: 0 0 0 6px rgba(230, 162, 60, 0.1);
  }
}
.spf {
  padding: 8px 12px;
  border-top: 1px solid var(--dcs-border-color-lighter);
  color: var(--dcs-text-placeholder);
  font-size: 11.5px;

  &.sumok {
    color: var(--dcs-color-success);
  }
}
.dcs-sp-enter-active,
.dcs-sp-leave-active {
  transition:
    opacity 0.2s,
    transform 0.2s;
}
.dcs-sp-enter-from,
.dcs-sp-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
@media (prefers-reduced-motion: reduce) {
  .spdot.run {
    animation: none;
  }
}
</style>
