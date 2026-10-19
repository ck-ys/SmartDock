<template>
  <teleport to="body">
    <div v-if="mounted" class="dcs-dw-mask" :class="{ open: modelValue }" @click="close" />
    <aside class="dcs-dw" :class="{ open: modelValue, full: full }" role="dialog" aria-label="DCS 小助手">
      <header v-show="!full" class="dw-head">
        <span class="orb"><IconSpan name="bot" :size="18" /></span>
        <div class="hd-txt">
          <b>DCS 小助手</b>
          <!-- 业务批4 Q2 拍板二：任务上下文条下移到输入框上方（.ctx-bar），头部只留页面感知 -->
          <span class="ctx"><IconSpan name="map-pin" /> 当前页面：{{ pageName }}</span>
        </div>
        <span class="role-tag" :title="roleLabel + '视角：可用的工具与建议按身份自动区分'">{{ roleLabel }}模式</span>
        <div class="hd-btns">
          <el-tooltip content="使用说明" :show-after="400">
            <button class="ic" aria-label="使用说明" title="使用说明" @click="help.show()">
              <IconSpan name="help" />
            </button>
          </el-tooltip>
          <el-tooltip content="展开全屏" :show-after="400">
            <button class="ic" aria-label="展开全屏" title="展开全屏" @click="full = true">
              <IconSpan name="maximize" />
            </button>
          </el-tooltip>
          <el-tooltip content="收起面板（点悬浮球再开）" :show-after="400">
            <button class="ic" aria-label="关闭" title="收起面板（点悬浮球再开）" @click="close">
              <IconSpan name="x" />
            </button>
          </el-tooltip>
        </div>
      </header>

      <!-- 屏D 页头（原型 .oh：「← + 会话标题 + 身份徽标 + ⛶收侧栏 + ❓」） -->
      <div v-if="full" class="full-bar">
        <button class="ic fb-back" aria-label="返回抽屉" title="返回抽屉" @click="full = false">
          <IconSpan name="arrow-left" />
        </button>
        <span class="fb-title">{{ sessionTitle }}</span>
        <!-- AU-36：归档会话回放态可见提示（发送在 onSend 拦，后端 403 双保险） -->
        <span v-if="state.curConvArchived" class="fb-arc">已归档 · 只读</span>
        <span class="role-tag">{{ roleLabel }}模式</span>
        <span class="fb-sp" />
        <button class="ic" :aria-pressed="wide ? 'true' : 'false'" :title="wide ? '展开侧栏' : '收起侧栏'" @click="toggleWide">
          <IconSpan name="layout" />
        </button>
        <button class="ic" aria-label="使用说明" title="使用说明" @click="help.show()">
          <IconSpan name="help" />
        </button>
        <!-- 业务批4 Q6：全屏顶栏补✕——与遮罩同款 close 语义（收起不打断，生成继续跑），
             小屏无遮罩可点时是唯一收起出路（此前三路全断实锤） -->
        <button class="ic" aria-label="收起助手" title="收起助手" @click="close">
          <IconSpan name="x" />
        </button>
      </div>

      <div class="fp-wrap">
        <!-- 屏D 左会话栏（原型 .fp-l：搜索+新会话+列表；点击切换=重放接管单例） -->
        <div v-if="full && !wide" class="fp-l">
          <label class="fp-search"
            ><IconSpan name="search" :size="13" /> <input v-model="fpKw" type="search" placeholder="搜索会话" aria-label="搜索会话"
          /></label>
          <button class="fp-new" title="新建会话（Alt+N）" @click="onFpNewSession"><IconSpan name="plus" /> 新会话</button>
          <div class="fp-list" aria-label="会话列表">
            <!-- DP-1 原型 renderSessions：📌 置顶组 + 今天/昨天分组；R4 二改：分组头带计数、
                 「更早」收折叠条（默认收起，搜索/当前会话命中时自动展开），时间改分组感知口径 -->
            <template v-for="g in fpGroupsTop" :key="g.label">
              <div class="fp-grp">
                <IconSpan v-if="g.label === '置顶'" name="map-pin" :size="11" />
                <span>{{ g.label }}</span>
                <span class="g-badge">{{ g.items.length }}</span>
              </div>
              <div
                v-for="c in g.items"
                :key="c.id"
                class="fp-item"
                :class="{ on: c.threadId === state.threadId, pin: pins.includes(String(c.id)) }"
                @click="onFpOpen(c)"
              >
                <span v-if="fpRowStatus(c) === 'answering'" class="sspin" title="正在回答…"><IconSpan name="loader-circle" :size="11" /></span>
                <span v-else-if="fpRowStatus(c) === 'error'" class="sdot sdot-err" title="最近一轮出错，可点重试" />
                <span v-else-if="fpActive(c)" class="sdot" title="最近活跃" />
                <div class="fp-mid">
                  <div class="fp-t">
                    <input
                      v-if="renamingId === c.id"
                      v-model="renameVal"
                      class="fp-rename"
                      :ref="fpRenameFocus"
                      @click.stop
                      @keydown.enter.prevent="commitRename(c)"
                      @keydown.esc.stop.prevent="cancelRename"
                      @blur="commitRename(c)"
                    />
                    <template v-else>{{ c.title || '（未命名会话）' }}</template>
                  </div>
                  <div class="fp-time">{{ fpRowStatus(c) === 'answering' ? '正在回答…' : fpDayTime(c.lastMessageTime) }}</div>
                </div>
                <button v-if="renamingId !== c.id" class="fp-more" title="会话操作" aria-label="会话操作" @click.stop="openSessMenu(c)">⋯</button>
                <!-- DP-1 原型 sessMenu：重命名/置顶/归档（Q8=B 拍板不补删除） -->
                <div v-if="sessMenuId === c.id" class="fp-ctxmenu" role="menu">
                  <button role="menuitem" @click.stop="startRename(c)"><IconSpan name="pencil" :size="13" /> 重命名</button>
                  <button role="menuitem" @click.stop="togglePin(c)">
                    <IconSpan name="map-pin" :size="13" /> {{ pins.includes(String(c.id)) ? '取消置顶' : '置顶' }}
                  </button>
                  <button role="menuitem" :class="{ dgw: arcArmed }" @click.stop="archiveConv(c)">
                    <IconSpan name="database" :size="13" /> {{ arcArmed ? '再点一次确认归档' : '归档' }}
                  </button>
                </div>
              </div>
            </template>
            <!-- R4 二改：更早折叠条（与会话页同款；归档条仍在其后） -->
            <div v-if="fpOlder" class="fp-grpbar" @click="fpOlderOpen = !fpOlderOpen">
              <IconSpan name="history" :size="13" /> 更早 ({{ fpOlder.items.length }})
              <IconSpan class="aarr" :name="fpOlderOpen ? 'chevron-up' : 'chevron-down'" :size="12" />
            </div>
            <template v-if="fpOlderOpen && fpOlder">
              <div
                v-for="c in fpOlder.items"
                :key="c.id"
                class="fp-item"
                :class="{ on: c.threadId === state.threadId, pin: pins.includes(String(c.id)) }"
                @click="onFpOpen(c)"
              >
                <span v-if="fpRowStatus(c) === 'answering'" class="sspin" title="正在回答…"><IconSpan name="loader-circle" :size="11" /></span>
                <div class="fp-mid">
                  <div class="fp-t">{{ c.title || '（未命名会话）' }}</div>
                  <div class="fp-time">{{ fpRowStatus(c) === 'answering' ? '正在回答…' : fpDayTime(c.lastMessageTime) }}</div>
                </div>
                <button class="fp-more" title="会话操作" aria-label="会话操作" @click.stop="openSessMenu(c)">⋯</button>
              </div>
            </template>
            <!-- DP-1 归档折叠条（原型 arcbar：↩ 恢复；Q8=B 不做彻底删除） -->
            <div v-if="fpArchived.length" class="fp-arcbar" :class="{ open: arcOpen }" @click="arcOpen = !arcOpen">
              <IconSpan name="database" :size="13" /> 已归档 ({{ fpArchived.length }})
              <IconSpan class="aarr" name="chevron-down" :size="12" />
            </div>
            <div v-if="arcOpen" class="fp-arcbox">
              <div v-for="c in fpArchived" :key="'a' + c.id" class="fp-item arc" @click="onFpOpen(c)">
                <div class="fp-mid">
                  <div class="fp-t">{{ c.title || '（未命名会话）' }}</div>
                  <div class="fp-time">{{ fpDayTime(c.lastMessageTime) }}</div>
                </div>
                <button class="fp-more" title="恢复会话" aria-label="恢复会话" @click.stop="restoreConv(c)">
                  <IconSpan name="undo" :size="13" />
                </button>
              </div>
            </div>
            <div v-if="fpGroups.length === 0 && fpArchived.length === 0" class="fp-empty">没有匹配的会话</div>
            <!-- AU-19：超 50 条的旧会话翻页 -->
            <button v-if="fpRealCount < fpTotal" class="fp-loadmore" @click="loadMoreFpConvs">
              加载更多（已显示 {{ fpRealCount }}/{{ fpTotal }}）
            </button>
          </div>
          <div class="fp-note">会话与悬浮球抽屉同步 · 全部 {{ fpTotal }} 条</div>
        </div>

        <!-- Q8/Q9/三反馈②：廊位由 StatPanel 解算写 --sp-strip 变量（仅全屏有面板），本层只消费 -->
        <div class="dw-body">
          <!-- 批4 Q11：管理员公告横幅——本地可关（按用户+公告版本），编辑重发/新发自然重现；MAINTENANCE=警示色 -->
          <div
            v-if="ann && ann.token !== annDismissedToken"
            class="dw-banner ann"
            :class="ann.style === 'MAINTENANCE' ? 'warn' : 'info'"
            role="status"
          >
            <span class="ann-text" :title="ann.title + (ann.content ? '　' + ann.content : '')"
              ><b>{{ ann.title }}</b
              ><template v-if="ann.content">　{{ ann.content }}</template></span
            >
            <button class="ann-x" aria-label="关闭公告" @click="dismissAnn">知道了</button>
          </div>
          <!-- 异常态横幅多态（批E：offline 可重试/quota 额度尽/degraded 降级/auth 鉴权失效；J-2 三态配色+通栏+重试右对齐） -->
          <div v-if="state.banner === 'offline'" class="dw-banner warn" role="status">
            <span>连接已断，回答可能不完整。</span><a @click.prevent="retryLast">点击重试</a>
          </div>
          <div v-else-if="state.banner === 'quota'" class="dw-banner info" role="status">
            <span>今天的 {{ quotaLimit }} 个来回用完了，明早 0 点恢复</span>
          </div>
          <div v-else-if="state.banner === 'degraded'" class="dw-banner info" role="status"><span>AI 服务降级中，回答可能变慢</span></div>
          <div v-else-if="state.banner === 'auth'" class="dw-banner dg" role="status"><span>登录已过期，请重新登录后再使用助手</span></div>
          <!-- R1（2026-10-13）：批③用户额度禁用闸的前端消费——被禁用户打开即见，不再"石沉大海" -->
          <div v-else-if="state.banner === 'disabled'" class="dw-banner dg" role="status">
            <span>管理员已暂停你的 AI 助手使用，如有疑问请联系管理员</span>
          </div>

          <msg-flow
            ref="msgFlowRef"
            :state="state"
            :cur="cur"
            :follow="follow"
            :full="full"
            :snap="full ? snapItems : undefined"
            @scroll-lock="onScrollLock"
            @resolve-hitl="resolveHitl"
            @pick="(p) => onSend(p, '')"
            @resend="onResend"
            @resend-run="(q) => onSend(q, '')"
            @resume="onResume"
            @stop="onStop"
            @fill="onFillDraft"
            @new-session="newSession"
            @recover-interrupt="recoverInterrupt"
            @open-help="help.show()"
            @branch="onBranch"
            @ask="(t) => onSend(t, '')"
            @pgoto="onPageGoto"
            @mdnav="onMdNav"
            @draft-set="onDraftSet"
            @plan-cancel="onPlanCancel"
            @tour="startTour"
          />

          <!-- 业务批4 Q2 拍板二：任务上下文条（原头部 chip 下移）——紧贴输入框上方，
               🎯 任务名 ▾ 四项菜单 + ✕ 可收（收=本会话不带任务上下文；tmenu 点外关沿用） -->
          <div v-if="taskCtx.missionName && !ctxDismissed" class="ctx-bar">
            <span
              class="cb-main"
              role="button"
              tabindex="0"
              @click="taskMenuOpen = !taskMenuOpen"
              @keydown.enter.prevent="taskMenuOpen = !taskMenuOpen"
            >
              <IconSpan name="compass" :size="13" />
              <span class="cb-name" :title="taskCtx.missionName">{{ taskCtx.missionName }}</span>
              <!-- 视觉席 U7b P3：展开态箭头翻转（有 menuOpen 态可绑，不再是纯装饰） -->
              <IconSpan name="chevron-down" :size="12" :class="{ open: taskMenuOpen }" class="cb-chev" />
            </span>
            <!-- 视觉席 U7 P3：✕ 钉在条右缘——名长时按 min-width:0 收敛省略，
                 控件不跟随文字流换行落第二行 -->
            <button class="cb-x" aria-label="收起任务上下文" title="收起任务上下文（本会话不再关联此任务）" @click="ctxDismissed = true">
              <IconSpan name="x" :size="12" />
            </button>
            <span v-if="taskMenuOpen" class="tmenu" role="menu">
              <button v-for="m in TASK_MENU" :key="m[1]" role="menuitem" @click="onTaskMenu(m[2])"><IconSpan :name="m[0]" /> {{ m[1] }}</button>
            </span>
          </div>

          <composer
            ref="composerRef"
            :state="state"
            :sending="state.sending"
            :active="modelValue || full"
            :full="full"
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
            @compacted="reloadCurrentConv"
          />

          <!-- 须知门（首次发问前；同意后原问题自动继续） -->
          <gate-card v-if="showGate" @ok="onGateOk" />

          <!-- 统计面板（DP-3：对齐原型 statdock 只在全屏形态渲染；抽屉形态无面板） -->
          <stat-panel
            v-if="full && state.turns.length > 0"
            :turn="cur()"
            :todo="state.todoItems"
            :todo-jump-id="todoJumpId"
            @step-jump="(id) => msgFlowRef?.jumpToStep(id)"
            @jump-confirm="msgFlowRef?.jumpToConfirm()"
          />
        </div>
      </div>
    </aside>

    <!-- 使用说明弹层（❓ 与 hero「完整说明」共用，原型 #helpModal；批I-3 BN-14 补五条细则/三入口/演示钮）
         业务批10 #3：会话页(/ai/assistant)自带一份同绑 helpModal 单例的弹层，本组件常驻全局，
         不互斥会两份同帧双开、后开的盖住先开的且 help.hide() 同关两份——会话页路由下让位 -->
    <el-dialog
      v-if="!route.path.startsWith('/ai/assistant')"
      :model-value="help.open.value"
      title="DCS 小助手 使用说明"
      width="480px"
      append-to-body
      @update:model-value="help.hide()"
    >
      <div class="dcs-help-body">
        <!-- 五条细则（原型 .hbody 首卡原文案适配） -->
        <div class="help-rules">
          <b><IconSpan name="clipboard" /> 五条细则</b>
          ① 数据范围——只读你当前页面权限内的数据，不越权<br />
          ② 长期记忆——默认关闭；开启后每条可查看来源、可删除<br />
          ③ 运行留痕——每次查库记录元数据（何时/何人/查什么），管理员可见<br />
          ④ 费用——按对话次数计，额度见输入框右上环<br />
          ⑤ 停用——管理员可在设置一键停用；你也可以随时关抽屉
        </div>
        <h4>三步上手</h4>
        <ol class="help-steps">
          <li>第一次打开先过「三条承诺」——只查权限内的数据、动真数据先确认、答不了明说</li>
          <li>用大白话问（例：「我有哪些待办？」），它会自己查库，催办等写操作先弹确认卡</li>
          <li>顺着每条回答底部的建议点下去，或照下面的词典自己打一句</li>
        </ol>
        <h4>照着打就行</h4>
        <table class="help-tab">
          <tbody>
            <tr v-for="row in HELP_TRIGGERS" :key="row[0]">
              <td>{{ row[0] }}</td>
              <td>{{ row[1] }}</td>
            </tr>
          </tbody>
        </table>
        <h4>不想打字？三个入口</h4>
        <ul class="help-ul">
          <li>① 输入框上方的快捷问题按钮（按身份换词）</li>
          <li>② 右下角悬浮球弹出的快捷提问菜单</li>
          <li>③ 输入框左下「＋」面板里的快捷能力（待办速览/发起催办/广播任务解读）</li>
        </ul>
        <h4>藏起来的互动</h4>
        <ul class="help-ul">
          <li>
            回答上悬停出分支/收藏等操作；生成中按 Esc 或点 <IconSpan name="square" :size="11" /> 停止，停了还能「<IconSpan
              name="play-circle"
              :size="12"
            />
            继续说完」
          </li>
          <li>回答中打字会自动排队，答完逐条发出，字不丢；空输入框点发送键=续发暂停的队列</li>
          <li>「＋」面板的「每次都问」= 自主权三档（每次都问 / 同类只问一次 / 全自动）</li>
          <li>输入 @ 引用任务、/ 唤出命令</li>
        </ul>
        <h4>三个角色</h4>
        <ul class="help-ul">
          <li>发布人/管理员：查数据、发催办、看进度</li>
          <li>成员：查自己的待办与提交状态（没有催办工具）</li>
        </ul>
      </div>
      <template #footer>
        <el-button @click="help.hide()">开始使用</el-button>
        <el-button type="primary" @click="onHelpDemo"><IconSpan name="clapperboard" /> 跟我走一遍</el-button>
      </template>
    </el-dialog>

    <!-- 助手域 toast（批B：顶部居中 2600ms 自清） -->
    <assistant-toasts />
  </teleport>
