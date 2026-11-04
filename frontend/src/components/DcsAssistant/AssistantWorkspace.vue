<template>
  <div class="dcs-as-session" :class="{ 'ws-in-agent': variant === 'agent' }">
    <!-- 左：会话列表（搜索/新会话/置顶/时间分组/归档折叠）；≤992 变历史会话抽屉 -->
    <div v-if="sideOpen" class="side-mask" @click="sideOpen = false"></div>
    <aside
      class="side"
      :class="{ 'mobile-open': sideOpen }"
      :aria-hidden="isMobile && !sideOpen ? 'true' : undefined"
      :inert="(isMobile && !sideOpen) || undefined"
    >
      <!-- 批3.6：agent 壳左栏 = 新任务 + 导航 + 搜索（四项待建，置灰不可点）；会话页走下面旧分支，一行不变 -->
      <template v-if="variant === 'agent'">
        <div class="ag-side-hd">
          <button class="ag-new" type="button" @click="onNewSession"><IconSpan name="plus" :size="14" /> 新任务</button>
          <button class="side-close" type="button" aria-label="关闭会话列表" @click="sideOpen = false"><IconSpan name="x" /></button>
        </div>
        <nav class="ag-nav" aria-label="工作台导航">
          <!-- 「会话」是当前位置标记，不是入口：它原来挂了 onNewSession，正在聊的时候点一下
               就会清空当前会话（且 selected 不复位，标题栏还挂着旧标题）。做成不可点的当前项。 -->
          <span class="on" aria-current="page"><IconSpan name="message" :size="15" /> 会话</span>
          <!-- 四项待建：用 disabled 按钮而不是 aria-disabled 的 span——读屏浏览模式会念出
               「技能, 按钮, 不可用」，span 上的 aria-disabled 则会被当普通文字略过 -->
          <button type="button" class="off" disabled title="技能管理 · 待建设"><IconSpan name="sparkles" :size="15" /> 技能</button>
          <button type="button" class="off" disabled title="工具管理 · 待建设"><IconSpan name="wrench" :size="15" /> 工具</button>
          <button type="button" class="off" disabled title="自动化 · 待建设"><IconSpan name="timer" :size="15" /> 自动化</button>
          <button type="button" class="off" disabled title="审计 · 待建设"><IconSpan name="clipboard" :size="15" /> 审计</button>
        </nav>
        <el-input v-model="kw" class="ag-side-search" placeholder="搜索会话标题" aria-label="搜索会话标题" clearable :prefix-icon="SearchIcon" />
      </template>

      <template v-else>
        <div class="side-hd">
          <span class="side-t">我的助手会话</span>
          <div class="side-ops">
            <el-button link type="primary" :loading="loading" title="刷新" @click="loadConversations()"><IconSpan name="refresh" /></el-button>
            <el-button link type="primary" title="新会话" @click="onNewSession"><IconSpan name="plus" /> 新会话</el-button>
          </div>
          <button class="side-close" type="button" aria-label="关闭会话列表" @click="sideOpen = false"><IconSpan name="x" /></button>
        </div>
        <el-input v-model="kw" class="side-search" placeholder="搜索会话标题" clearable :prefix-icon="SearchIcon" />
      </template>

      <div class="side-body">
        <el-empty
          v-if="!loading && grouped.length === 0 && archived.length === 0"
          :description="variant === 'agent' ? '还没有会话——点上面「新任务」开始' : '还没有会话——点「新会话」开始'"
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
                <template v-else>{{ displayTitle(c) }}</template>
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
              <div class="c-title">{{ displayTitle(c) }}</div>
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
              <div class="c-title">{{ displayTitle(c) }}</div>
              <div class="c-meta">{{ dayTime(c.lastMessageTime) }}</div>
            </div>
            <span class="c-ops"><button title="恢复会话" @click.stop="restoreConv(c)">↩</button></span>
          </div>
        </template>

        <!-- AU-19：超 50 条的旧会话靠这里继续翻页（后端单页 clamp 100） -->
        <div
          v-if="realCount < convTotal"
          class="load-more"
          role="button"
          tabindex="0"
          @click="loadMoreConversations"
          @keydown.enter="loadMoreConversations"
        >
          加载更多（已显示 {{ realCount }}/{{ convTotal }}）
        </div>
      </div>

      <!-- 批3.6：agent 壳左栏底部=当前用户（首字圆标 + 昵称 + 身份）；会话页不加这一块 -->
      <div v-if="variant === 'agent'" class="ag-user">
        <span class="ag-uav" aria-hidden="true">{{ userInitial }}</span>
        <div class="ag-umeta">
          <b>{{ userStore.nickname || userStore.name || '当前用户' }}</b>
          <span>{{ userSub }}</span>
        </div>
      </div>
    </aside>

    <!-- 右：对话区（单例 store 接管——与抽屉/全屏同一状态机，可继续对话） -->
    <!-- Q8/Q9/三反馈②：廊位由 StatPanel 解算写 --sp-strip 变量（面板贴内容列右缘），
         本层只消费变量——消息列与输入框同让同回，浮层面板不吃 padding -->
    <section class="main" :class="{ 'ag-empty': agEmpty }">
      <!-- 批3.5·题1/题5：agent 壳进门内容（hero + 能做什么）——空态渲染在会话区顶部，会话开始即让位。
           条件与 agEmpty 同一份（原来内联写了一遍，两处改一处忘一处就会「标题在、技能条不在」） -->
      <slot v-if="agEmpty" name="intro" />
      <div class="main-hd">
        <button class="side-toggle" type="button" @click="sideOpen = true"><IconSpan name="history" /> 历史会话</button>
        <span>{{ selected ? displayTitle(selected) : '新会话' }}</span>
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
        :hide-welcome="agEmpty"
        @scroll-lock="onScrollLock"
        @resolve-hitl="resolveHitl"
        @pick="(p) => onSend(p, '')"
        @resend="onResend"
        @resend-run="(q) => onSend(q, '')"
        @resume="onResume"
        @recover-interrupt="recoverInterrupt"
        @fill="onFillDraft"
        @new-session="onNewSession"
        @tour="startTour"
        @open-help="help.show()"
        @branch="onBranch"
        @ask="(t) => onSend(t, '')"
        @pgoto="onPageGoto"
        @mdnav="(u) => router.push(u)"
        @draft-set="onDraftSet"
        @elicit-done="removeElicitationCard"
        @ask-user-done="removeAskUserCard"
      />

      <!-- 计划待确认：输入框有字时只出细条，不抢输入（Q6-A）；点开后才占输入框位置 -->
      <plan-tip v-if="showPlanTip && activePlan" :plan="activePlan" :busy="state.sending" full @open="openPlanDock" />
      <plan-dock
        v-if="showPlanDock && activePlan"
        :plan="activePlan"
        full
        :can-collapse="draftText.trim().length > 0"
        @confirm="onPlanConfirm"
        @adjust="onPlanAdjust"
        @cancel="onPlanCancel"
        @collapse="collapsePlanDock"
      />

      <!-- HITL 待确认停靠区——占输入框位置，输入框由 blocked 收起（草稿保留） -->
      <hitl-dock :ask="pendingAsk" full @resolve="resolveHitl" @revise="onDockRevise" />

      <composer
        ref="composerRef"
        full
        active
        :state="state"
        :sending="state.sending"
        :blocked="!!pendingAsk || showPlanDock"
        @draft-change="draftText = $event"
        @send="onSend"
        @stop="onStop"
        @queue-edit="onQueueEdit"
        @queue-go="onQueueGo"
        @queue-drop="onQueueDrop"
        @queue-resume="onQueueResume"
        @auto-level="setAutoLevel"
        @think-show="setThinkShow"
        @open-help="help.show()"
        @new-session="onNewSession"
        @compacted="onCompacted"
      />

      <!-- 批3.6：agent 空态第二段进门内容（技能条）——必须落在输入框之后，
           与原型 D3 同序（标题 → 输入框 → 技能卡）；会话页不传该槽位，什么都不渲染 -->
      <slot v-if="agEmpty" name="intro-foot" />

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
    <el-dialog :model-value="help.open.value" :title="`${assistantName} 使用说明`" width="480px" append-to-body @update:model-value="help.hide()">
      <div class="dcs-help-body">
        <h4>三步上手</h4>
        <ol class="help-steps">
          <li>选择左侧会话，或点「{{ variant === 'agent' ? '新任务' : '新会话' }}」开始提问</li>
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
 * 会话工作区公共积木（批3.5·题2 ★案甲，2026-11-03）：由「AI 助手独立会话页（批E→批D）」整页搬来，
 * 会话页(/ai/assistant)与智能体工作台(/agent)两个壳共用——改这里，两边同时生效，不存在第二套会话代码。
 * 搬运时只做三处手术：/agent 跨页交接退役（工作台改为本页对话）、新增 variant 开关 + intro 槽位、
 * defineExpose 遥控口。其余行为与批D 会话页逐项一致（老功能零倒退）。原视角说明：
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
import { getAssistant, ACT, logSt, tryAcquireRemoteWatch, releaseRemoteWatch, PLAN_CONFIRM_TEXT } from '@/components/DcsAssistant/useAssistant';
import type { AssistantBindings } from '@/components/DcsAssistant/useAssistant';
import { useHelpModal } from '@/components/DcsAssistant/helpModal';
import { relTime, cnEpoch, cnDayDiff } from '@/components/DcsAssistant/time';
import { displayTitle, renameSeed, shouldRename } from '@/utils/assistantTitle';
import { pushToast } from '@/components/DcsAssistant/toastStore';
import { assistantName } from '@/components/DcsAssistant/nameState';
import MsgFlow from '@/components/DcsAssistant/MsgFlow.vue';
import HitlDock from '@/components/DcsAssistant/HitlDock.vue';
import PlanDock from '@/components/DcsAssistant/PlanDock.vue';
import PlanTip from '@/components/DcsAssistant/PlanTip.vue';
import { latestPendingPlan, shouldShowPlanDock } from '@/components/DcsAssistant/planDock';
import { startTour } from '@/components/DcsAssistant/useTour';
import IconSpan from '@/components/DcsAssistant/IconSpan.vue';
import Composer from '@/components/DcsAssistant/Composer.vue';
import GateCard from '@/components/DcsAssistant/GateCard.vue';
import StatPanel from '@/components/DcsAssistant/StatPanel.vue';
import type { AssistantTurn } from '@/components/DcsAssistant/useAssistant';

