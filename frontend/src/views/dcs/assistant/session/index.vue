<template>
  <div class="app-container dcs-as-session">
    <!-- 左：会话列表（搜索/新会话/置顶/时间分组/归档折叠） -->
    <aside class="side">
      <div class="side-hd">
        <span class="side-t">我的助手会话</span>
        <div class="side-ops">
          <el-button link type="primary" :loading="loading" title="刷新" @click="loadConversations"><IconSpan name="refresh" /></el-button>
          <el-button link type="primary" title="新会话" @click="onNewSession"><IconSpan name="plus" /> 新会话</el-button>
        </div>
      </div>
      <el-input v-model="kw" class="side-search" placeholder="搜索会话标题" clearable :prefix-icon="SearchIcon" />

      <div class="side-body">
        <el-empty
          v-if="!loading && grouped.length === 0 && archivedExpanded.length === 0"
          description="还没有会话——点「新会话」或右下角悬浮球开始"
          :image-size="64"
        />
        <!-- R4 二改(2026-10-10)：分组头带计数徽标（置顶组配 pin 图标）；「更早」收成折叠条
             （长列表不淹今天/昨天，计数可见），搜索/选中命中更早时自动展开 -->
        <template v-for="g in groupedTop" :key="g.key">
          <div class="grp">
            <IconSpan v-if="g.key === '置顶'" name="map-pin" :size="11" />
            <span>{{ g.key }}</span>
            <span class="g-badge">{{ g.items.length }}</span>
          </div>
          <div
            v-for="c in g.items"
            :key="c.id"
            class="conv-item"
            :class="{ on: c.id === selectedId, pinned: pins.includes(String(c.id)) }"
            :aria-current="c.id === selectedId ? 'true' : undefined"
            @click="openConversation(c)"
          >
            <span v-if="rowStatus(c) === 'answering'" class="sspin" title="正在回答…"><IconSpan name="loader-circle" :size="11" /></span>
            <span v-else-if="rowStatus(c) === 'error'" class="sdot sdot-err" title="最近一轮出错，可点重试" />
            <span v-else-if="isActive(c)" class="sdot" title="最近活跃" />
            <div class="c-main">
              <div class="c-title">
                <template v-if="renamingId === c.id">
                  <input
                    ref="renameRef"
                    v-model="renameVal"
                    class="c-rename"
                    maxlength="60"
                    @keydown.enter="saveRename(c)"
                    @keydown.esc="renamingId = ''"
                    @blur="saveRename(c)"
                  />
                </template>
                <template v-else>{{ c.title || '（未命名会话）' }}</template>
              </div>
              <div class="c-meta">{{ rowStatus(c) === 'answering' ? '正在回答…' : dayTime(c.lastMessageTime) }}</div>
            </div>
            <span v-if="renamingId !== c.id && c.id !== 'tmp'" class="c-ops">
              <button title="重命名" @click.stop="startRename(c)"><IconSpan name="pencil" /></button>
              <button :title="pins.includes(String(c.id)) ? '取消置顶' : '置顶'" @click.stop="togglePin(c)">
                <IconSpan name="map-pin" />
              </button>
              <button title="归档" @click.stop="archiveConv(c)"><IconSpan name="database" /></button>
            </span>
          </div>
        </template>

        <!-- 更早折叠条（与「已归档」同款交互；默认收起） -->
        <div v-if="olderGroup" class="grp-fold" @click="olderOpen = !olderOpen">
          <IconSpan name="history" :size="13" /> 更早 ({{ olderGroup.items.length }})
          <IconSpan :name="olderOpen ? 'chevron-up' : 'chevron-down'" :size="12" />
        </div>
        <template v-if="olderOpen && olderGroup">
          <div
            v-for="c in olderGroup.items"
            :key="c.id"
            class="conv-item"
            :class="{ on: c.id === selectedId, pinned: pins.includes(String(c.id)) }"
            :aria-current="c.id === selectedId ? 'true' : undefined"
            @click="openConversation(c)"
          >
            <span v-if="rowStatus(c) === 'answering'" class="sspin" title="正在回答…"><IconSpan name="loader-circle" :size="11" /></span>
            <span v-else-if="rowStatus(c) === 'error'" class="sdot sdot-err" title="最近一轮出错，可点重试" />
            <div class="c-main">
              <div class="c-title">{{ c.title || '（未命名会话）' }}</div>
              <div class="c-meta">{{ rowStatus(c) === 'answering' ? '正在回答…' : dayTime(c.lastMessageTime) }}</div>
            </div>
            <span class="c-ops">
              <button title="重命名" @click.stop="startRename(c)"><IconSpan name="pencil" /></button>
              <button :title="pins.includes(String(c.id)) ? '取消置顶' : '置顶'" @click.stop="togglePin(c)">
                <IconSpan name="map-pin" />
              </button>
              <button title="归档" @click.stop="archiveConv(c)"><IconSpan name="database" /></button>
            </span>
          </div>
        </template>

        <!-- 已归档折叠条 -->
        <div v-if="archived.length > 0" class="arch-fold" @click="archOpen = !archOpen">
          <IconSpan name="database" /> 已归档 ({{ archived.length }})
          <IconSpan :name="archOpen ? 'chevron-up' : 'chevron-down'" :size="12" />
        </div>
        <template v-if="archOpen">
          <div v-for="c in archivedExpanded" :key="c.id" class="conv-item archived" @click="openConversation(c)">
            <div class="c-main">
              <div class="c-title">{{ c.title || '（未命名会话）' }}</div>
              <div class="c-meta">{{ dayTime(c.lastMessageTime) }}</div>
            </div>
            <span class="c-ops"><button title="恢复会话" @click.stop="restoreConv(c)">↩</button></span>
          </div>
        </template>

        <!-- AU-19：超 50 条的旧会话靠这里继续翻页（后端单页 clamp 100） -->
        <div v-if="realCount < convTotal" class="load-more" role="button" tabindex="0" @click="loadMoreConversations">
          加载更多（已显示 {{ realCount }}/{{ convTotal }}）
        </div>
      </div>
    </aside>

    <!-- 右：对话区（单例 store 接管——与抽屉/全屏同一状态机，可继续对话） -->
    <!-- Q8/Q9/三反馈②：廊位由 StatPanel 解算写 --sp-strip 变量（面板贴内容列右缘），
         本层只消费变量——消息列与输入框同让同回，浮层面板不吃 padding -->
    <section class="main">
      <div class="main-hd">
        <span>{{ selected ? selected.title || '（未命名会话）' : '新会话' }}</span>
        <el-tag v-if="selected" size="small" type="info">thread {{ selected.threadId?.slice(0, 8) }}</el-tag>
        <el-tag v-if="selected?.status === 'ARCHIVED'" size="small" type="warning">已归档（只读回放，发消息前先恢复）</el-tag>
      </div>

      <!-- Q6-B（拍板 2026-10-10）：补 :full 统一口径（900 居中 24padding，与全屏一致；此前满宽 14padding 两套口径） -->
      <msg-flow
        ref="msgFlowRef"
        :state="state"
        :cur="cur"
        :follow="follow"
        full
        @scroll-lock="onScrollLock"
        @resolve-hitl="resolveHitl"
        @pick="(p) => onSend(p, '')"
        @resend="onResend"
        @resend-run="(q) => onSend(q, '')"
        @resume="onResume"
        @recover-interrupt="recoverInterrupt"
        @fill="onFillDraft"
        @new-session="newSession"
        @tour="startTour"
        @open-help="help.show()"
        @branch="onBranch"
        @ask="(t) => onSend(t, '')"
        @pgoto="onPageGoto"
        @mdnav="(u) => router.push(u)"
        @draft-set="onDraftSet"
        @plan-cancel="onPlanCancel"
      />

      <composer
        ref="composerRef"
        full
        active
        :state="state"
        :sending="state.sending"
        @send="onSend"
        @stop="onStop"
        @queue-edit="onQueueEdit"
        @queue-go="onQueueGo"
        @queue-drop="onQueueDrop"
        @queue-resume="onQueueResume"
        @auto-level="setAutoLevel"
        @think-show="setThinkShow"
        @open-help="help.show()"
        @new-session="newSession"
        @compacted="onCompacted"
      />

      <gate-card v-if="showGate" @ok="onGateOk" />

      <!-- 统计面板（DP-3：会话页=全屏形态，对齐原型 statdock 挂 fp-main 右上） -->
      <stat-panel
        v-if="state.turns.length > 0"
        :turn="cur()"
        :todo="state.todoItems"
        :todo-jump-id="todoJumpId"
        @step-jump="(id) => msgFlowRef?.jumpToStep(id)"
        @jump-confirm="msgFlowRef?.jumpToConfirm()"
      />

      <!-- 动作账本（查库留痕对账，G3；选中会话时展示） -->
      <el-collapse v-if="actions.length > 0" class="act-ledger">
        <el-collapse-item :title="`动作账本（${actions.length} 条查库留痕）`">
          <el-table :data="actions" size="small">
            <el-table-column label="动作" width="140">
              <!-- 批I 账本两列：动作列复用 ACT 工具词典（旧英文工具名直出） -->
              <template #default="{ row }">{{ ACT[row.toolName] ?? row.toolName }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <!-- 批I 账本两列：旧按不存在的 SUCCESS 枚举判色永不命中+英文直出→logSt 共用映射 -->
              <template #default="{ row }">
                <el-tag :type="logSt(row.status).type" size="small">{{ logSt(row.status).label }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="结果摘要" prop="resultSummary" show-overflow-tooltip />
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </section>

    <!-- 使用说明弹层（与抽屉共享 helpModal 单例开关） -->
    <el-dialog :model-value="help.open.value" title="SmartDock 小助手 使用说明" width="480px" append-to-body @update:model-value="help.hide()">
      <div class="dcs-help-body">
        <h4>三步上手</h4>
        <ol class="help-steps">
          <li>选择左侧会话，或点「新会话」开始提问</li>
          <li>需要查数据时它会自己查，涉及催办等会动真实数据的操作先弹确认卡</li>
          <li>回答尾部有费用与「AI 生成」标识，重要数字以系统页面为准</li>
        </ol>
        <h4>会话管理</h4>
        <ul class="help-ul">
          <li>悬停会话行：<IconSpan name="pencil" /> 重命名 / <IconSpan name="map-pin" /> 置顶 / <IconSpan name="database" /> 归档</li>
          <li>归档的会话收进「已归档」折叠条，↩ 可恢复</li>
          <li>本页与悬浮球抽屉共用同一会话状态——在哪儿聊都连续</li>
        </ul>
      </div>
      <template #footer>
        <el-button type="primary" @click="help.hide()">开始使用</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * AI 助手独立会话页（批E → 批D 升级）。悬浮球抽屉之外的完整视角：
 * 左栏=会话管理（搜索/新会话/置顶 localStorage/重命名 PATCH/归档二次确认+恢复/活动绿点/
 * 相对时间分组/已归档折叠）；右栏=单例 store 接管的可继续对话区（批A0 架构兑现——
 * 与抽屉/全屏同一状态机，MsgFlow/Composer/GATE 全量复用）+ 动作账本（G3 查库留痕对账）。
 * 权限=dcs:assistant:use（菜单 11920 perms 同源）。
 */
import { ElMessage, ElMessageBox } from 'element-plus';
import { Search as SearchIcon } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import {
  archiveConversation,
  listActions,
  listConversations,
  listMessages,
  renameConversation,
  restoreConversation,
  type AssistantAction,
  type AssistantConversation
} from '@/api/dcs/assistant';
import { useUserStore } from '@/store/modules/user';
import { getAssistant, ACT, logSt, tryAcquireRemoteWatch, releaseRemoteWatch } from '@/components/DcsAssistant/useAssistant';
import type { AssistantBindings } from '@/components/DcsAssistant/useAssistant';
import { useHelpModal } from '@/components/DcsAssistant/helpModal';
import { relTime, cnEpoch, cnDayDiff } from '@/components/DcsAssistant/time';
import { pushToast } from '@/components/DcsAssistant/toastStore';
import MsgFlow from '@/components/DcsAssistant/MsgFlow.vue';
import { startTour } from '@/components/DcsAssistant/useTour';
import IconSpan from '@/components/DcsAssistant/IconSpan.vue';
import Composer from '@/components/DcsAssistant/Composer.vue';
import GateCard from '@/components/DcsAssistant/GateCard.vue';
import StatPanel from '@/components/DcsAssistant/StatPanel.vue';
import type { AssistantTurn } from '@/components/DcsAssistant/useAssistant';

const router = useRouter();
const userStore = useUserStore();
const help = useHelpModal();

const loading = ref(false);
const conversations = ref<AssistantConversation[]>([]);
const selected = ref<AssistantConversation | null>(null);
const selectedId = ref<number | string>('');
const actions = ref<AssistantAction[]>([]);
const kw = ref('');
const archOpen = ref(false);

/** 置顶（localStorage 一期，跨设备同步登记二站） */
const PIN_KEY = 'dcs:assistant:pins';
const pins = ref<string[]>(
  (() => {
    try {
      return JSON.parse(localStorage.getItem(PIN_KEY) ?? '[]');
    } catch {
      return [];
    }
  })()
);
function togglePin(c: AssistantConversation) {
  // 自查回修：临时行不可操作（假 ID 置顶=僵尸 pin，永远匹配不到真实会话）
  if (c.id === 'tmp') {
    return;
  }
  const id = String(c.id);
  pins.value = pins.value.includes(id) ? pins.value.filter((x) => x !== id) : [id, ...pins.value];
  localStorage.setItem(PIN_KEY, JSON.stringify(pins.value));
}

/** 重命名（PATCH title；行内编辑 Enter/失焦保存，Esc 取消） */
const renamingId = ref<number | string>('');
const renameVal = ref('');
const renameRef = ref();
function startRename(c: AssistantConversation) {
  if (c.id === 'tmp') {
    return;
  }
  renamingId.value = c.id;
  renameVal.value = c.title ?? '';
  nextTick(() => (renameRef.value as HTMLInputElement[] | undefined)?.at(-1)?.focus());
}
async function saveRename(c: AssistantConversation) {
  if (renamingId.value !== c.id) {
    return;
  }
  const t = renameVal.value.trim();
  renamingId.value = '';
  if (!t || t === c.title) {
    return;
  }
  try {
    await renameConversation(c.id, t);
    c.title = t;
    ElMessage.success('已重命名');
  } catch {
    ElMessage.error('重命名失败，请稍后再试');
  }
}

async function archiveConv(c: AssistantConversation) {
  if (c.id === 'tmp') {
    return;
  }
  try {
    await ElMessageBox.confirm(`归档会话「${(c.title ?? '未命名').slice(0, 20)}」？归档后收进折叠条，可随时恢复。`, '归档确认', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await archiveConversation(c.id);
    c.status = 'ARCHIVED';
    if (selectedId.value === c.id) {
      selected.value = { ...c };
      // 单例归档闸同步置位（AU-36）：抽屉/会话页任一宿主发送都拦
      state.curConvArchived = true;
    }
    ElMessage.success('已归档');
  } catch {
    ElMessage.error('归档失败，请稍后再试');
  }
}

async function restoreConv(c: AssistantConversation) {
  try {
    await restoreConversation(c.id);
    c.status = 'ACTIVE';
    // 归档时 selected 存的是副本（AU-15）：恢复只改行对象副本仍 ARCHIVED——
    // 头部「已归档」tag 残留 + onSend 拦发送；同步指回行对象
    if (selectedId.value === c.id) {
      selected.value = c;
      state.curConvArchived = false;
    }
    ElMessage.success('已恢复');
  } catch {
    ElMessage.error('恢复失败，请稍后再试');
  }
}

/** 列表视图：置顶优先 → 今天/昨天/更早；归档独立折叠 */
const filtered = computed(() =>
  conversations.value.filter((c) => !kw.value.trim() || (c.title ?? '').toLowerCase().includes(kw.value.trim().toLowerCase()))
);
const archived = computed(() => filtered.value.filter((c) => c.status === 'ARCHIVED'));
const archivedExpanded = computed(() => (archOpen.value ? archived.value : []));
const grouped = computed(() => {
  const act = filtered.value.filter((c) => c.status !== 'ARCHIVED');
  const pin = act.filter((c) => pins.value.includes(String(c.id)));
  const rest = act.filter((c) => !pins.value.includes(String(c.id)));
  const g: Array<{ key: string; items: AssistantConversation[] }> = [];
  if (pin.length > 0) {
    g.push({ key: '置顶', items: pin });
  }
  const byDay = new Map<string, AssistantConversation[]>();
  rest.forEach((c) => {
    byDay.set(dayBucket(c.lastMessageTime), [...(byDay.get(dayBucket(c.lastMessageTime)) ?? []), c]);
  });
  ['今天', '昨天', '更早'].forEach((k) => {
    if (byDay.has(k)) {
      g.push({ key: k, items: byDay.get(k)! });
    }
  });
  return g;
});

/** R4 二改：更早组收折叠条（置顶/今天/昨天常驻），搜索或选中命中更早时自动展开 */
const groupedTop = computed(() => grouped.value.filter((g) => g.key !== '更早'));
const olderGroup = computed(() => grouped.value.find((g) => g.key === '更早') ?? null);
const olderOpen = ref(false);
watch(kw, (k) => {
  if (k.trim()) {
    olderOpen.value = true;
  }
});
watch(
  () => [selectedId.value, olderGroup.value] as const,
  ([sel, og]) => {
    if (og && og.items.some((c) => c.id === sel)) {
      olderOpen.value = true;
    }
  }
);
/** 批A 审查回修 P1-2：分组日界改北京自然日（cnDayDiff=now−item 天数），与 relTime 的
 *  「今天/昨天」判定同一套口径——修前浏览器本地零点与北京零点在时差窗内错开，会出现
 *  「行内显示昨天 22:00 却挂在今天分组」的自相矛盾 */
function dayBucket(t?: string): string {
  const diff = cnDayDiff(t);
  if (diff === null) {
    return '更早';
  }
  return diff <= 0 ? '今天' : diff === 1 ? '昨天' : '更早';
}
/** 批A Q12（拍板 2026-10-11）：与抽屉 fpDayTime 一并收敛到共用 relTime（修无时区串
 *  本地解析负差恒「刚刚」+ 补「X 分钟前」档），两宿主口径单一真源 */
function dayTime(t?: string): string {
  return relTime(t);
}
function fmtTime(t?: string): string {
  if (!t) {
    return '';
  }
  return String(t).replace('T', ' ').slice(0, 16);
}
/** 活动绿点：30 分钟内有消息（批A 审查回修 P1-1：共用 cnEpoch 北京墙钟解析，
 *  修非中国时区浏览器绿点误亮/永不亮） */
function isActive(c: AssistantConversation): boolean {
  if (!c.lastMessageTime) {
    return false;
  }
  const ms = cnEpoch(c.lastMessageTime);
  return ms !== null && Date.now() - ms < 30 * 60_000;
}

/** 批D Q11-A：侧栏行首状态槽（单槽互斥）。answering 只认生产相位
 *  （thinking/acting/writing）——done/error/waiting 时 sending 可能还没翻 false（收尾落账
 *  窗口），照转就是 lobehub masked-tail 教训的「内容停了圈还在转」；waiting=等用户确认卡不转。
 *  Q4（业务批4，dsflash P2 回修）：刷新后本地 sending/turns 已丢，吃后端瞬态 running
 *  （RUNNING 中的 run 存在即标"正在回答…"，与抽屉 watchRemoteRun 同源） */
function rowStatus(c: AssistantConversation): 'answering' | 'error' | 'ok' {
  if (state.sending && c.threadId === state.threadId) {
    const last = state.turns[state.turns.length - 1];
    if (last && (last.phase === 'thinking' || last.phase === 'acting' || last.phase === 'writing')) {
      return 'answering';
    }
  }
  if (c.running) {
    return 'answering';
  }
  if (c.threadId === state.threadId && state.turns[state.turns.length - 1]?.phase === 'error') {
    return 'error';
  }
  return 'ok';
}

/* ===== 右栏：单例 store 接管（与抽屉同一状态机；批A0 架构兑现） ===== */
const {
  state,
  cur,
  send,
  stop,
  resumeSay,
  resolveHitl,
  setAutoLevel,
  queueEdit,
  queueGo,
  queueDrop,
  queueResume,
  gateOk,
  rebuildFromHistory,
  recoverInterrupt,
  setThinkShow,
  newSession,
  loadTodo,
  branchFrom
} = getAssistant(userStore.userId);
// 单例多宿主绑定：会话页在场时草稿/toast/鉴权提示归本页（离开页面不清状态——单例池跨路由存续）
// H8：持有绑定引用——unbind 按身份弹，抽屉还开着时本页卸载不再误弹抽屉的绑定
const hostBinding: AssistantBindings = {
  onToast: (t: string) => pushToast(t),
  onAuthFailure: () => ElMessage.warning('登录已过期，请重新登录后再使用助手'),
  getDraft: () => composerRef.value?.draftText ?? '',
  clearDraft: () => composerRef.value?.clearDraft()
};
getAssistant(userStore.userId).bind(hostBinding);

const composerRef = ref<InstanceType<typeof Composer>>();
/** DP-3：统计面板跳转通道（step-jump/jump-confirm → MsgFlow expose） */
const msgFlowRef = ref<InstanceType<typeof MsgFlow>>();
/** Q2（20261011 拍板A）：todo 清单行跳转目标——清单是会话级而步骤分属各回合，
 *  跨回合取最近一次 todo_write 步骤 id；无（回放/未写过清单）=面板行不可点 */
const todoJumpId = computed(
  () =>
    state.turns
      .flatMap((t) => t.steps)
      .filter((s) => s.name === 'todo_write')
      .at(-1)?.id ?? null
);
const follow = ref(true);
/** 须知门（与 Drawer 同构）：派生态（AU-17）——单例 gatePendQ 非空且未同意即弹，
 * 双宿主不再各持本地 ref（旧路径：打开有消息会话静默置 gateDone，门卡滞留误补发） */
const showGate = computed(() => !state.gateDone && !!state.gatePendQ);
let flowEl: HTMLElement | null = null;
function onScrollLock(el: HTMLElement | null) {
  if (flowEl) {
    flowEl.removeEventListener('scroll', onScrollEvt);
  }
  flowEl = el;
  flowEl?.addEventListener('scroll', onScrollEvt, { passive: true });
}
function onScrollEvt() {
  if (flowEl) {
    follow.value = flowEl.scrollHeight - flowEl.scrollTop - flowEl.clientHeight < 48;
  }
}

function onSend(q: string, stamp = '', fwd: Record<string, unknown> = {}) {
  // 归档会话前端拦截（批H P1-2；后端 ensureConversation 同口径 403 双保险）；
  // curConvArchived=单例闸（AU-36：抽屉打开的归档会话切到本页也拦）
  if (state.curConvArchived || selected.value?.status === 'ARCHIVED') {
    ElMessage.warning('该会话已归档（只读回放），请先在左侧「已归档」里恢复再发消息');
    return;
  }
  follow.value = true;
  if (!state.gateDone) {
    state.gatePendQ = q; // 须知门派生态：gatePendQ 非空即弹（AU-17）
    return;
  }
  send(q, stamp, fwd);
}
function onGateOk() {
  gateOk();
}
function onStop() {
  stop(true);
}
function onResume() {
  follow.value = true;
  resumeSay();
}
function onQueueEdit(i: number) {
  // BN-4 原型口径：撤回的条目回填前，先护住用户正在打的草稿（queueEdit 内入队+toast）
  const t = queueEdit(i, composerRef.value?.draftText ?? '');
  if (t) {
    composerRef.value?.setDraft(t);
    composerRef.value?.focus();
  }
}
function onQueueGo(i: number) {
  queueGo(i);
}
function onQueueDrop(i: number) {
  queueDrop(i);
}
function onQueueResume() {
  queueResume();
}
function onResend(text: string) {
  composerRef.value?.setDraft(text);
}
/** BN-6 原型 stepsTry：新手「帮我填一句」=回填+聚焦+toast（不直接发） */
function onFillDraft(text: string) {
  composerRef.value?.setDraft(text);
  composerRef.value?.focus();
  pushToast('已帮你填好——点发送或按回车', 'ok');
}
function onBranch(turn: AssistantTurn) {
  if (state.sending) {
    pushToast('等这轮回答完再分支', 'ok');
    return;
  }
  branchFrom(turn);
  composerRef.value?.focus();
}
function onPageGoto(p: { missionId: string; missionName: string }) {
  pushToast(`已为你打开任务「${p.missionName.slice(0, 14)}」`);
  router.push(`/dcs/mission/detail/${p.missionId}`);
}
function onDraftSet(text: string, append: boolean) {
  const curText = append ? (composerRef.value?.draftText ?? '') : '';
  composerRef.value?.setDraft(curText ? curText + '\n' + text : text);
  composerRef.value?.focus();
}
function onPlanCancel() {
  const t = cur();
  if (t) {
    t.plan = null;
  }
}

function onNewSession() {
  if (state.sending) {
    stop(true);
  }
  newSession();
  selected.value = null;
  selectedId.value = '';
  actions.value = [];
  // 业务批(1015) Q15：新会话即时置顶（答后补刷会换成真实行+真实标题）
  conversations.value = ensureCurRow(conversations.value);
}

/** 列表分页（AU-19）：后端 clamp pageSize=100，此前只拉 50 条弃 total——
 *  第 51 条起的旧会话 UI 永不可达；尾部「加载更多」按 total 追加 */
const convPageNum = ref(1);
const convTotal = ref(0);
/** 业务批(1015) Q15：当前会话行保底——服务端首轮 run 才落库，"新会话还没发第一句"
 *  时列表里没有它（须刷新才见的根因）；合成行按 threadId 去重，真实行到达即接管。
 *  自查回修(dspro/m3 P2)三处：①lastMessageTime 给当前北京墙钟——空串会被分组归进默认
 *  收起的「更早」组，即时置顶落空；②行内操作(打开/重命名/置顶/归档)对临时行全拦
 *  （假 ID 打真 API 只会弹"操作失败"假错误 + 僵尸置顶）；③计数不计临时行 */
function ensureCurRow(list: AssistantConversation[]): AssistantConversation[] {
  if (!state.threadId || list.some((c) => c.threadId === state.threadId)) {
    return list;
  }
  const nowWall = new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 19).replace('T', ' ');
  return [{ id: 'tmp', threadId: state.threadId, title: '新会话', lastMessageTime: nowWall, status: 'ACTIVE', running: state.sending }, ...list];
}
/** 真实会话数（不含临时行）：加载更多计数/可见性用它，防 +1 挤掉第 51 条 */
const realCount = computed(() => conversations.value.filter((c) => c.id !== 'tmp').length);
/** silent=true 供答后补刷（3.5/5.5/9s）用：不闪 loading 转圈，静默换数据 */
async function loadConversations(silent = false) {
  if (!silent) {
    loading.value = true;
  }
  try {
    // status=ALL（批H P1-1）：活跃+归档一起拉回由前端分组——后端默认只回 ACTIVE 会让
    // 归档折叠条刷新后恒空、恢复入口永不可达
    const res = (await listConversations({ pageNum: 1, pageSize: 50, status: 'ALL' })) as unknown as {
      rows?: AssistantConversation[];
      total?: number;
    };
    const head = ensureCurRow(res?.rows ?? []);
    if (silent) {
      // 自查回修(dspro P2)：静默补刷只换首页，已「加载更多」的尾段保留——此前整表替换把
      // 列表压回 50 条，刚翻到第 2 页的用户被无声打断；同 id 以新数据为准，临时行一律丢弃
      const headIds = new Set(head.map((c) => c.id));
      conversations.value = [...head, ...conversations.value.filter((c) => !headIds.has(c.id) && c.id !== 'tmp')];
      if (res?.total != null) {
        convTotal.value = res.total;
      }
    } else {
      conversations.value = head;
      convTotal.value = res?.total ?? head.length;
      convPageNum.value = 1;
    }
  } catch {
    /* 失败保持现有列表（request 层已 toast），勿变 unhandled rejection */
  } finally {
    if (!silent) {
      loading.value = false;
    }
  }
}
async function loadMoreConversations() {
  convPageNum.value += 1;
  try {
    const res = (await listConversations({ pageNum: convPageNum.value, pageSize: 50, status: 'ALL' })) as unknown as {
      rows?: AssistantConversation[];
      total?: number;
    };
    const incoming = res?.rows ?? [];
    // 按 id 合并追加（同 id 行以新数据为准；选中行引用更换由 openConversation 重新点击对齐）
    // 自查回修(glm53 P2)：真实行到达时先剥掉同 threadId 的临时行，防同一会话双行
    const realThreads = new Set(incoming.map((c) => c.threadId));
    const base = conversations.value.filter((c) => !(c.id === 'tmp' && realThreads.has(c.threadId)));
    const map = new Map(base.map((c) => [c.id, c]));
    for (const c of incoming) {
      map.set(c.id, c);
    }
    conversations.value = [...map.values()];
    if (res?.total != null) {
      convTotal.value = res.total;
    }
  } catch {
    convPageNum.value -= 1;
  }
}

/** 打开会话：DB messages 重放进单例（rebuildFromHistory 同步 threadId+清队列，P1-4/P1-3）+动作账本 */
let openSeq = 0;
async function openConversation(c: AssistantConversation) {
  // 自查回修(dspro P2)：临时行=当前正在用的会话本身，点它只对齐选中态即可——
  // 走下方真实加载会拿 'tmp' 打 messages 接口报"会话加载失败"，还会先白停一次在途生成
  if (c.id === 'tmp') {
    selected.value = c;
    selectedId.value = c.id;
    return;
  }
  // 业务批10 #2 P1：点开"正在生成的当前会话"不掐流——stop+重放会把消息区替换成 DB 快照，
  // 正在生成的部分丢失、尾部转圈直到收割；同 threadId 在飞时只对齐选中态，本地流继续播
  if (state.sending && state.threadId === c.threadId) {
    selected.value = c;
    selectedId.value = c.id;
    return;
  }
  if (state.sending) {
    stop(true);
  }
  // 连点序号守卫（批H P2-1）：两个并发 Promise.all 后完成者不得覆盖先选中者
  const mySeq = ++openSeq;
  selected.value = c;
  selectedId.value = c.id;
  // 单例归档闸（AU-36）：rebuildFromHistory 开头 reset 会清位，rebuild 之后再置
  const archived = c.status === 'ARCHIVED';
  try {
    // request 层返回整个 body（{code,msg,data}，与 TurnBlock resolveMessageId 同款取法）
    const [msgs, acts] = (await Promise.all([listMessages(c.id, { limit: 100 }), listActions({ threadId: c.threadId })])) as unknown as [
      { data?: Array<{ role: string; content: string; totalTokens?: number; endReason?: string | null }> },
      { data?: AssistantAction[] }
    ];
    if (openSeq !== mySeq) {
      return;
    }
    const list = msgs?.data ?? [];
    if (list.length > 0) {
      rebuildFromHistory(list, c.threadId);
    } else {
      // 空会话保 threadId（AU-16）：旧路径 newSession() 换新 uuid——左侧行高亮
      // 仍挂旧行、消息却落新 thread，旧行永远空着（身份漂移）
      rebuildFromHistory([], c.threadId);
    }
    state.curConvArchived = archived;
    actions.value = acts?.data ?? [];
    // ZCode 对齐 Q2（批B）：恢复该会话的任务清单（StatPanel TODO 分区）
    loadTodo(c.threadId);
    // 批6-#2：会话页在途轮恢复——run 仍 RUNNING（刷新/断连拆链，后端失联收割
    // ≤~45s 终局化）时挂 3s 轮询，终局后重放 openConversation 接上终态与中断徽标
    if (c.running) {
      watchPageRemote(c);
    }
  } catch {
    if (openSeq === mySeq) {
      ElMessage.error('会话加载失败，请稍后再试');
    }
  }
}

/** 批17 Q2：手动压缩完成后重拉当前会话消息——「已压缩」分隔条随历史重建出现。
 *  排队/生成中跳过（rebuild 会清队掐流），临时行无 DB 消息可拉。 */
function onCompacted() {
  if (state.queue.length > 0 || state.sending || !selected.value || selected.value.id === 'tmp') {
    return;
  }
  void openConversation(selected.value);
}

/** 批6-#2：会话页在途轮轮询（抽屉 watchRemoteRun 同源口径的本地瘦身版）。
 *  守卫：openSeq（连点换会话旧 tick 自灭）+ selectedId 仍在本会话 + 本地非在飞
 *  （用户刚发新问题时 rebuild 会擦在飞回合，远端结果下次打开可见）。 */
const pageWatchers = new Map<string, ReturnType<typeof setInterval>>();
function watchPageRemote(c: AssistantConversation) {
  const old = pageWatchers.get(c.threadId);
  if (old) {
    globalThis.clearInterval(old);
    pageWatchers.delete(c.threadId);
    releaseRemoteWatch(c.threadId);
  }
  // 批6-#2 回修（m3 P0-2）：抽屉（layout 常驻）已盯同 threadId 时不再挂第二份；
  // 对方收敛重建的是共享 turns，本页响应式自动跟随
  if (!tryAcquireRemoteWatch(c.threadId)) {
    return;
  }
  const placeholder = state.turns[state.turns.length - 1] ?? null;
  if (placeholder && placeholder.phase === 'done' && !placeholder.bufText) {
    placeholder.phase = 'thinking';
  }
  const mySeq = openSeq;
  let ticks = 0;
  const timer = globalThis.setInterval(async () => {
    ticks += 1;
    if (ticks > 60 || openSeq !== mySeq || selectedId.value !== c.id) {
      globalThis.clearInterval(timer);
      pageWatchers.delete(c.threadId);
      releaseRemoteWatch(c.threadId);
      // 业务批10 #2 P2：超限即熄行级转圈——run 卡 RUNNING（零事件盲区走 15min 兜底）时
      // 列表"正在回答…"会永转，本地先行收敛为非转圈态
      if (ticks > 60) {
        const mine = conversations.value.find((x) => x.threadId === c.threadId);
        if (mine) {
          mine.running = false;
        }
      }
      if (
        ticks > 60 &&
        openSeq === mySeq &&
        selectedId.value === c.id &&
        placeholder &&
        state.turns[state.turns.length - 1] === placeholder &&
        placeholder.phase === 'thinking'
      ) {
        placeholder.phase = 'done';
        ElMessage.info('生成时间过长，可能已中断；稍后重新打开该会话即可看到已生成的结果');
      }
      return;
    }
    try {
      const convs = (await listConversations({ pageNum: 1, pageSize: 50, status: 'ALL' })) as unknown as {
        rows?: AssistantConversation[];
      };
      const row = (convs?.rows ?? []).find((x) => x.threadId === c.threadId);
      if (row) {
        const mine = conversations.value.find((x) => x.threadId === c.threadId);
        if (mine) {
          mine.running = row.running;
        }
      }
      if (!row?.running) {
        globalThis.clearInterval(timer);
        pageWatchers.delete(c.threadId);
        releaseRemoteWatch(c.threadId);
        // 传新鲜行（running=false）防重放后再挂轮询；本地在飞时不重建（同抽屉 m3 P1-5）
        if (openSeq === mySeq && selectedId.value === c.id && !state.sending) {
          await openConversation(row ?? c);
        }
      }
    } catch {
      /* 网络抖动下轮再试；60 次上限兜底 */
    }
  }, 3000);
  pageWatchers.set(c.threadId, timer);
}

onMounted(loadConversations);

/** 业务批(1015) Q15：答完递进补刷列表（3.5/5.5/9s，与追问侧车同款时序）——标题侧车
 *  几秒后才改写短标题，单次刷新常读旧值；静默模式不闪 loading；卸载回收防登出后 401 幽灵 */
let convRefreshTimers: ReturnType<typeof setTimeout>[] = [];
watch(
  () => state.sending,
  (v, old) => {
    if (old && !v) {
      convRefreshTimers.forEach((t) => clearTimeout(t));
      convRefreshTimers = [3500, 5500, 9000].map((ms) => setTimeout(() => void loadConversations(true), ms));
    }
  }
);
onBeforeUnmount(() => convRefreshTimers.forEach((t) => clearTimeout(t)));

/** BN-10 原型 Esc 分层（会话页口径）：输入区弹层（AU-29）→ 生成中 Esc=停止（无面板可收；输入框内的 Esc 由组件自身处理） */
function onPageEsc(e: KeyboardEvent) {
  if (e.key !== 'Escape') {
    return;
  }
  if (composerRef.value?.escClosePop()) {
    return;
  }
  if (state.sending) {
    stop();
  }
}
onMounted(() => document.addEventListener('keydown', onPageEsc));
onBeforeUnmount(() => document.removeEventListener('keydown', onPageEsc));
// 路由离开弹栈归还绑定（批H P1-5）：抽屉 layout 常驻不重挂，不清会永久占用失效的 composerRef
onUnmounted(() => {
  getAssistant(userStore.userId).unbind(hostBinding);
  // 批6-#2：会话页轮询器随路由离开回收（同抽屉 dspro P2 泄漏口径）
  pageWatchers.forEach((t, tid) => {
    globalThis.clearInterval(t);
    releaseRemoteWatch(tid);
  });
  pageWatchers.clear();
});
</script>

<style lang="scss" scoped>
.dcs-as-session {
  display: flex;
  gap: 12px;
  height: calc(100vh - 120px);
  padding: 12px;

  .side {
    width: 300px;
    flex: none;
    display: flex;
    flex-direction: column;
    border: 1px solid var(--dcs-border-color-lighter);
    border-radius: 8px;
    background: var(--el-bg-color);
    overflow: hidden;

    .side-hd {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 12px 6px;

      .side-t {
        font-weight: 600;
        color: var(--dcs-text-primary);
      }
    }
    .side-search {
      margin: 0 10px 8px;
      width: calc(100% - 20px);
    }
    .side-body {
      flex: 1;
      overflow-y: auto;
      padding: 0 8px 10px;
    }
  }
  /* R4 二改：分组头=小字+计数徽标（孤零零灰字升级，计数一眼读出每组几条） */
  .grp {
    display: flex;
    align-items: center;
    gap: 5px;
    padding: 10px 6px 4px;
    font-size: 11.5px;
    color: var(--dcs-text-placeholder);
    .g-badge {
      font-size: 10px;
      line-height: 1;
      padding: 2px 6px;
      border-radius: 8px;
      background: var(--el-fill-color);
      color: var(--dcs-text-secondary);
    }
  }
  /* 更早折叠条（与已归档同款交互骨架，视觉稍重提示可点开长列表）；chevron 右推行尾（视觉席统一批注） */
  .grp-fold {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 6px;
    padding: 7px 6px;
    border-top: 1px dashed var(--dcs-border-color-lighter);
    font-size: 12px;
    color: var(--dcs-text-secondary);
    cursor: pointer;
    user-select: none;

    &:hover {
      color: var(--el-color-primary);
      background: var(--el-fill-color-light);
    }
    > .iswap:last-child {
      margin-left: auto;
    }
  }
  .conv-item {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 8px 8px 10px;
    border-radius: 8px;
    cursor: pointer;
    position: relative;

    &:hover {
      background: var(--el-fill-color-light);

      .c-ops {
        visibility: visible;
      }
    }
    &.on {
      background: var(--el-color-primary-light-9);
    }
    &.pinned::before {
      content: '';
      position: absolute;
      left: 0;
      top: 8px;
      bottom: 8px;
      width: 3px;
      border-radius: 2px;
      background: var(--el-color-primary);
    }
    &.archived {
      opacity: 0.75;
    }
    .sdot {
      /* Q10 圆点统一：与抽屉 sdot/统计面板 spdot 同 7px（视觉三轮提过不一致） */
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: var(--dcs-color-success);
      flex: none;
    }
    /* 批D Q11-A：出错红点（最近一轮 error） */
    .sdot-err {
      background: var(--dcs-color-danger);
    }
    /* 批D Q11-A：回答中旋转标——EP loading 同款顺时针，主色弧线 */
    .sspin {
      flex: none;
      display: inline-flex;
      align-items: center;
      color: var(--el-color-primary);
      animation: dcs-sspin 0.9s linear infinite;
    }
    @keyframes dcs-sspin {
      to {
        transform: rotate(360deg);
      }
    }
    .c-main {
      flex: 1;
      min-width: 0;
    }
    .c-title {
      font-size: 13.5px;
      color: var(--dcs-text-primary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .c-rename {
      width: 100%;
      font: inherit;
      font-size: 13px;
      border: 1px solid var(--el-color-primary);
      border-radius: 4px;
      padding: 1px 4px;
      color: var(--dcs-text-primary);
      outline: none;
    }
    .c-meta {
      margin-top: 2px;
      font-size: 12px;
      color: var(--dcs-text-secondary);
    }
    .c-ops {
      visibility: hidden;
      display: flex;
      gap: 2px;
      flex: none;

      button {
        border: none;
        background: none;
        cursor: pointer;
        font-size: 12px;
        padding: 2px;
        border-radius: 4px;

        &:hover {
          background: var(--el-fill-color);
        }
      }
    }
  }
  .arch-fold {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 6px;
    /* R4 二改视觉统一：与「更早」折叠条同为虚线分隔 + chevron 右推（视觉席批注） */
    border-top: 1px dashed var(--dcs-border-color-lighter);
    font-size: 12.5px;
    color: var(--dcs-text-secondary);
    padding: 8px 6px;
    cursor: pointer;
    user-select: none;

    &:hover {
      color: var(--el-color-primary);
    }
    > .iswap:last-child {
      margin-left: auto;
    }
  }
  .load-more {
    padding: 8px 0 4px;
    text-align: center;
    font-size: 12.5px;
    color: var(--el-color-primary);
    cursor: pointer;
    user-select: none;

    &:hover {
      text-decoration: underline;
    }
  }

  .main {
    flex: 1;
    min-width: 0;
    position: relative;
    display: flex;
    flex-direction: column;
    border: 1px solid var(--dcs-border-color-lighter);
    border-radius: 8px;
    background: var(--el-bg-color);
    overflow: hidden;
    /* Q8/Q9/三反馈② 廊位：StatPanel 按容器宽解算（fills P+4 / capped 2P+956-W / 0 贴列）写
       --sp-strip，本层消费——胶囊与面板始终贴内容列右缘，宽屏不再漂在空档；
       StatPanel 为 absolute 浮层不吃本层 padding，正文列与聊天框一起让、一起回 */
    padding-right: var(--sp-strip, 0px);
    transition: padding-right 0.3s ease;

    .main-hd {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 10px 14px;
      border-bottom: 1px solid var(--dcs-border-color-lighter);
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
    :deep(.dcs-flow) {
      flex: 1;
    }
    /* A5 原型：欢迎卡限宽 640。宽屏加宽批 P2 回修（视觉席像素级实锤）：帽+auto 原落在
       .dcs-hero 容器层——flex 里 auto margin 压过 >* 规则的 flex-start，卡片对着整个
       主区居中，与内容列中轴错位 126px；帽下移到卡片层，容器吃列几何（.dcs-flow.full > *
       同列帽/贴左），卡片在列内居中=与输入框同中轴 */
    :deep(.dcs-hero .hero-card) {
      width: 100%;
      max-width: 640px;
      margin-inline: auto;
    }
    .act-ledger {
      border-top: 1px solid var(--dcs-border-color-lighter);
      margin: 0;

      :deep(.el-collapse-item__header) {
        padding: 0 14px;
        font-size: 13px;
      }
      :deep(.el-collapse-item__content) {
        padding: 0 14px 10px;
      }
    }
  }
}
</style>