</template>

<script setup lang="ts">
/**
 * 助手抽屉（批E Task E2 → PROTO PARITY 批A）。
 * 状态机=单例 getAssistant（抽屉/全屏/会话页共享同一会话）；本组件只做宿主：
 * 绑草稿/toast、页面感知头部、身份徽标、使用说明弹层、全屏形态。
 * Esc 优先级=帮助弹层&gt;须知门&gt;停止&gt;收起（原型分层语义）。
 */
import { ElMessage } from 'element-plus';
import { useRoute, useRouter } from 'vue-router';
import {
  getAssistantStatus,
  getAssistantAnnouncementActive,
  listActions,
  listConversations,
  listMessages,
  archiveConversation,
  renameConversation,
  restoreConversation
} from '@/api/dcs/assistant';
import { getTask, statsOverview } from '@/api/dcs/core/task';
import { useUserStore } from '@/store/modules/user';
import { getAssistant, tryAcquireRemoteWatch, releaseRemoteWatch } from './useAssistant';
import { relTime, cnEpoch } from './time';
import type { AssistantBindings, AssistantTurn } from './useAssistant';
import { roleOf, roleLabelOf, pageNameOf, taskCtxOf, suggestionsFor } from './assistantCtx';
import { useHelpModal } from './helpModal';
import { pushToast } from './toastStore';
import MsgFlow from './MsgFlow.vue';
import { startTour } from './useTour';
import IconSpan from './IconSpan.vue';
import Composer from './Composer.vue';
import GateCard from './GateCard.vue';
import AssistantToasts from './AssistantToasts.vue';
import StatPanel from './StatPanel.vue';
import { unwrap } from './util';
import type { SnapItem } from './HeroWelcome.vue';
// 批17 Q7：界面导览（聚光灯带看，新手首开引导）

const modelValue = defineModel<boolean>({ required: true });
// AU-34(1008)：原 defineEmits<{stopped}> 已随死代码删除（唯一消费方 index.vue onStoppedBall）

const mounted = ref(true);
const composerRef = ref<InstanceType<typeof Composer>>();
/** DP-3：统计面板跳转通道（step-jump/jump-confirm → MsgFlow expose） */
const msgFlowRef = ref<InstanceType<typeof MsgFlow>>();
const help = useHelpModal();
/** 全屏形态（批A 过渡：同一抽屉展宽；批D 升级为会话栏+限宽 hero 的完整屏D） */
const full = ref(false);
const taskMenuOpen = ref(false);

const userStore = useUserStore();
const route = useRoute();
const router = useRouter();