/** 批3.5·题2（★案甲：整页公共积木）：宿主只有两个——会话页(默认)与 /agent 工作台。
 *  差异面刻意收到最小：variant 只控制 intro 槽位与少量样式钩子；能力零裁剪。 */
const props = withDefaults(defineProps<{ variant?: 'session' | 'agent' }>(), { variant: 'session' as const });

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
// ≤992 左侧会话栏改成「历史会话」抽屉
const sideOpen = ref(false);
/** 壳层 992 断点判定（与 layout 的 width-1<992 同口径）：手机上抽屉关闭时从 Tab 序移除 */
const mqMobile = window.matchMedia('(max-width: 992px)');
const isMobile = ref(mqMobile.matches);
const syncMobile = (e: MediaQueryListEvent) => {
  isMobile.value = e.matches;
};

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
  // 2026-11-01 题8：脏标题回填空串（与抽屉同一把尺子），别把 JSON 残片塞进输入框
  renameVal.value = renameSeed(c);
  nextTick(() => (renameRef.value as HTMLInputElement[] | undefined)?.at(-1)?.focus());
}
async function saveRename(c: AssistantConversation) {
  if (renamingId.value !== c.id) {
    return;
  }
  const t = renameVal.value.trim();
  renamingId.value = '';
  // 回填写的是 renameSeed（脏标题空串），提交判据必须同源，否则原样回车会白发一次请求
  if (!shouldRename(t, c)) {
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
    await ElMessageBox.confirm(`归档会话「${displayTitle(c).slice(0, 20)}」？归档后收进折叠条，可随时恢复。`, '归档确认', { type: 'warning' });
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

/** 列表视图：置顶优先 → 今天/昨天/更早；归档独立折叠
 *  2026-11-01：搜索同时认原始标题与兜底显示名——脏标题在列表里显示「（未命名会话）」，只搜原文就搜不到。 */
const filtered = computed(() =>
  conversations.value.filter((c) => {
    const q = kw.value.trim().toLowerCase();
    if (!q) return true;
    return (c.title ?? '').toLowerCase().includes(q) || displayTitle(c).toLowerCase().includes(q);
  })
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
  resumeStreamingPlaceholder,
  removeElicitationCard,
  removeAskUserCard,
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

/** 批3.6：agent 工作台空态（与 intro 槽位同一判据，另加 variant 闸门）。
 *  命中时：会话区让位、标题条收起、进门内容 + 输入框作为一组居中——
 *  旧版这里是被两块欢迎卡叠着挤到只剩 42% 的那条 243px 缝。 */
/** 批3.6：agent 工作台空态（进门内容 + 会话区让位的唯一判据）。
 *  `queue` 也要空：排队中的话下面已经在报「排队 N 条」，不能还算空态；
 *  `sending` 为 true 时同理。会话页 variant=session，这里恒 false。 */
const agEmpty = computed(
  () => props.variant === 'agent' && !selected.value && state.turns.length === 0 && !state.sending && state.queue.length === 0
);
/** 左栏底部当前用户：首字圆标（与聊天气泡同口径）+ 身份。
 *  角色只从真实角色码翻译（superadmin→管理员，其余→成员）；昵称本身就叫「管理员」时不再重复，
 *  退回账号名。这里刻意不取租户名——/agent 是独立分区，不经业务壳，tenantName 不会加载，
 *  副行会随进入路径变化；账号名是确定值。 */
const userInitial = computed(() => (userStore.nickname || userStore.name || '我').trim().charAt(0) || '我');
const userSub = computed(() => {
  const label = userStore.roles.includes('superadmin') ? '管理员' : '成员';
  return label === (userStore.nickname || '').trim() ? userStore.name || '' : label;
});
/** 停靠区数据源（与抽屉同口径）：最近一张 pending 卡 */
const pendingTurn = computed(() => [...state.turns].reverse().find((t) => t.hitl?.cardState === 'pending') ?? null);
const pendingAsk = computed(() => pendingTurn.value?.hitl ?? null);
/** 最新一张待确认计划（Q5-A：旧卡只读，只有最新一张可操作） */
const pendingPlan = computed(() => latestPendingPlan(state.turns));
const activePlan = computed(() => pendingPlan.value?.plan ?? null);
/** Composer 草稿（Q6-A：有字时只出细条，不抢输入框） */
const draftText = ref('');
const planDockForced = ref(false);
const showPlanDock = computed(() =>
  shouldShowPlanDock({
    hasPlan: !!activePlan.value,
    hasPendingAsk: !!pendingAsk.value,
    sending: state.sending,
    forced: planDockForced.value,
    draft: draftText.value
  })
);
const showPlanTip = computed(() => !!activePlan.value && !pendingAsk.value && !showPlanDock.value);
watch(
  () => activePlan.value?.planId,
  () => {
    planDockForced.value = false;
  }
);
watch(showPlanDock, (v, old) => {
  if (old && !v) {
    nextTick(() => composerRef.value?.focus());
  }
});
function openPlanDock() {
  planDockForced.value = true;
}
function collapsePlanDock() {
  planDockForced.value = false;
  nextTick(() => composerRef.value?.focus());
}
/** 停靠区「改一下」：先取消本次确认（cancelled resume 清服务端悬挂），
 *  用户要求进排队，取消轮收尾后自动发出 */
function onDockRevise(m: string) {
  const text = m.trim();
  if (!text) {
    return;
  }
  resolveHitl(false);
  onSend(text, '');
}
// 停靠区收卡后焦点还给输入框（与抽屉同口径）；计划停靠区接手时由 PlanDock 自己聚焦
watch(pendingAsk, (v, old) => {
  if (old && !v && !showPlanDock.value) {
    nextTick(() => composerRef.value?.focus());
  }
});
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
  // 交接提示词被须知门挂起时，输入框里还留着同一句；放行后 gateOk 会把 pending 发出去，
  // 草稿必须同步清掉，否则用户再按回车就是第二条（三席审查 P1-2 的门控分支）
  const pending = state.gatePendQ;
  gateOk();
  if (pending && composerRef.value?.draftText === pending) composerRef.value?.clearDraft();
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
/** 计划动作的前置闸：归档/禁用时 onSend 会整段早退，不能先把卡翻成“已确认/已修改” */
function planBlockedMsg(): string {
  if (state.curConvArchived || selected.value?.status === 'ARCHIVED') {
    return '该会话已归档（只读回放），请先恢复再执行计划';
  }
  if (state.banner === 'disabled') {
    return '管理员已暂停你的 AI 助手使用，这张计划卡现在不能执行';
  }
  return '';
}
/** 计划停靠区：开始执行（带本卡 planId，旧卡重复点击由后端 409 挡住） */
function onPlanConfirm(planId?: string) {
  const blocked = planBlockedMsg();
  if (blocked) {
    pushToast(blocked, 'info');
    return;
  }
  getAssistant(userStore.userId).notePlanDecision('confirm', planId);
  onSend(PLAN_CONFIRM_TEXT, '');
}
/** 计划停靠区：改一下——只发一句反馈，不再把整份计划回填输入框 */
function onPlanAdjust(planId: string | undefined, feedback: string) {
  const text = feedback.trim();
  if (!text) {
    return;
  }
  const blocked = planBlockedMsg();
  if (blocked) {
    pushToast(blocked, 'info');
    return;
  }
  getAssistant(userStore.userId).notePlanAdjust(planId);
  onSend(text, '');
}
/** 计划停靠区：取消只作废这张卡；卡留在聊天记录里显示「已取消」 */
function onPlanCancel(planId?: string) {
  // 题8（三席回修）：取消也是明确动作——下一次请求带 planDecision=cancel 解闸，
  // 否则后端硬闸要等 30 分钟 TTL 才放开写操作
  getAssistant(userStore.userId).notePlanDecision('cancel', planId);
  // ②（2026-10-31）：当场打清闸端点立即解闸，不等下一条消息；失败提示，下一条消息仍兜底
  getAssistant(userStore.userId)
    .cancelPlanOnServer(planId)
    .then((ok) => {
      if (!ok) {
        pushToast('取消请求没送达，下一条消息会自动重试', 'info');
      }
    });
}

function onNewSession() {
  // 手机上点「新会话」后收起历史抽屉，新对话区直接可见
  sideOpen.value = false;
  if (state.sending) {
    stop(true);
  }
  newSession();
  selected.value = null;
  selectedId.value = '';
  actions.value = [];
  // 搜索词一起清：不清的话新会话列表会被上一次的关键字过滤，用户以为会话没了
  kw.value = '';
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
  // 手机端选完会话即收起历史抽屉，主对话区全宽
  sideOpen.value = false;
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
    const [msgs, acts] = (await Promise.all([listMessages(c.id, { limit: 100, tail: true }), listActions({ threadId: c.threadId })])) as unknown as [
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

/** 批A：检测到自动压缩（上下文大幅回落）后重开当前会话——「已自动压缩」分隔条随历史重建出现。
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
  // 题5 续看：尾回合换新空占位并挂真流（重放从零重建，含已分段落库的部分正文场景）；
  // 未命中/失败静默走下方 3s 轮询兜底，终态后重开会话拉持久化真历史
  const placeholder = resumeStreamingPlaceholder();
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

onMounted(async () => {
  await loadConversations();
});

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
  if (sideOpen.value) {
    sideOpen.value = false;
    return;
  }
  if (composerRef.value?.escClosePop()) {
    return;
  }
  if (state.sending) {
    stop();
  }
}
onMounted(() => {
  document.addEventListener('keydown', onPageEsc);
  mqMobile.addEventListener('change', syncMobile);
});
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onPageEsc);
  mqMobile.removeEventListener('change', syncMobile);
});
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

/** 批3.5·题3：agent 壳的遥控口——新任务/灌草稿/聚焦/刷新列表（只暴露已有函数，不加新逻辑） */
defineExpose({
  newSession: onNewSession,
  openConversation,
  reloadConversations: () => void loadConversations(true),
  setDraft: (t: string) => composerRef.value?.setDraft(t),
  /** 读草稿 / 清空 / 悬停灰字层：工作台悬停预填要判"框里有没有字、是不是我们填的那句"（批5 题四甲） */
  draftText: computed(() => composerRef.value?.draftText ?? ''),
  clearDraft: () => composerRef.value?.clearDraft(),
  setGhostText: (t: string | null) => composerRef.value?.setGhostText(t),
  focusComposer: () => composerRef.value?.focus()
});
</script>

<style lang="scss" scoped>
.dcs-as-session {
  display: flex;
  gap: 12px;
  /* Q21（2026-10-29 拍板前实测）：底部恒空 96px——原公式扣 --session-chrome
     （= app-chrome + 16 + --app-fab-yield 80），而壳层 .main-container/.app-main 早已改通高贴底，
     那 80px 是「给悬浮球让位」的历史值；本页悬浮球 v-show=false（index.vue：/ai/assistant 路由下整体隐藏），
     于是 1440×900 实测 root 底 804 / 视口 900，白空 96（80+16）。
     Q23（2026-10-29）：只扣页头，呼吸位交给 padding——全局 box-sizing=border-box，
     再减 24 等于「padding 之外又多留 24」，实测四边 12/12/12/36 不齐；现在四边同为 12。 */
  height: calc(100vh - var(--app-chrome) - var(--sp-top, 0px) - env(safe-area-inset-bottom, 0px));
  height: calc(100dvh - var(--app-chrome) - var(--sp-top, 0px) - env(safe-area-inset-bottom, 0px));
  padding: 12px;

  .side {
    width: 300px;
    flex: none;
    display: flex;
    flex-direction: column;
    /* Q24（2026-10-29 用户截图红圈）：全局 element 选择器 `aside{...}`（assets/styles/index.scss:150，
       若依模板的文档页样式）打穿了本组件的根 aside——①margin-bottom:20px 让卡片底比右栏主区
       高 20px（红圈处那条 20px 白带）；②padding:8px 24px 把 298 的内容宽吃到 250，会话滚动条
       因此悬在离卡右缘 24px 的空白里；③line-height:32px 把每行会话从 ~56 撑到 82（标题行 32、
       时间行 32）；④font-size:16px 抬高所有没显式设字号的文字。抽屉根 aside 早前已按同因复位
       line-height/font-size（AssistantDrawer.vue `.dcs-dw`），本页整树一起复位。 */
    margin: 0;
    padding: 0;
    line-height: 1.5;
    font-size: 13px;
    border: 1px solid var(--dcs-border-color-lighter);
    border-radius: var(--app-card-radius);
    background: var(--el-bg-color);
    overflow: hidden;

    .side-hd {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      padding: 10px 12px 6px;

      .side-t {
        font-size: 14px;
        font-weight: 600;
        color: var(--dcs-text-primary);
      }
    }
    .side-search {
      margin: 0 10px 8px;
      width: calc(100% - 20px);
    }

    /* 批3.6：agent 壳左栏（新任务 / 五项导航 / 搜索）。
       只在本分支渲染，会话页走上面 .side-hd 旧样式，一行不受影响。
       字号只用 13（档内），间距只用 4/8/12，圆角 8——全部对齐 dcs-tokens。 */
    .ag-side-hd {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px;

      .side-close {
        margin-left: auto;
      }
    }
    .ag-new {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      height: 32px;
      padding: 0 12px;
      border: 1px solid var(--dcs-border-color);
      border-radius: 8px;
      background: var(--el-bg-color);
      color: var(--dcs-text-primary);
      font-family: inherit;
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      transition:
        background-color 0.16s,
        border-color 0.16s,
        color 0.16s;

      &:hover {
        border-color: var(--el-color-primary);
        background: var(--el-color-primary-light-9);
        color: var(--el-color-primary);
      }
      /* 深色档 light-8 与 light-9 同值（都是 #16283f），按下去会没反馈，所以用 light-7 */
      &:active {
        background: var(--el-color-primary-light-7);
      }
      /* 焦点环跟本组件自己的按钮走：宿主 index.vue 那条 :is(button,[role=button]) 是 scoped，
         穿不进这个文件，否则同屏会出现「2px 深色环 + 浏览器默认蓝环」两套 */
      &:focus-visible {
        outline: 2px solid var(--el-color-primary);
        outline-offset: 2px;
      }
    }
    .ag-nav {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 0 12px 8px;

      > a,
      > span,
      > button {
        display: flex;
        align-items: center;
        gap: 8px;
        width: 100%;
        height: 32px;
        padding: 0 8px;
        border: 0;
        border-radius: 8px;
        background: none;
        font-family: inherit;
        font-size: 13px;
        font-weight: 400;
        text-align: left;
        color: var(--dcs-text-secondary);
        text-decoration: none;
        user-select: none;
      }
      > a {
        cursor: pointer;

        &:hover {
          background: var(--el-fill-color-light);
          color: var(--dcs-text-primary);
        }
        &:focus-visible {
          outline: 2px solid var(--el-color-primary);
          outline-offset: 2px;
        }
      }
      /* 当前所在位置（会话）：不可点，所以不给 hover/焦点，只给选中态 */
      > .on {
        background: var(--el-color-primary-light-9);
        color: var(--el-color-primary);
        font-weight: 500;
      }
      /* 置灰：功能本体还没建，留入口但不假装能用——disabled 按钮不进 Tab、不响应点击，
         title 在鼠标悬停时说明原因 */
      > .off {
        color: var(--dcs-text-placeholder);
        cursor: default;
      }
    }
    .ag-side-search {
      margin: 0 12px 8px;
      width: calc(100% - 24px);
    }
    /* 底部当前用户（只在 agent 壳渲染）——把「我是谁」钉在左栏底，不再挤进顶栏 */
    .ag-user {
      flex: none;
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px 12px 8px;
      border-top: 1px solid var(--dcs-border-color-lighter);

      .ag-uav {
        width: 28px;
        height: 28px;
        flex: none;
        display: grid;
        place-items: center;
        border-radius: 50%;
        background: var(--dcs-text-primary);
        color: var(--el-bg-color);
        font-size: 12px;
        font-weight: 500;
      }
      .ag-umeta {
        min-width: 0;

        b {
          display: block;
          font-size: 13px;
          font-weight: 500;
          line-height: 1.3;
          color: var(--dcs-text-primary);
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
        span {
          display: block;
          font-size: 12px;
          color: var(--dcs-text-placeholder);
        }
      }
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
      font-size: var(--dcs-font-xs);
      line-height: 1;
      padding: 2px 6px;
      border-radius: var(--app-card-radius);
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
    border-radius: var(--app-card-radius);
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
      border-radius: var(--dcs-radius-xs);
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
      border-radius: var(--dcs-radius-sm);
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
        border-radius: var(--dcs-radius-sm);

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

  /* 批3.6：agent 工作台空态布局。
     实机取证（1440×900 /agent）：.main 828 = intro 77 + 卡片区 285 + 标题条 45
     + 会话区 243 + 输入框 156，而欢迎卡本身 497 高——被塞进 243，只露 42% 还被输入框切断；
     根因是「工作台欢迎区」与 MsgFlow 自带的欢迎卡两块叠着。现在：
     ① 欢迎卡由 hide-welcome 关掉（MsgFlow 层）② 会话区在空态塌 0（这里）
     ③ 标题条在空态收起（「新会话」与进门内容重复）④ 剩下的内容作为一组居中。
     会话页 variant=session，agEmpty 恒 false，以上一条都不会命中。 */
  .main.ag-empty {
    /* 批4：整组下沉。实测原来是一像素不差的几何居中（上 295 / 下 294），但框内「上紧下松」——
       标题到输入框只有输入框自己的 8px 内边距，卡片下面却空 294px，眼睛就把整块读高了。
       center 对齐下 padding-top 只会把内容推下「一半」，所以乘 2：1080 屏 lift=28px、整组下沉约 56px。
       手机档在下方的 ≤992 里归零——那档内容已经比一屏高，下沉会把输入框顶出首屏。 */
    --ag-lift: clamp(0px, 2.6vh, 30px);
    padding-top: calc(var(--ag-lift) * 2);
    /* 批4：输入框投影走令牌，深色只换值。原来深色另写一条选择器，两条类计数相同，
       是靠一个 html 类型选择器破平局——日后给亮色那条多挂一个类，深色就静默失效 */
    --ag-box-shadow: 0 1px 2px rgba(16, 24, 40, 0.05), 0 10px 28px -14px rgba(16, 24, 40, 0.16);
    justify-content: center;
    /* 视口一矮（1440×720、125% 缩放）整组就比可用高还高：flex 居中 + overflow:hidden
       会把上下两端同时裁掉且够不到。safe center 在溢出时自动退化成 flex-start，
       配 overflow-y:auto 才有滚动口；不支持 safe 的浏览器退回上一行 center，不会更差。 */
    justify-content: safe center;
    overflow-y: auto;
    overflow-x: hidden;

    .main-hd {
      display: none;
    }
    /* 会话区是 MsgFlow 的根节点，用 :deep 穿透（同下方 992 断点的写法）。
       visibility:hidden 是兜底：万一哪天 height:0 被别的规则盖掉，里面的按钮也不会被 Tab 到 */
    :deep(.dcs-flow) {
      flex: 0 0 0;
      min-height: 0;
      height: 0;
      padding-top: 0;
      padding-bottom: 0;
      overflow: hidden;
      visibility: hidden;
    }
    /* 输入区平时坐在灰色页脚带上（和消息区分层）；空态没有消息，这条带子会横切白卡，
       看起来像一条悬空的灰杠——空态改成透明，输入框直接浮在白卡上。
       深色档例外：.box 与 .main 同为 --el-bg-color，透明后输入卡只剩 1px 描边、糊在面板里，
       所以深色下把 .box 抬一档底色，形体还在。 */
    :deep(.dcs-comp) {
      background: transparent;
      /* 批4：标题下沿到输入框上沿原来只有 8px（输入框自己的内边距），标题等于贴在框上。
         20 是 4/8/12/16/24/32 六档里的「一档半再多」，实测 1440/1920 都读得开 */
      padding-top: 20px;
    }
    /* :not(.focus) 是必需的：这条规则的类计数高于 Composer 自己的 .box.focus，
       不排除聚焦态就把蓝焦点边压掉了——全站只有本页「点了输入框没反应」 */
    :deep(.dcs-comp .box:not(.focus)) {
      border-color: var(--dcs-border-color);
    }
    :deep(.dcs-comp .box) {
      /* 批4：本页唯一「你要动手」的东西。原来只有 0 1px 2px 4%，和卡片一个平面；
         现在近处一条细边 + 远处一层柔光，让它成为页面上唯一浮起来的物件。
         再重一档就成浮层卡片，和整站「安静边框」的基调冲突——这是上限 */
      box-shadow: var(--ag-box-shadow);
    }
  }
  /* safe center 不支持时退回 flex-start（老 Chrome/Safari）：宁可贴着顶部，
     也别「居中后被上下同时裁掉、还没滚动口」 */
  @supports not (justify-content: safe center) {
    .main.ag-empty {
      justify-content: flex-start;
    }
  }

  /* 批4：手机档不下沉。390×844 实测内容本来就比一屏高 22px（要滚动才看得完第三张卡），
     再下沉只会把输入框往首屏外推；间隙也从 20 收到 12，省 8px 给技能卡 */
  @media (max-width: 992px) {
    .main.ag-empty {
      --ag-lift: 0px;
    }
    .main.ag-empty :deep(.dcs-comp) {
      padding-top: 12px;
    }
  }
  /* 批4：宽而矮的窗口（1440×720、宽屏拉半高）内容放不下时 safe center 会退成 flex-start，
     下沉就从「视觉补偿」变成「白占一块死区」把输入框再往下顶。内容高约 417、可用高约视口-74，
     解 417 + lift×2 > 可用高 得视口 < 520，把闸放在 560 留余量 */
  @media (min-width: 993px) and (max-height: 560px) {
    .main.ag-empty {
      --ag-lift: 0px;
    }
  }

  .main {
    flex: 1;
    min-width: 0;
    position: relative;
    display: flex;
    flex-direction: column;
    border: 1px solid var(--dcs-border-color-lighter);
    border-radius: var(--app-card-radius);
    background: var(--el-bg-color);
    overflow: hidden;
    /* Q8/Q9/三反馈② 廊位：StatPanel 按容器宽解算（fills P+4 / capped 2P+956-W / 0 贴列）写
       --sp-strip，本层消费——胶囊与面板始终贴内容列右缘，宽屏不再漂在空档；
       StatPanel 为 absolute 浮层不吃本层 padding，正文列与聊天框一起让、一起回
       Q32（2026-10-30）：mini 退状态点时另写 --sp-strip-l，**左右等量**——
       旧版只让右侧，列按剩余区居中 ⇒ 左 25 / 右 48（1280）差 23px，肉眼就是歪的 */
    padding-right: var(--sp-strip, 0px);
    padding-left: var(--sp-strip-l, 0px);
    transition:
      padding-right 0.3s ease,
      padding-left 0.3s ease;

    /* Q28（2026-10-30 用户实拍两个红框）：让位带不留白。面板是浮在会话画布上的一层，
       它身后这条竖带以前是 .main 的白底，和右侧白卡糊成一片白；现在按答案区同一档
       画布色（--el-fill-color-light）铺底，整块读作「画布继续向右延」，面板落在画布上。
       伪元素只吃 padding 区（内容都在 padding-box 内），不遮任何子元素。 */
    &::after {
      content: '';
      position: absolute;
      inset: 0 0 0 auto;
      width: var(--sp-strip, 0px);
      background: var(--el-fill-color-light, #f5f7fa);
      pointer-events: none;
      transition: width 0.3s ease;
      /* Q36：滚动区（.dcs-flow）现在压进让位带里，得让它画在这条带之上——
         否则 6px 滚动条正好被带子盖掉（带子是定位元素，默认压在普通流内容上）。 */
      z-index: 0;
    }

    .main-hd {
      display: flex;
      align-items: center;
      gap: 8px;
      /* Q29：标题跟正文列同一左缘——正文列居中后，标题还钉 14px 会在宽屏错开一大截
         （1920 实测列左 214、标题左 14）。列被帽限制时列左 =（内容区 − 列帽）/ 2；
         列撑满时上式会掉到负数，用 25 兜底（= 列内距，与 .box 实宽同位） */
      padding: 10px 14px;
      padding-left: calc(var(--sp-strip-l, 0px) + max(25px, calc((100% - var(--dcs-col-max, 900px)) / 2)));
      /* Q32：左侧让位后标题条会被推离卡片左缘、下边框缺一小段——负外边距吃掉让位宽、
         内距再补回来（padding 百分比对内层是「父内容宽」，不吃这层负 margin，故补一个 strip-l） */
      margin-left: calc(-1 * var(--sp-strip-l, 0px));
      /* Q28：让位带铺色后，标题条横贯整条（否则白底在带口断一刀）——
         负外边距吃掉让位宽、右内距补回来，文字位置不变，下边框一直画到右缘 */
      margin-right: calc(-1 * var(--sp-strip, 0px));
      padding-right: calc(14px + var(--sp-strip, 0px));
      position: relative;
      z-index: 1;
      background: var(--el-bg-color);
      border-bottom: 1px solid var(--dcs-border-color-lighter);
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
    :deep(.dcs-flow) {
      flex: 1;
    }
    /* Q36（2026-10-30 用户红圈「这个滚动条能不能放在最右侧」）：消息区滚动条原来贴的是
       「内容列右缘 + 24」，离卡内右缘整整一个让位宽（1920 实测滚动条 1715–1720、卡内右缘 1907）——
       因为滚动容器是 .dcs-flow，而让位是 .main 的 padding。把让位带并进滚动容器：
       负外边距吃掉 .main 的 padding-right，再由自己的 padding-right 补回来。
       **内容盒宽一格不变**（= 卡宽 − strip − 48 − 滚动条），所以列几何与 Q29–Q35 的两侧缝全部不动；
       变的只是滚动口——从「卡宽 − strip」变成整卡宽，滚动条因此贴到卡内右缘。
       与 .main-hd 用的是同一条负边距手法。过渡也必须同一条 0.3s：否则展开/收起时 .main 的 padding
       在动、负 margin 一步到位，滚动口会短暂比卡还宽（被 overflow:hidden 裁掉、滚动条闪一下）。 */
    :deep(.dcs-flow.full) {
      margin-right: calc(-1 * var(--sp-strip, 0px));
      padding-right: calc(24px + var(--sp-strip, 0px));
      position: relative;
      z-index: 1;
      transition:
        margin-right 0.3s ease,
        padding 0.3s ease;
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

/* 深色档空态：.box 与 .main 同为 --el-bg-color，容器背景透明后只剩 1px 描边会糊在面板里，
   这里给输入卡抬一档底色（html.dark 在组件根之外，只能写在顶层，靠 .main.ag-empty 限定作用域） */
html.dark .main.ag-empty {
  /* 深色下黑影看不见，只留落影；「亮环」不用 box-shadow 画——0 0 0 1px 画在 border-box
     外面，输入框会比下方卡片行左右各宽 1px（实测亮色 x=1655 / 深色 x=1656），
     改成同一条 border 上换色（见下一条） */
  --ag-box-shadow: 0 12px 32px -16px rgba(0, 0, 0, 0.6);
}
html.dark .main.ag-empty :deep(.dcs-comp .box) {
  background: var(--el-fill-color-light);
}
html.dark .main.ag-empty :deep(.dcs-comp .box:not(.focus)) {
  border-color: rgba(255, 255, 255, 0.14);
}

/* ≤992：左侧会话栏改历史抽屉，主对话区全宽 */
.side-toggle,
.side-close,
.side-mask {
  display: none;
}

@media (max-width: 992px) {
  .dcs-as-session {
    gap: 0;
    /* Q21 同口径：手机档 padding 8 → 四边同 8；悬浮球在会话路由同样隐藏，不再预留 fab-yield。
       Q23：呼吸位只靠 padding，公式不再额外减 16（box-sizing=border-box 会叠成 24）。 */
    height: calc(100vh - var(--app-chrome) - var(--sp-top, 0px) - env(safe-area-inset-bottom, 0px));
    height: calc(100dvh - var(--app-chrome) - var(--sp-top, 0px) - env(safe-area-inset-bottom, 0px));
    padding: 8px;

    .side {
      position: fixed;
      top: 0;
      bottom: 0;
      left: 0;
      z-index: 1201;
      width: min(320px, 86vw);
      height: 100dvh;
      padding-top: var(--sp-top, 0px);
      padding-bottom: env(safe-area-inset-bottom, 0px);
      border-top: 0;
      border-bottom: 0;
      border-left: 0;
      border-radius: 0;
      transform: translateX(-100%);
      transition: transform 0.25s ease;

      &.mobile-open {
        transform: translateX(0);
      }
    }

    .side-mask {
      display: block;
      position: fixed;
      inset: 0;
      z-index: 1200;
      background: rgb(0 0 0 / 32%);
    }

    .side-toggle {
      display: inline-flex;
      align-items: center;
      flex: none;
      gap: 4px;
      min-height: 44px;
      padding: 0;
      border: none;
      background: none;
      color: var(--el-color-primary);
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
    }

    .side-close {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex: none;
      width: 44px;
      height: 44px;
      margin-left: auto;
      border: none;
      background: none;
      color: var(--dcs-text-secondary);
      cursor: pointer;
    }

    .main {
      width: 100%;
      padding-right: 0;

      /* 批3.6：手机档空态不能沿用桌面的「居中 + 收起标题条」——
         ① 标题条里的「历史会话」是手机唯一能打开抽屉的入口，收掉就没路了；
         ② 三张技能卡竖排比一屏高，居中会被 .main 的 overflow:hidden 切掉。
         所以手机档改成从顶部顺排 + 整列可滚：输入框仍在首屏，卡片往下滚就能看全。 */
      &.ag-empty {
        justify-content: flex-start;
        overflow-y: auto;
        overflow-x: hidden;
        /* 手机档 .main 只清了右内距，左内距还挂着 StatPanel 写的 --sp-strip-l；
           空态面板是隐藏的，两边一起清干净，否则内容列会被整体右推 */
        padding-left: 0;

        /* DOM 里标题槽位在标题条之前（桌面档标题条是收起的，看不出来）；
           手机档标题条要显示，必须用 order 提回最顶，否则「历史会话」入口会掉到标题下面。
           但标题条里的「新会话」四个字与下面的 h1 重复，空态只留按钮不留字。
           sticky 是必须的：这张条是手机上唯一能打开会话抽屉的入口，往下滚看技能卡时不能滚丢。 */
        .main-hd {
          display: flex;
          order: -1;
          position: sticky;
          top: 0;
          z-index: 3;
          padding: 8px 12px;

          > span {
            display: none;
          }
        }
      }

      /* 手机端不占对话宽度；进度信息在消息流里仍可见 */
      :deep(.stat-panel) {
        display: none;
      }

      /* Q36：面板隐藏时让位本该是 0，但 StatPanel 仍会按宿主宽写 --sp-strip；
         这里把负边距收回去，否则滚动口会比卡片宽出一个 strip（被 overflow 裁掉、滚动条看不见） */
      :deep(.dcs-flow.full) {
        margin-right: 0;
        padding-right: 24px;
      }
    }

    .main .main-hd {
      flex-wrap: wrap;
      gap: 6px;
      padding: 8px 12px;
    }
  }
}
</style>