/** 身份徽标：student 优先，admin/superadmin=管理员，其余发布人（detail.vue 同口径） */
const roleKey = computed(() => roleOf(userStore.roles, userStore.permissions));
const roleLabel = computed(() => roleLabelOf(roleKey.value));
/** 页面感知：当前页面中文名 + 任务详情页任务 chip 上下文 */
const pageName = computed(() => pageNameOf(route));
const rawTaskCtx = computed(() => taskCtxOf(route));
/** 任务名懒取：详情页路由只带 missionId（path 段），名称经既有详情 API 补齐并缓存 */
const taskNameCache = new Map<string, string>();
const taskName = ref('');
watch(
  () => rawTaskCtx.value.missionId,
  async (id) => {
    taskName.value = '';
    if (!id) {
      return;
    }
    const cached = taskNameCache.get(id);
    if (cached) {
      taskName.value = cached;
      return;
    }
    try {
      // 批5 契约迁移: 旧 mission 端点退役, 改用微核心任务详情
      const det = await getTask(id);
      const name = (det?.task?.title ?? '').trim();
      if (name) {
        taskNameCache.set(id, name);
        if (rawTaskCtx.value.missionId === id) {
          taskName.value = name;
        }
      }
    } catch {
      /* 详情不可达：chip 退化为不出（页面行仍显示） */
    }
  },
  { immediate: true }
);
const taskCtx = computed(() => ({
  missionId: rawTaskCtx.value.missionId,
  missionName: rawTaskCtx.value.missionName ?? (taskName.value || undefined)
}));
/** 业务批4 Q2：上下文条✕收起（会话级）；换任务自动重开——新任务新上下文不打永久静音 */
const ctxDismissed = ref(false);
watch(
  () => taskCtx.value.missionId,
  () => {
    ctxDismissed.value = false;
  }
);

/** 任务 chip 四项直达（原型 ctxTaskMenu：查进度/转发链/催办/对比） */
const TASK_MENU: Array<[string, string, string]> = [
  // DP-5 原型 ctxTaskMenu 语气对齐（能力维持实现四项，Q9=B 不换血）
  ['compass', '查它的进度', '这个任务的提交进度怎么样？'],
  ['link', '它的转发链', '把这个任务的转发链查给我看。'],
  ['megaphone', '催办', '帮我对这个任务还没提交的人发一次催办提醒。'],
  ['chart', '和别的任务对比', '这个任务各下级的完成情况对比一下。']
];
function onTaskMenu(prompt: string) {
  taskMenuOpen.value = false;
  onSend(prompt, '');
}

/** 使用说明「照着打就行」触发词典（原型 .htab 9 行） */
const HELP_TRIGGERS: Array<[string, string]> = [
  ['我有哪些待办', '今天的待办清单'],
  ['谁逾期了', '名下逾期情况'],
  ['查未交名单', '某任务未交的人'],
  ['怎么催办', '催办三步指引'],
  ['广播任务是什么', '业务概念 30 秒'],
  ['看板口径', '指标怎么算的'],
  ['退回重做怎么操作', '操作指引'],
  ['最近三天截止', '临期提醒'],
  ['帮我查某人的提交', '提交状态明细']
];

/** 全屏页标题：当前问题截断，无回合时「新会话」（原型 fullTitle 绑定） */
const sessionTitle = computed(() => {
  const t = cur();
  return t ? (t.question.length > 24 ? t.question.slice(0, 24) + '…' : t.question) : '新会话';
});

/* ===== 屏D 全屏形态：侧栏收展 + 会话列表 + 今日概况（批D-3） ===== */
const WIDE_KEY = 'dcs:assistant:wide';
const wide = ref(localStorage.getItem(WIDE_KEY) === '1');
function toggleWide() {
  wide.value = !wide.value;
  localStorage.setItem(WIDE_KEY, wide.value ? '1' : '0');
}

const fpKw = ref('');
type FpConv = { id: number | string; threadId: string; title: string; lastMessageTime?: string; status?: string; running?: boolean };
const fpConvs = ref<FpConv[]>([]);
const PIN_KEY = 'dcs:assistant:pins'; // 与会话页共用同一份置顶（跨宿主一致）
const pins = ref<string[]>(JSON.parse(localStorage.getItem(PIN_KEY) ?? '[]'));

/** DP-1 原型 renderSessions 分组：📌 置顶 + 今天/昨天/更早（关键词只搜标题，与会话页口径一致） */
const fpGroups = computed(() => {
  const kw = fpKw.value.trim().toLowerCase();
  const live = fpConvs.value.filter((c) => c.status !== 'ARCHIVED' && (!kw || (c.title ?? '').toLowerCase().includes(kw)));
  const isPin = (c: FpConv) => pins.value.includes(String(c.id));
  const groups: Array<{ label: string; items: FpConv[] }> = [];
  const pinned = live.filter(isPin);
  if (pinned.length) {
    groups.push({ label: '置顶', items: pinned });
  }
  const td = new Date().toDateString();
  const yd = new Date(Date.now() - 86400_000).toDateString();
  const norms = live.filter((c) => !isPin(c));
  for (const [label, gi] of [
    ['今天', 0],
    ['昨天', 1],
    ['更早', 2]
  ] as const) {
    const items = norms.filter((c) => {
      const ds = new Date(c.lastMessageTime ?? Date.now()).toDateString();
      return (ds === td ? 0 : ds === yd ? 1 : 2) === gi;
    });
    if (items.length) {
      groups.push({ label, items });
    }
  }
  return groups;
});
/** R4 二改（与会话页同款）：置顶/今天/昨天常驻，「更早」收折叠条；搜索或当前会话命中更早时自动展开 */
const fpGroupsTop = computed(() => fpGroups.value.filter((g) => g.label !== '更早'));
const fpOlder = computed(() => fpGroups.value.find((g) => g.label === '更早') ?? null);
const fpOlderOpen = ref(false);
watch(fpKw, (k) => {
  if (k.trim()) {
    fpOlderOpen.value = true;
  }
});
const fpArchived = computed(() => fpConvs.value.filter((c) => c.status === 'ARCHIVED'));

/* DP-1 原型 sessMenu/arcbar：⋯ 菜单（重命名/置顶/归档二次确认）+ 归档折叠条 */
const sessMenuId = ref<number | string | null>(null);
const arcArmed = ref(false);
const arcOpen = ref(false);
const renamingId = ref<number | string | null>(null);
const renameVal = ref('');

function openSessMenu(c: FpConv) {
  // 自查回修：临时行不可操作（假 ID 打真 API 弹假错误 + 僵尸置顶）
  if (c.id === 'tmp') {
    return;
  }
  sessMenuId.value = sessMenuId.value === c.id ? null : c.id;
  arcArmed.value = false;
}
watch(sessMenuId, (id) => {
  if (!id) {
    arcArmed.value = false;
    return;
  }
  // 原型 sessMenu 点外关闭（一次性 mousedown 监听）
  const close = (ev: MouseEvent) => {
    if (!(ev.target as HTMLElement)?.closest('.fp-item')) {
      sessMenuId.value = null;
      document.removeEventListener('mousedown', close);
    }
  };
  setTimeout(() => document.addEventListener('mousedown', close), 0);
});
function startRename(c: FpConv) {
  if (c.id === 'tmp') {
    return;
  }
  sessMenuId.value = null;
  renamingId.value = c.id;
  renameVal.value = c.title ?? '';
}
function cancelRename() {
  renamingId.value = null;
}
/** 行内重命名输入框挂载即聚焦全选（原型 sessMenu 重命名交互） */
function fpRenameFocus(el: unknown) {
  const inp = el as HTMLInputElement | null;
  if (inp) {
    inp.focus();
    inp.select();
  }
}
async function commitRename(c: FpConv) {
  if (renamingId.value !== c.id) {
    return;
  }
  const t = renameVal.value.trim();
  renamingId.value = null;
  if (!t || t === (c.title ?? '')) {
    return;
  }
  try {
    await renameConversation(c.id, t);
    c.title = t;
    pushToast('已重命名', 'ok');
  } catch {
    pushToast('重命名失败，稍后再试', 'err');
  }
}
function togglePin(c: FpConv) {
  if (c.id === 'tmp') {
    return;
  }
  sessMenuId.value = null;
  const key = String(c.id);
  const has = pins.value.includes(key);
  pins.value = has ? pins.value.filter((p) => p !== key) : [...pins.value, key];
  localStorage.setItem(PIN_KEY, JSON.stringify(pins.value));
  pushToast(has ? '已取消置顶' : '已置顶', 'ok');
}
async function archiveConv(c: FpConv) {
  if (c.id === 'tmp') {
    return;
  }
  if (!arcArmed.value) {
    arcArmed.value = true; // 原型归档二次确认（再点一次确认归档）
    return;
  }
  sessMenuId.value = null;
  arcArmed.value = false;
  try {
    await archiveConversation(c.id);
    pushToast('已归档——底部「已归档」可随时恢复', 'ok');
    if (c.threadId === state.threadId) {
      const next = fpConvs.value.find((x) => x.status !== 'ARCHIVED' && x.id !== c.id);
      if (next) {
        await onFpOpen(next);
      } else {
        onFpNewSession();
      }
    }
    await loadFpConvs();
  } catch {
    pushToast('归档失败，稍后再试', 'err');
  }
}
async function restoreConv(c: FpConv) {
  try {
    await restoreConversation(c.id);
    pushToast('已恢复到会话列表', 'ok');
    await loadFpConvs();
  } catch {
    pushToast('恢复失败，稍后再试', 'err');
  }
}

/** 批A Q12（拍板 2026-10-11）：时间阶梯收敛到共用 relTime——无时区串按北京墙钟(+08:00)
 *  解析，修非中国时区浏览器负差恒「刚刚」；并补「X 分钟前」档（原版 60s 后直接跳 HH:mm） */
function fpDayTime(t?: string): string {
  return relTime(t);
}
/** 批A 审查回修 P1-1：活跃绿点与 relTime 共用 cnEpoch 解析（北京墙钟 +08:00）——
 *  修前 new Date(t) 本地解析在西/东半球时区浏览器上绿点分别误亮/永不亮 */
function fpActive(c: { lastMessageTime?: string }): boolean {
  const ms = cnEpoch(c.lastMessageTime);
  return ms !== null && Date.now() - ms < 30 * 60_000;
}

/** 批D Q11-A：抽屉侧栏行首状态槽（与会话页 rowStatus 同口径单槽互斥）。
 *  answering 只认生产相位（thinking/acting/writing）——done/error/waiting 时 sending
 *  可能尚未翻 false（收尾落账窗口），照转就是 lobehub masked-tail「内容停了圈还转」 */
function fpRowStatus(c: { threadId?: string; running?: boolean }): 'answering' | 'error' | 'ok' {
  if (state.sending && c.threadId === state.threadId) {
    const last = state.turns[state.turns.length - 1];
    if (last && (last.phase === 'thinking' || last.phase === 'acting' || last.phase === 'writing')) {
      return 'answering';
    }
  }
  if (c.threadId === state.threadId && state.turns[state.turns.length - 1]?.phase === 'error') {
    return 'error';
  }
  // 业务批4 Q4：远端在途（run 表 RUNNING 回填）——刷新/重开后本窗无 sending 内存态，
  // 侧栏转圈改吃接口标志；本地 error 优先级更高（真错压过远端迟到的 running）
  if (c.running) {
    return 'answering';
  }
  return 'ok';
}

/** 打开全屏：拉会话列表+今日概况（原型 openFull 两件数据源） */
watch(full, (f) => {
  if (!f) {
    return;
  }
  loadFpConvs();
  loadSnap();
});
/** 分页（AU-19）：此前单页 50 弃 total，超 50 条的旧会话不可达；尾部「加载更多」追加 */
const fpPageNum = ref(1);
const fpTotal = ref(0);
/** 业务批(1015) Q15：当前会话行保底——服务端首轮 run 才落库，"新会话还没发第一句"时
 *  列表里没有它（须刷新才见的根因）；合成行按 threadId 与真实行去重，真实行到达即接管。
 *  自查回修(dspro P2)：补 lastMessageTime（北京墙钟）——分组/时间显示都吃它；行内操作
 *  (打开/重命名/置顶/归档)对临时行全拦（假 ID 打真 API 只会弹假错误 + 僵尸置顶） */
function ensureCurRow(list: FpConv[]): FpConv[] {
  if (!state.threadId || list.some((c) => c.threadId === state.threadId)) {
    return list;
  }
  const nowWall = new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 19).replace('T', ' ');
  return [{ id: 'tmp', threadId: state.threadId, title: '新会话', lastMessageTime: nowWall, status: 'ACTIVE', running: state.sending }, ...list];
}
/** 真实会话数（不含临时行）：加载更多计数/可见性用它，防 +1 挤掉第 51 条 */
const fpRealCount = computed(() => fpConvs.value.filter((c) => c.id !== 'tmp').length);
async function loadFpConvs(silent = false) {
  try {
    // DP-1 拉 ALL：活跃+归档一次到位（归档折叠条需要 ARCHIVED 行）
    const convs = (await listConversations({ pageNum: 1, pageSize: 50, status: 'ALL' })) as unknown as {
      rows?: FpConv[];
      total?: number;
    };
    const head = ensureCurRow(convs?.rows ?? []);
    if (silent) {
      // 自查回修(dspro P2)：答后静默补刷只换首页，已「加载更多」的尾段保留；
      // 同 id 以新数据为准，临时行一律丢弃
      const headIds = new Set(head.map((c) => c.id));
      fpConvs.value = [...head, ...fpConvs.value.filter((c) => !headIds.has(c.id) && c.id !== 'tmp')];
      if (convs?.total != null) {
        fpTotal.value = convs.total;
      }
    } else {
      fpConvs.value = head;
      fpTotal.value = convs?.total ?? head.length;
      fpPageNum.value = 1;
    }
  } catch {
    /* 列表拉不到不阻塞对话 */
  }
}
async function loadMoreFpConvs() {
  fpPageNum.value += 1;
  try {
    const convs = (await listConversations({ pageNum: fpPageNum.value, pageSize: 50, status: 'ALL' })) as unknown as {
      rows?: FpConv[];
      total?: number;
    };
    const incoming = convs?.rows ?? [];
    // 自查回修(glm53 P2)：真实行到达时先剥掉同 threadId 的临时行，防同一会话双行
    const realThreads = new Set(incoming.map((c) => c.threadId));
    const base = fpConvs.value.filter((c) => !(c.id === 'tmp' && realThreads.has(c.threadId)));
    const map = new Map(base.map((c) => [c.id, c]));
    for (const c of incoming) {
      map.set(c.id, c);
    }
    fpConvs.value = [...map.values()];
    if (convs?.total != null) {
      fpTotal.value = convs.total;
    }
  } catch {
    fpPageNum.value -= 1;
  }
}
function onFpNewSession() {
  if (state.sending) {
    stop(true);
  }
  newSession();
  // 业务批(1015) Q15：新会话即时置顶（列表已加载过才插——普通抽屉形态无侧栏，开全屏时 loadFpConvs 兜底）
  if (fpConvs.value.length || full.value) {
    fpConvs.value = ensureCurRow(fpConvs.value);
  }
}
/** 连点序号守卫（k3 P2-2 回修，会话页批H openSeq 同款）：连点两个会话时
 * 双 listMessages 并发在途，晚 resolve 的旧会话不得覆盖先选中的新会话 */
let fpOpenSeq = 0;
async function onFpOpen(c: { id: number | string; threadId: string; status?: string; running?: boolean }) {
  // 自查回修：临时行=当前会话本身，点它无需加载（'tmp' 打 messages 接口必失败，
  // 且下方 stop(true) 会白掐一次在途生成）
  if (c.id === 'tmp') {
    return;
  }
  // 业务批10 #2 P1：点开"正在生成的当前会话"不掐流（同会话页 openConversation 口径）——
  // 全屏侧栏与消息区共享单例 state，现场已在播，重开只做无操作
  if (state.sending && state.threadId === c.threadId) {
    return;
  }
  if (state.sending) {
    stop(true);
  }
  const mySeq = ++fpOpenSeq;
  // 单例归档闸（AU-36）：归档行可点开回放，但发送被拦。注意 rebuildFromHistory 开头
  // resetForNewSession 会清位——必须在 rebuild 之后再置，否则闸永远失效（E2E 实锤）
  const archived = c.status === 'ARCHIVED';
  try {
    const msgs = (await listMessages(c.id, { limit: 100 })) as unknown as {
      data?: Array<{ role: string; content: string; totalTokens?: number; endReason?: string | null }>;
    };
    if (mySeq !== fpOpenSeq) {
      return;
    }
    const list = msgs?.data ?? [];
    if (list.length > 0) {
      rebuildFromHistory(list, c.threadId);
    } else {
      // 空会话保 threadId（AU-16）：newSession 会换新 uuid，左侧行与消息落库分家
      rebuildFromHistory([], c.threadId);
    }
    state.curConvArchived = archived;
    // ZCode 对齐 Q2（批B）：恢复该会话的任务清单（StatPanel TODO 分区）
    loadTodo(c.threadId);
    // 业务批4 Q4：远端在途轮恢复——run 表 RUNNING 时尾部挂「生成中」占位（复用流式
    // 思考态视觉）+3s 轮询收尾（run 终态后重放一次把生成结果接上；占位随重放消散）
    if (c.running) {
      watchRemoteRun(c);
    }
  } catch {
    pushToast('会话加载失败，稍后再试', 'err');
  }
}

/**
 * 业务批4 Q4：在途轮恢复轮询。占位=尾部 user 回合置 thinking 态（无文字）——
 * MsgFlow 渲染与真生成一致（转圈视觉；不真流式，数据仍由重放收敛）。
 * 收尾守卫三连：m3 P1-5=本地在飞不重建（擦在飞回合）；dspro P1=每个 await 之后
 * 复核代数（fpOpenSeq）+会话指针+占位回合仍是尾巴（切会话/发新问题后旧 tick 不得覆盖）。
 * 连 60 次（约 3 分钟）防真悬挂时无限轮询，超限把占位落回 done 给出路——后端
 * closeStuck job 每分钟扫描、15 分钟阈值兜底迁移，窗口内切走再切回即恢复。
 */
const remoteWatchers = new Map<string, ReturnType<typeof setInterval>>();
function watchRemoteRun(c: { id: number | string; threadId: string; status?: string; running?: boolean }) {
  // dspro P2：同会话重复打开先撤旧轮询器防叠加（threadId 无变化时旧 interval 不自停）
  const old = remoteWatchers.get(c.threadId);
  if (old) {
    globalThis.clearInterval(old);
    remoteWatchers.delete(c.threadId);
    releaseRemoteWatch(c.threadId);
  }
  // 批6-#2 回修（m3 P0-2）：会话页已盯同 threadId 时抽屉不再挂第二份（双发轮询+
  // 双份重建互相顶替）；对方收敛重建的是共享 turns，本组件响应式自动跟随
  if (!tryAcquireRemoteWatch(c.threadId)) {
    return;
  }
  const placeholder = state.turns[state.turns.length - 1] ?? null;
  if (placeholder && placeholder.phase === 'done' && !placeholder.bufText) {
    placeholder.phase = 'thinking';
  }
  const mySeq = fpOpenSeq;
  let ticks = 0;
  // step5 FE-5：慢网在途守卫——上一轮请求未 resolve 时本轮直接跳过（防重复并发+重复写
  // 侧栏 running；await 后已有身份/代数守卫，此处只堵重入）
  let inFlight = false;
  const timer = globalThis.setInterval(async () => {
    ticks += 1;
    if (ticks > 60 || state.threadId !== c.threadId || inFlight) {
      if (ticks > 60 || state.threadId !== c.threadId) {
        globalThis.clearInterval(timer);
        remoteWatchers.delete(c.threadId);
        releaseRemoteWatch(c.threadId);
        // 业务批10 #2 P2：超限即熄侧栏行级转圈（同会话页口径，防 run 卡 RUNNING 永转）
        if (ticks > 60) {
          const mine = fpConvs.value.find((x) => x.threadId === c.threadId);
          if (mine) {
            mine.running = false;
          }
        }
        // dspro P2：超限不复位=永久转圈且无停钮（sending 未置位）——落回 done 并给人话出路
        if (
          ticks > 60 &&
          state.threadId === c.threadId &&
          state.turns[state.turns.length - 1] === placeholder &&
          placeholder &&
          placeholder.phase === 'thinking'
        ) {
          placeholder.phase = 'done';
          pushToast('生成时间过长，可能已中断；稍后重新打开该会话即可看到已生成的结果', 'info');
        }
      }
      return;
    }
    inFlight = true;
    try {
      const convs = (await listConversations({ pageNum: 1, pageSize: 50, status: 'ALL' })) as unknown as {
        rows?: FpConv[];
      };
      const row = (convs?.rows ?? []).find((x) => x.threadId === c.threadId);
      // 同步侧栏转圈（列表行远端标志随轮询刷新）
      const mine = fpConvs.value.find((x) => x.threadId === c.threadId);
      if (mine && row) {
        mine.running = row.running;
      }
      if (!row?.running) {
        globalThis.clearInterval(timer);
        remoteWatchers.delete(c.threadId);
        releaseRemoteWatch(c.threadId);
        // m3 P1-5 回修：轮询期间用户发了新问题（sending=本地在飞）——重建会擦掉在飞回合
        //（追问早期 thinking+空 bufText 恰与占位同签名），本地收尾自会收敛，远端结果下次重放可见
        if (state.sending) {
          return;
        }
        // dspro P1 回修：占位必须仍是尾巴且代数未变（防 await 让出线程期间切会话被旧 tick 覆盖）
        if (
          !placeholder ||
          state.turns[state.turns.length - 1] !== placeholder ||
          mySeq !== fpOpenSeq ||
          state.threadId !== c.threadId ||
          placeholder.phase !== 'thinking'
        ) {
          return;
        }
        const msgs = (await listMessages(c.id, { limit: 100 })) as unknown as {
          data?: Array<{ role: string; content: string; totalTokens?: number; endReason?: string | null }>;
        };
        // await 之后环境可能又变，二次复核后才允许重建
        if (mySeq !== fpOpenSeq || state.threadId !== c.threadId || state.turns[state.turns.length - 1] !== placeholder) {
          return;
        }
        const list = msgs?.data ?? [];
        if (list.length > 0) {
          rebuildFromHistory(list, c.threadId);
        } else {
          placeholder.phase = 'done';
        }
        state.curConvArchived = c.status === 'ARCHIVED';
      }
    } catch {
      /* 网络抖动下轮再试；60 次上限兜底 */
    } finally {
      inFlight = false;
    }
  }, 3000);
  remoteWatchers.set(c.threadId, timer);
}

/** 今日概况 snaprow（仅全屏；admin=待审核口径，其余=待办口径；「已催办/自己逾期」无现成字段→以「待修改」替换并计划留痕） */
const snapItems = ref<SnapItem[]>([]);
const LAST_KEY = 'dcs:assistant:lastUse';
async function loadSnap() {
  const lastRaw = localStorage.getItem(LAST_KEY);
  const last = lastRaw ? new Date(lastRaw) : null;
  const lastTxt = last && !Number.isNaN(last.getTime()) ? last.toTimeString().slice(0, 5) : '—';
  try {
    // 批5 契约迁移: 旧 todo 摘要退役, 改用微核心双视角统计
    const s = await statsOverview().catch(() => null);
    const si = (s as any)?.student ?? {};
    const ss = { fillCount: si.todo ?? 0, reviewCount: si.pendingAudit ?? 0, reviseCount: si.rejected ?? 0, doneCount: (si.approved ?? 0) + (si.submitted ?? 0) };
    const todo = (ss.fillCount ?? 0) + (ss.reviewCount ?? 0);
    snapItems.value =
      roleOf(userStore.roles, userStore.permissions) === 'admin'
        ? [
            { icon: 'receipt', label: '待审核', value: ss.reviewCount ?? 0 },
            { icon: 'clipboard', label: '待修改', value: ss.reviseCount ?? 0 },
            { icon: 'history', label: '上次', value: lastTxt }
          ]
        : [
            { icon: 'clock', label: '待办', value: todo },
            // 成员/发布人口径用 overdueCount（本人逾期），publishedOverdueCount 是发布人视角（批H dspro P1-9）
            { icon: 'clipboard', label: '待修改', value: ss.reviseCount ?? 0 },
            { icon: 'history', label: '上次', value: lastTxt }
          ];
  } catch {
    snapItems.value = [];
  }
}

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

/** Q2（20261011 拍板A）：todo 清单行跳转目标——清单是会话级而步骤分属各回合，
 *  跨回合取最近一次 todo_write 步骤 id；无（回放/未写过清单）=面板行不可点。
 *  批1审查 P2：读 state 的 computed 必须挂在解构之后（防 future immediate 消费 TDZ） */
const todoJumpId = computed(
  () =>
    state.turns
      .flatMap((t) => t.steps)
      .filter((s) => s.name === 'todo_write')
      .at(-1)?.id ?? null
);

// R4 二改补：当前会话落在「更早」组时自动展开折叠条（挂 getAssistant 之后——
// watch getter 在 setup 即求值，放解构前会 TDZ ReferenceError，HMR 白屏）
watch(
  () => [state.threadId, fpOlder.value] as const,
  ([tid, og]) => {
    if (og && og.items.some((c) => c.threadId === tid)) {
      fpOlderOpen.value = true;
    }
  }
);

/** 须知门（AU-17 派生态）：单例 gatePendQ 非空且未同意即弹——双宿主不再各持本地 ref
 * （旧路径：会话页 OK 后本页门卡滞留 / 打开有消息会话静默置 gateDone 后误补发 pendQ） */
const showGate = computed(() => !state.gateDone && !!state.gatePendQ);

// 单例多宿主绑定（AU-37）：仅抽屉可见时入栈——抽屉常驻但隐身时若占住栈顶，
// 会话页（后挂载/后打开）的草稿会被 getDraft 归到看不见的抽屉 composer 上；
// 关抽屉弹栈归还下一宿主，onUnmounted 兜底弹掉未还的
let hostBound = false;
/** H8：持有绑定引用——unbind 按身份弹，卸载序与挂载序不一致时不再误弹别人的 */
let hostBinding: AssistantBindings | null = null;
function bindHost() {
  if (hostBound) {
    return;
  }
  hostBinding = {
    onToast: (t) => pushToast(t),
    onAuthFailure: () => {
      // 401 双形态：aguiClient 已识别，全局登录弹窗由 request 层/路由守卫处理，此处仅降 banner
      ElMessage.warning('登录已过期，请重新登录后再使用助手');
    },
    // composerRef 是模板 ref 对象——必须 .value 取组件实例（写成 composerRef?.() 调用会 TypeError，
    // 且炸点在 consumeQueue 的 shift 之前=队列补发链整体瘫痪，E2E 实证）
    getDraft: () => composerRef.value?.draftText ?? '',
    clearDraft: () => composerRef.value?.clearDraft()
  };
  getAssistant(userStore.userId).bind(hostBinding);
  hostBound = true;
}
function unbindHost() {
  if (!hostBound) {
    return;
  }
  getAssistant(userStore.userId).unbind(hostBinding ?? undefined);
  hostBinding = null;
  hostBound = false;
}
watch(modelValue, (open) => (open ? bindHost() : unbindHost()), { immediate: true });

/** 滚动跟随：距底<48px 才自动跟随（followTail 口径） */
const follow = ref(true);
let flowEl: HTMLElement | null = null;
function onScrollLock(el: HTMLElement | null) {
  if (flowEl) {
    flowEl.removeEventListener('scroll', onScrollEvt);
  }
  flowEl = el;
  flowEl?.addEventListener('scroll', onScrollEvt, { passive: true });
}
function onScrollEvt() {
  if (!flowEl) {
    return;
  }
  follow.value = flowEl.scrollHeight - flowEl.scrollTop - flowEl.clientHeight < 48;
}

/** 横幅多态数据（批E）：开抽屉探测额度/降级；发问即视为用户已知晓，横幅让位 */
const quotaLimit = ref(50);

/** 批4 Q11：管理员公告横幅——开抽屉拉最新一条生效公告；用户点「知道了」记版本号本地关闭。
 *  三审回修两处：①键按用户隔离（机房共用浏览器 A 关 B 不受牵连，与 thinkLevel 键同惯例）；
 *  ②dismiss 记 `${id}|${publishTime}` 而非裸 id——管理端「编辑重发」刷 publishTime 后已关闭
 *  旧版的用户自然重见（与页面承诺一致；裸 id 会让最需要触达的维护更新人群永远看不到）。 */
const annDismissKey = computed(() => `dcs:assistant:annDismissed:${userStore.userId}`);
const ann = ref<{ token: string; title: string; content: string; style: string } | null>(null);
const annDismissedToken = ref('');
function dismissAnn() {
  if (ann.value) {
    annDismissedToken.value = ann.value.token;
    localStorage.setItem(annDismissKey.value, ann.value.token);
  }
}
async function loadAnnouncement() {
  try {
    const d = unwrap<{ id?: string; title?: string; style?: string; content?: string; publishTime?: string }>(await getAssistantAnnouncementActive());
    ann.value =
      d?.id && d.title
        ? {
            token: `${d.id}|${d.publishTime ?? ''}`,
            title: String(d.title),
            content: d.content ?? '',
            style: d.style ?? 'NORMAL'
          }
        : null;
  } catch {
    ann.value = null; // 公告不可达不打扰用户
  }
  // dismiss 键随登录用户变（换账号共用浏览器场景），每次开抽屉重读一次
  annDismissedToken.value = localStorage.getItem(annDismissKey.value) ?? '';
}

watch(modelValue, async (open) => {
  if (!open) {
    return;
  }
  // 打开移焦（原型 openDrawer ta.focus）：抽屉在场即落输入框
  nextTick(() => composerRef.value?.focus());
  loadAnnouncement();
  try {
    const res = (await getAssistantStatus()) as unknown as {
      data?: { dailyRoundLimitPerUser?: number; turnsUsedToday?: number; degraded?: boolean; quotaDisabled?: boolean };
    };
    const d = unwrap<Record<string, unknown>>(res);
    if (d?.dailyRoundLimitPerUser) {
      quotaLimit.value = d.dailyRoundLimitPerUser;
    }
    // R1：用户级禁用闸最高优先（批③ /status.quotaDisabled 此前零消费）——admin 在
    // 用户额度页签停用后，被禁者打开抽屉即见红横幅+输入置灰，不再无提示无反馈；
    // 复权清除主链在 Composer.refreshStatus（三宿主统一），此处 else 兜底同清
    if (d?.quotaDisabled) {
      state.banner = 'disabled';
    } else if (d?.degraded) {
      state.banner = 'degraded';
    } else if ((d?.turnsUsedToday ?? 0) >= quotaLimit.value) {
      state.banner = 'quota';
    } else if (state.banner === 'disabled') {
      state.banner = '';
    }
  } catch {
    /* 状态不可达不打扰 */
  }
});
function onSend(q: string, stamp: string, fwd: Record<string, unknown> = {}) {
  // 归档会话只读回放（AU-36 补齐会话页同款拦截；后端 ensureConversation 403 双保险）
  if (state.curConvArchived) {
    pushToast('该会话已归档（只读回放），请先在「已归档」里恢复再发消息', 'info');
    return;
  }
  // R1：被禁用户发送拦截（横幅不清除——禁用是持续态，发问不算"已知晓让位"；
  //  队列/确认卡深层路径由 useAssistant.assertUsable 统一收口）
  if (state.banner === 'disabled') {
    pushToast('管理员已暂停你的 AI 助手使用，如有疑问请联系管理员', 'err');
    return;
  }
  follow.value = true;
  if (state.banner === 'quota' || state.banner === 'degraded') {
    state.banner = '';
  }
  if (!state.gateDone) {
    state.gatePendQ = q; // 须知门派生态：gatePendQ 非空即弹（AU-17）
    return;
  }
  send(q, stamp, fwd);
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
  const text = queueEdit(i, composerRef.value?.draftText ?? '');
  composerRef.value?.setDraft(text);
  composerRef.value?.focus();
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
/** BN-14 原型 helpDemo：关说明+按当前页面/身份发建议首问（跟我走一遍） */
function onHelpDemo() {
  help.hide();
  const first = suggestionsFor(pageName.value, roleKey.value)[0];
  onSend(first?.[3] ?? '我的待办', '');
}
/** 分支（原型 branchHere）：该回合为止的前文复制进新会话，原会话保留 */
function onBranch(turn: AssistantTurn) {
  if (state.sending) {
    pushToast('等这轮回答完再分支', 'ok');
    return;
  }
  branchFrom(turn);
  composerRef.value?.focus();
}
function onGateOk() {
  gateOk();
  // 焦点归还（批G 无障碍：同意须知后直接落输入框，继续刚才的问题）
  composerRef.value?.focus();
}
function retryLast() {
  state.banner = '';
  const t = cur();
  if (t) {
    follow.value = true;
    send(t.question);
  }
}
function close() {
  // Q6 原型口径：关抽屉=收起不打断（stash 语义）——生成继续跑，重开抽屉接着看；停止只走停止钮/Esc
  // 全屏形态点遮罩收起必须连同退出全屏（批H P1-4）：.full 的 right:0/width:100%
  // 不依赖 .open——只关 mask 会留一块卡死的全屏面板
  full.value = false;
  modelValue.value = false;
}

/** 页面联动（原型 pageHighlight 降级版）：数据卡行→收抽屉→跳任务详情页+toast（高亮依赖业务页 DOM，降级为直达路由） */
function onPageGoto(p: { missionId: string; missionName: string }) {
  close();
  pushToast(`已为你打开任务「${p.missionName.slice(0, 14)}」`);
  router.push(`/dcs/mission/detail/${p.missionId}`);
}

/** 批3 Q6 站内链/任务卡导航（回修 P1-2）：收抽屉再跳转——直接 push 会把目标页压在抽屉遮罩后面 */
function onMdNav(url: string) {
  close();
  router.push(url);
}

/** 选区条落输入框：追加=接在现有草稿后；覆盖=填入选区聚焦待编辑 */
function onDraftSet(text: string, append: boolean) {
  const cur = append ? (composerRef.value?.draftText ?? '') : '';
  composerRef.value?.setDraft(cur ? cur + '\n' + text : text);
  composerRef.value?.focus();
}

/** 计划卡取消：只收卡不动对话（cur() 指向单例 state 内的 reactive 回合） */
function onPlanCancel() {
  const t = cur();
  if (t) {
    t.plan = null;
  }
}

/** Esc 分层（原型语义）：帮助弹层→须知门→流式中停止（不关面板）→停后收起；Alt+N=新建会话 */
function onKeydown(e: KeyboardEvent) {
  if (e.altKey && !e.ctrlKey && !e.metaKey && (e.key === 'n' || e.key === 'N')) {
    e.preventDefault();
    if (!modelValue.value && !full.value) {
      modelValue.value = true;
      return;
    }
    newSession();
    return;
  }
  if (e.key !== 'Escape' || (!modelValue.value && !full.value)) {
    return;
  }
  // 批W2（SPEC §2.1 可达性）：Esc 链最前置=有 pending 确认卡——写操作未决时
  // 不收面板不停止，先提示去处理（卡容器已 tabindex=-1 可被 Esc 聚焦链覆盖）
  if (state.turns.some((t) => t.hitl?.cardState === 'pending')) {
    pushToast('还有一条确认未处理：请先在对话里选择确认或取消', 'info');
    return;
  }
  if (help.open.value) {
    help.hide();
    return;
  }
  // AU-29 Esc 链：任务 chip 菜单/会话 ⋯ 菜单 → 输入区三个弹层（➕/自主权/思考档）→ 须知门 → 停止 → 收起
  if (taskMenuOpen.value) {
    taskMenuOpen.value = false;
    return;
  }
  if (sessMenuId.value) {
    sessMenuId.value = null;
    return;
  }
  if (composerRef.value?.escClosePop()) {
    return;
  }
  if (showGate.value) {
    return;
  }
  if (state.sending) {
    onStop();
  } else if (full.value) {
    full.value = false;
  } else {
    modelValue.value = false;
  }
}

/** E5 会话重建：挂载即按持久 threadId 找会话拉历史回放（找不到=新会话从欢迎卡开始） */
let rebuilt = false;
async function rebuildSession() {
  if (rebuilt || state.turns.length > 0) {
    return;
  }
  rebuilt = true;
  try {
    const convs = (await listConversations({ pageNum: 1, pageSize: 50 })) as unknown as {
      rows?: Array<{ id: number | string; threadId: string; status?: string; running?: boolean }>;
    };
    const conv = (convs?.rows ?? []).find((c) => c.threadId === state.threadId);
    if (!conv) {
      return;
    }
    const msgs = (await listMessages(conv.id, { limit: 100 })) as unknown as {
      data?: Array<{ role: string; content: string; totalTokens?: number; endReason?: string | null }>;
    };
    const list = msgs?.data ?? [];
    if (list.length > 0) {
      rebuildFromHistory(list);
      // k3 P2-1 回修：刷新/重进恢复路径也要拉任务清单——漏接时 F5 后步骤恢复了、
      // 清单分区静默消失（onFpOpen/openConversation 都接了，独漏这条最高频路径）
      loadTodo(state.threadId);
      // 断线恢复（G3 账本口径）：该 thread 有 EXECUTING 遗留=上次回答被中断、操作结果未知。
      // 真确认卡恢复需要 interruptId（threadId:toolCallId），action 表无 toolCallId 列——后端补列前降级为提示（计划留痕）。
      try {
        const acts = (await listActions({ threadId: state.threadId })) as unknown as { data?: Array<{ status: string }> };
        const pending = (acts?.data ?? []).filter((a) => a.status === 'EXECUTING').length;
        if (pending > 0) {
          pushToast(`上次有 ${pending} 个操作结果未知（连接中断），账本已留痕；需要的话请重新发起`);
        }
      } catch {
        /* 账本查不到不影响回放 */
      }
      // 批6-#2：刷新恢复路径也要接在途轮——run 仍 RUNNING（断连拆链后由后端失联收割
      // ≤40s+sweep 收尾）时挂同款 3s 轮询，收割落库后自动重放补上终态与中断徽标
      if (conv.running) {
        watchRemoteRun(conv);
      }
    }
  } catch {
    // 后端不可达等：静默走新会话（不打断首问）
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown);
  rebuildSession();
});

/** 批17 Q2：手动压缩完成后重拉当前会话消息——「已压缩」分隔条随历史重建出现。
 *  排队队列非空时跳过（rebuild 会清队，压缩不该吞掉还没发的问题）。 */
async function reloadCurrentConv() {
  if (state.queue.length > 0 || state.sending) {
    return;
  }
  try {
    const convs = (await listConversations({ pageNum: 1, pageSize: 50 })) as unknown as {
      rows?: Array<{ id: number | string; threadId: string }>;
    };
    const conv = (convs?.rows ?? []).find((c) => c.threadId === state.threadId);
    if (!conv) {
      return;
    }
    const msgs = (await listMessages(conv.id, { limit: 100 })) as unknown as {
      data?: Array<{ role: string; content: string; totalTokens?: number; endReason?: string | null }>;
    };
    rebuildFromHistory((msgs?.data ?? []) as Parameters<typeof rebuildFromHistory>[0], state.threadId);
  } catch {
    /* 拉不到就算了——分隔条在下次打开会话时自然出现 */
  }
}
/** 业务批(1015) Q15：答完递进重拉会话列表（3.5/5.5/9s，与追问侧车同款时序）——
 *  会话行虽在首轮 run 前已落库，但标题侧车几秒后才改写短标题，单次刷新常读旧值；
 *  全屏形态才刷（普通抽屉无侧栏，展开时 loadFpConvs 自会取新）。重复触发先清旧防叠加 */
let convRefreshTimers: ReturnType<typeof setTimeout>[] = [];
function scheduleConvRefresh() {
  convRefreshTimers.forEach((t) => clearTimeout(t));
  convRefreshTimers = [3500, 5500, 9000].map((ms) =>
    setTimeout(() => {
      if (mounted.value && full.value) {
        void loadFpConvs(true);
      }
    }, ms)
  );
}
watch(
  () => state.sending,
  (v, old) => {
    if (old && !v && full.value) {
      scheduleConvRefresh();
    }
  }
);

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown);
  // dspro P2：远端轮询器随组件卸载回收（登出后带新 token 空跑+写孤儿状态的确定性泄漏）
  remoteWatchers.forEach((t, tid) => {
    globalThis.clearInterval(t);
    releaseRemoteWatch(tid);
  });
  remoteWatchers.clear();
  // 业务批(1015) Q15：答后列表补刷定时器随卸载回收（登出后回调 401 幽灵框，同 ringRefetchTimer 族）
  convRefreshTimers.forEach((t) => clearTimeout(t));
  convRefreshTimers = [];
  // 单例 store 不随抽屉卸载销毁（收起面板回答继续收敛；登出硬销毁由 index.vue disposeAssistant 负责）
  unbindHost();
  mounted.value = false;
});

defineExpose({ state, ask: (q: string) => onSend(q) });
</script>

<style lang="scss" scoped>
.dcs-dw-mask {
  position: fixed;
  inset: 0;
  z-index: 950;
  background: rgb(0 0 0 / 32%);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.28s;

  &.open {
    opacity: 1;
    pointer-events: auto;
  }
}
.dcs-dw {
  position: fixed;
  top: 0;
  right: -404px;
  bottom: 0;
  width: 384px;
  z-index: 960;
  display: flex;
  flex-direction: column;
  /* AU-30 小屏兜底：视口 <420px 时 384px 定宽会左右溢出（球在右缘点不开/内容裁切） */
  @media (max-width: 420px) {
    /* 业务反馈批1015b Q9：两侧留缝 16px→32px——小屏点外边收起更好命中（每侧 16px 可点） */
    width: calc(100vw - 32px);
    right: calc(-100vw);
  }
  /* 全局 aside{line-height:32px;font-size:16px}（index.scss，文档页样式）被抽屉根 aside 吃到
     并继承放大所有未显式设行高的行——＋面板每行 48px/面板 537px 的根因，这里整树重置 */
  line-height: 1.5;
  font-size: 14px;
  /* J-2 原型 bg2：灰画布，白头/白气泡/白输入盒在其上分层 */
  background: #f7f8fa;
  box-shadow: -6px 0 24px rgb(0 0 0 / 16%);
  transition: right 0.28s cubic-bezier(0.2, 0.8, 0.3, 1);

  &.open {
    right: 0;
  }
  /* 全屏形态（屏D：页头横排 + .fp-wrap 两栏；批A 过渡版升级） */
  &.full {
    right: 0;
    width: 100%;
    max-width: 1280px;
    margin: 0 auto;
    /* 全屏覆盖语义：须压过系统侧栏（sidebar z=1001），否则左栏会话列表/⋯ 菜单左半被遮（视觉复核实锤） */
    z-index: 1200;
    /* UI 专项批1：max-width 1280 居中时视口两侧各露 ~14px 底层页面（半截菜单图标，视觉评审 ui3 实锤）——
       100vmax spread 阴影当暗幕盖住外围，同时保留居中栏的浮起投影 */
    box-shadow:
      0 0 0 100vmax rgb(0 0 0 / 24%),
      -8px 0 32px rgb(0 0 0 / 20%);

    /* hero 限宽 640（原型屏D 主区排版）；建议卡全屏 2 列（抽屉单列行由 HeroWelcome 默认态）。
       宽屏加宽批 P2 回修（同 session 页）：帽+auto 从 .dcs-hero 容器层下移到 .hero-card
       卡片层——容器吃列几何（.dcs-flow.full > * 同列帽/贴左），卡片在列内居中，
       与输入框同中轴（原容器层 auto margin 压过 flex-start，卡片对着整个主区居中错位） */
    :deep(.dcs-hero) {
      .sug {
        grid-template-columns: 1fr 1fr;
      }
    }
    :deep(.dcs-hero .hero-card) {
      width: 100%;
      max-width: 640px;
      margin-inline: auto;
    }
  }

  /* 业务批4 Q6：小屏全屏留缝——视口 <768px 时全屏不再顶满（100vw 满宽在 375px
     手机上两侧贴死），与抽屉 420px 兜底同款 calc(100vw - 16px) 居中留缝语义 */
  @media (max-width: 768px) {
    &.full {
      width: calc(100vw - 16px);
      max-width: none;
      border-radius: 12px;
    }
  }
}
/* 两栏骨架：左会话栏 + 右主区（全屏）；非全屏时 fp-l 不渲染、dw-body 自然占满 */
.fp-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
}
.dw-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  /* Q8/Q9/三反馈② 廊位：StatPanel 按容器宽解算写 --sp-strip（抽屉窄形态无面板=不写，
     走 0 兜底）；StatPanel 为 absolute 浮层不吃本层 padding，正文列与聊天框一起让、一起回 */
  padding-right: var(--sp-strip, 0px);
  transition: padding-right 0.3s ease;
}
/* 业务批4 Q2：任务上下文条（紧贴输入框上方）——顶层选择器（原块嵌在 .hd-txt 里是头部
   chip 时代的残留，Q2 模板搬走后 .dw-head .hd-txt .ctx-bar 永假=整块样式静默失效，
   U7 视觉席"控件落第二行/条无样式"实根因；scoped 属性隔离下顶层无串扰） */
.ctx-bar {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 0 0 6px;
  padding: 3px 4px 3px 10px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 12.5px;
  line-height: 20px;

  .cb-main {
    /* 视觉席 U7 P3：占满条宽参与收缩，名按省略号收敛——控件钉住不换行 */
    flex: 1 1 auto;
    display: inline-flex;
    align-items: center;
    gap: 5px;
    min-width: 0;
    cursor: pointer;
  }
  .cb-name {
    flex: 0 1 auto;
    min-width: 0;
    max-width: 240px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: 500;
  }
  .cb-chev {
    flex: none;
    transition: transform 0.15s;

    &.open {
      transform: rotate(180deg);
    }
  }
  .cb-x {
    flex: none;
    margin-left: auto;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    border: none;
    border-radius: 50%;
    background: transparent;
    color: var(--dcs-text-secondary);
    cursor: pointer;

    &:hover {
      background: var(--el-fill-color);
      color: var(--dcs-text-primary);
    }
  }

  .tmenu {
    position: absolute;
    top: 26px;
    left: 0;
    z-index: 10;
    display: flex;
    flex-direction: column;
    min-width: 168px;
    padding: 4px;
    background: var(--el-bg-color-overlay);
    border: 1px solid var(--dcs-border-color-lighter);
    border-radius: 8px;
    box-shadow: var(--el-box-shadow-light);

    button {
      border: none;
      background: none;
      text-align: left;
      padding: 6px 8px;
      font-size: 12.5px;
      color: var(--dcs-text-primary);
      border-radius: 6px;
      cursor: pointer;

      &:hover {
        background: var(--el-fill-color-light);
      }
    }
  }
}

.dw-head {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 52px;
  padding: 0 12px;
  background: var(--el-bg-color);
  box-shadow: 0 1px 4px rgb(0 0 0 / 4%);
  border-bottom: 1px solid var(--dcs-border-color-lighter);
  flex: none;
  z-index: 2;

  .orb {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #fff;
    background: linear-gradient(145deg, var(--el-color-primary), var(--el-color-primary-dark-2));
    box-shadow: 0 2px 6px rgb(64 158 255 / 35%);
    flex: none;
  }
  .hd-txt {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;

    b {
      font-size: 15px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
    .ctx {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      font-size: 11px;
      color: var(--dcs-text-secondary);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  }
  /* 身份徽标（原型 .tag.t-p：浅蓝描边方角小徽） */
  .role-tag {
    flex: none;
    display: inline-flex;
    align-items: center;
    height: 24px;
    padding: 0 10px;
    border: 1px solid var(--el-color-primary-light-7);
    border-radius: 4px;
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
    font-size: 12px;
    white-space: nowrap;
  }
  .hd-btns {
    display: flex;
    flex: none;
    gap: 2px;
  }
  .ic {
    border: none;
    background: none;
    cursor: pointer;
    color: var(--dcs-text-secondary);
    /* 业务反馈批1015b Q9：热区 22×26→40×36——小屏/触屏点✕易落空（图标本身不变，只放大可点区） */
    width: 40px;
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 8px;

    &:hover {
      background: var(--el-fill-color-light);
      color: var(--el-color-primary);
    }
  }
}
.dw-banner {
  flex: none;
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 8px 14px;
  border-bottom: 1px solid #f3d19e;
  border-radius: 0;
  font-size: 12.5px;
  background: var(--dcs-color-warning-light-9, #fdf6ec);
  color: var(--dcs-text-regular);

  span {
    flex: 1;
    min-width: 0;
  }
  a {
    color: var(--el-color-primary);
    cursor: pointer;
    text-decoration: none;
    white-space: nowrap;

    &:hover {
      text-decoration: underline;
    }
  }
  &.info {
    background: var(--el-color-primary-light-9);
    border-color: var(--el-color-primary-light-7);
  }
  &.dg {
    background: var(--dcs-color-danger-light-9, #fde2e2);
    border-color: #fbc4c4;
  }
  /* 批4 Q11：公告横幅——标题加粗+正文同行收敛省略；「知道了」右对齐按钮 */
  &.ann {
    .ann-text {
      flex: 1;
      min-width: 0;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;

      b {
        margin-right: 2px;
      }
    }
    .ann-x {
      flex: none;
      border: 1px solid currentColor;
      border-radius: 10px;
      background: transparent;
      padding: 1px 10px;
      font-size: 12px;
      line-height: 18px;
      cursor: pointer;
      opacity: 0.75;

      &:hover {
        opacity: 1;
      }
    }
  }
}
/* 全屏页头条（原型屏D：← + 会话标题 + 身份徽标） */
.full-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 50px;
  padding: 0 20px;
  background: var(--el-bg-color);
  box-shadow: 0 1px 4px rgb(0 0 0 / 4%);
  border-bottom: 1px solid var(--dcs-border-color-lighter);
  flex: none;
  z-index: 2;

  @media (max-width: 768px) {
    /* 业务批4 视觉席 U2-P0 回修：375px 下 7 子项×6 gap+padding 恰好溢出 ~19px，
       全部收缩量砸在唯一可缩的标题上（"新会话"→"新…"而右侧留白）——收 gap/padding 出空间 */
    gap: 8px;
    padding: 0 12px;
  }

  .fb-back {
    border: none;
    background: none;
    cursor: pointer;
    color: var(--dcs-text-secondary);
    /* 业务反馈批1015b Q9：热区 22×26→40×36——小屏/触屏点✕易落空（图标本身不变，只放大可点区） */
    width: 40px;
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 8px;

    &:hover {
      background: var(--el-fill-color-light);
      color: var(--dcs-text-primary);
    }
  }
  .fb-title {
    min-width: 0;
    max-width: 400px;
    font-size: 16px;
    font-weight: 600;
    color: var(--dcs-text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .fb-arc {
    flex: none;
    padding: 1px 8px;
    border: 1px solid var(--dcs-color-warning-light-5, #e6a23c);
    border-radius: 4px;
    background: var(--dcs-color-warning-light-9, #fdf6ec);
    color: var(--dcs-color-warning, #e6a23c);
    font-size: 12px;
    white-space: nowrap;
  }
  .fb-sp {
    flex: 1;
  }
  .ic {
    border: none;
    background: none;
    cursor: pointer;
    color: var(--dcs-text-secondary);
    /* 业务反馈批1015b Q9：热区 22×26→40×36——小屏/触屏点✕易落空（图标本身不变，只放大可点区） */
    width: 40px;
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 8px;
    font-size: 15px;

    &:hover {
      background: var(--el-fill-color-light);
      color: var(--el-color-primary);
    }
    /* 侧栏收起态（aria-pressed=true）显激活蓝 */
    &[aria-pressed='true'] {
      color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
    }
  }
  .role-tag {
    flex: none;
    display: inline-flex;
    align-items: center;
    height: 24px;
    padding: 0 10px;
    border: 1px solid var(--el-color-primary-light-7);
    border-radius: 4px;
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
    font-size: 12px;
    white-space: nowrap;
  }
}

/* 屏D 左会话栏（原型 .fp-l） */
.fp-l {
  width: 260px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: var(--el-bg-color);
  border-right: 1px solid var(--dcs-border-color-lighter);
  padding: 10px 10px 0;

  /* 业务批4 Q6：小屏强制藏侧栏——260px 定宽在 375px 视口把主区挤成竖排碎片（B9 实锤）。
     CSS 层隐藏不打 wide 偏好：用户点了收/展仍记 localStorage，回宽屏照常生效 */
  @media (max-width: 768px) {
    display: none;
  }

  .fp-search {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    color: var(--dcs-text-secondary);
    background: var(--el-fill-color-light);
    border: 1px solid transparent;
    border-radius: 8px;
    padding: 4px 8px;
    transition:
      border-color 0.15s,
      background 0.15s;

    &:focus-within {
      background: var(--el-bg-color);
      border-color: var(--el-color-primary-light-5);
    }

    input {
      flex: 1;
      min-width: 0;
      border: none;
      outline: none;
      font-size: 12.5px;
      color: var(--dcs-text-primary);
      background: none;
    }
  }
  .fp-new {
    margin: 8px 0;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 4px;
    border: none;
    background: none;
    color: var(--dcs-text-secondary);
    border-radius: 8px;
    padding: 6px 0;
    font-size: 12.5px;
    cursor: pointer;

    &:hover {
      background: var(--el-fill-color-light);
      color: var(--el-color-primary);
    }
  }
  .fp-list {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding-bottom: 8px;
  }
  .fp-item {
    position: relative;
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px;
    border: 1px solid transparent;
    border-radius: 8px;
    cursor: pointer;

    &:hover {
      background: var(--el-fill-color-light);
      .fp-more {
        opacity: 1;
      }
    }
    &.on {
      border-color: var(--el-color-primary-light-5);
      background: var(--el-color-primary-light-9);
    }
    &.arc {
      opacity: 0.75;
    }
    /* 置顶会话：左缘蓝竖条（原型 pin 标记） */
    &.pin::before {
      content: '';
      position: absolute;
      left: 0;
      top: 9px;
      bottom: 9px;
      width: 3px;
      border-radius: 2px;
      background: var(--el-color-primary);
    }
    .sdot {
      /* Q10 圆点统一：与会话页 sdot/统计面板 spdot 同 7px */
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: var(--dcs-color-success);
      flex: none;
    }
    /* 批D Q11-A：出错红点 + 回答中旋转标（与会话页同款口径） */
    .sdot-err {
      background: var(--dcs-color-danger);
    }
    .sspin {
      flex: none;
      display: inline-flex;
      align-items: center;
      color: var(--el-color-primary);
      animation: dcs-fpspin 0.9s linear infinite;
    }
    @keyframes dcs-fpspin {
      to {
        transform: rotate(360deg);
      }
    }
    .fp-mid {
      flex: 1;
      min-width: 0;
      display: flex;
      align-items: baseline;
      gap: 8px;
    }
    .fp-t {
      flex: 1;
      min-width: 0;
      font-size: 13px;
      color: var(--dcs-text-primary);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .fp-rename {
      width: 100%;
      font: inherit;
      color: inherit;
      border: 1px solid var(--el-color-primary-light-5);
      border-radius: 4px;
      padding: 0 4px;
      outline: none;
      background: var(--el-bg-color);
    }
    .fp-time {
      flex: none;
      font-size: 10.5px;
      color: var(--dcs-text-secondary);
    }
    .fp-more {
      border: none;
      background: none;
      cursor: pointer;
      padding: 2px 4px;
      border-radius: 6px;
      color: var(--dcs-text-secondary);
      font-size: 14px;
      line-height: 1;
      opacity: 0;
      flex: none;

      &:hover {
        background: var(--el-fill-color);
        color: var(--dcs-text-primary);
      }
    }
  }
  /* DP-1 原型 grp 分组标题 / arcbar 归档折叠条 / ctxmenu 会话操作菜单；R4 二改=分组头计数徽标 */
  .fp-grp {
    display: flex;
    align-items: center;
    gap: 5px;
    padding: 10px 8px 4px;
    font-size: 11px;
    color: var(--dcs-text-placeholder);
    .g-badge {
      font-size: 10px;
      line-height: 1;
      padding: 2px 6px;
      border-radius: 8px;
      background: var(--el-fill-color);
      color: var(--dcs-text-secondary);
    }
    &:first-child {
      padding-top: 4px;
    }
  }
  /* 更早折叠条（与会话页 grp-fold 同款骨架；aarr 旋转复用归档条） */
  .fp-grpbar {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 8px;
    padding: 7px 8px;
    border-top: 1px dashed var(--dcs-border-color-lighter);
    border-radius: 6px 6px 0 0;
    font-size: 12px;
    color: var(--dcs-text-secondary);
    cursor: pointer;
    user-select: none;

    &:hover {
      background: var(--el-fill-color-light);
      color: var(--dcs-text-primary);
    }
    .aarr {
      margin-left: auto;
      transition: transform 0.15s;
    }
  }
  .fp-arcbar {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 8px;
    padding: 7px 8px;
    /* R4 二改视觉统一：与「更早」折叠条同为虚线分隔（视觉席批注） */
    border-top: 1px dashed var(--dcs-border-color-lighter);
    border-radius: 6px 6px 0 0;
    font-size: 12px;
    color: var(--dcs-text-secondary);
    cursor: pointer;

    &:hover {
      background: var(--el-fill-color-light);
      color: var(--dcs-text-primary);
    }
    .aarr {
      /* chevron 右推行尾（与 fp-grpbar 统一，折叠条惯例） */
      margin-left: auto;
      transition: transform 0.2s;
    }
    &.open .aarr {
      transform: rotate(180deg);
    }
  }
  .fp-arcbox {
    padding: 2px 6px 4px;
    border-radius: 0 0 8px 8px;
    background: var(--el-fill-color-lighter);
  }
  .fp-ctxmenu {
    position: absolute;
    top: calc(100% + 2px);
    right: 6px;
    z-index: 12;
    min-width: 132px;
    display: flex;
    flex-direction: column;
    background: var(--el-bg-color);
    border: 1px solid var(--dcs-border-color-lighter);
    border-radius: 8px;
    box-shadow: var(--el-box-shadow-light);
    padding: 4px;

    button {
      display: flex;
      align-items: center;
      gap: 7px;
      border: none;
      background: none;
      text-align: left;
      font-size: 12.5px;
      color: var(--dcs-text-regular);
      padding: 6px 10px;
      border-radius: 6px;
      cursor: pointer;
      white-space: nowrap;

      &:hover {
        background: var(--el-color-primary-light-9);
        color: var(--el-color-primary);
      }
      &.dgw {
        color: var(--dcs-color-danger);
        &:hover {
          background: var(--dcs-color-danger-light-9, #fde2e2);
          color: var(--dcs-color-danger);
        }
      }
    }
  }
  .fp-empty {
    padding: 14px 8px;
    font-size: 12px;
    color: var(--dcs-text-placeholder);
    text-align: center;
  }
  .fp-loadmore {
    display: block;
    width: 100%;
    margin-top: 6px;
    padding: 7px 0;
    border: none;
    border-radius: 8px;
    background: none;
    font-size: 12.5px;
    color: var(--el-color-primary);
    cursor: pointer;

    &:hover {
      background: var(--el-fill-color-light);
    }
  }
  .fp-note {
    padding-top: 8px;
    border-top: 1px solid var(--dcs-border-color-lighter);
    font-size: 11px;
    color: var(--dcs-text-placeholder);
    text-align: center;
    line-height: 1.7;
  }
}
/* 使用说明弹层（el-dialog append-to-body 渲染；⚠ scoped 内 :global() 嵌套子选择器会被编译压平成
   裸 .dcs-help-body 错装到本体——历史 P1 实锤，故全部平铺为 :global(复合选择器)，禁再嵌套） */
:global(.dcs-help-body .help-rules) {
  /* 五条细则卡（原型 .hbody 首卡：浅蓝底+边框） */
  background: #ecf5ff;
  border: 1px solid #d9ecff;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  color: #5a5e66;
  margin-bottom: 8px;
  line-height: 1.8;
}
:global(.dcs-help-body .help-rules b) {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 4px;
  color: #303031;
}
:global(.dcs-help-body .help-steps) {
  margin: 4px 0 8px;
  padding-left: 20px;
  font-size: 13.5px;
  color: var(--dcs-text-primary);
  line-height: 1.9;
}
:global(.dcs-help-body .help-tab) {
  width: 100%;
  border-collapse: collapse;
  margin: 4px 0 8px;
  font-size: 13px;
}
:global(.dcs-help-body .help-tab td) {
  border: 1px solid var(--dcs-border-color-lighter);
  padding: 5px 10px;
  color: var(--dcs-text-primary);
}
:global(.dcs-help-body .help-tab td:first-child) {
  width: 1%;
  white-space: nowrap;
  font-weight: 600;
  color: var(--el-color-primary);
}
:global(.dcs-help-body h4) {
  margin: 10px 0 4px;
  font-size: 13.5px;
  color: var(--dcs-text-primary);
}
:global(.dcs-help-body .help-ul) {
  margin: 4px 0;
  padding-left: 20px;
  font-size: 13px;
  color: var(--dcs-text-secondary);
  line-height: 1.8;
}
@media (prefers-reduced-motion: reduce) {
  .dcs-dw,
  .dcs-dw-mask {
    transition: none;
  }
}
</style>
