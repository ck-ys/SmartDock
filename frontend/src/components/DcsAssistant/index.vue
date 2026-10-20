<template>
  <!-- 拍板⑤：租户未开启=前端零加载——hidden 前不渲染任何节点；灰球=开关开但未配模型（spec G1） -->
  <template v-if="mode !== 'hidden'">
    <!-- AU-02(1007拍板Q2-A)：助手会话页=助手已全屏在场，球与 proactive 卡冗余且遮发送区——会话路由下整体隐藏。
         R2(2026-10-10 拍板A)：会话页真实路由是 /ai/assistant（菜单 migration_20261008_01），旧守卫写 /assistant
         永假=独立会话页悬浮球重复出现（真机实证）——前缀匹配覆盖该页及子路由 -->
    <fab-ball
      v-show="!drawerOpen && !route.path.startsWith('/ai/assistant')"
      ref="ballRef"
      :has-dot="hasDot"
      :exec="astate.sending"
      :gray="mode === 'gray'"
      :menu-items="menuItems"
      :proactive="proactive"
      @open="openDrawer"
      @ask="onQuickAsk"
      @gray-tip="onGrayTip"
      @pro-accept="onProAccept"
      @pro-ai="onProAi"
      @pro-dismiss="dismissPro"
    />
    <!-- S-38：主体 dynamic import（悬浮球仅壳；灰球态也懒加载但不开）。
         AU-34(1008拍板A)：原 @stopped 让球 pulse——抽屉开时球必隐藏，脉冲永空跑，死代码已删 -->
    <assistant-drawer v-if="mode === 'normal'" ref="drawerRef" v-model="drawerOpen" />
  </template>
</template>

<script setup lang="ts">
/**
 * DCS AI 助手总装（批E Task E2 → PROTO PARITY 批E 外围）。
 *
 * <p>出现条件=登录用户有 dcs:assistant:use 权限 ∧ 租户开关开（G1 status 端点，零轮询一次探测）；
 * 开关开但未配模型=灰球引导（spec G1）；开关关=零加载（拍板⑤）。
 * 悬浮球单击=身份化快捷菜单，菜单开着再点球=开抽屉；主动提示卡=REVISE 退修提醒（每日一次）。</p>
 */
import { checkPermi } from '@/utils/permission';
import { getAssistantStatus } from '@/api/dcs/assistant';
import { myTasks } from '@/api/dcs/core/task';
import { useUserStore } from '@/store/modules/user';
import { useRoute, useRouter } from 'vue-router';
import { disposeAssistant, getAssistant } from './useAssistant';
import { roleOf } from './assistantCtx';
import { pushToast } from './toastStore';
import FabBall, { type BallMenuItem } from './FloatingBall.vue';

const AssistantDrawer = defineAsyncComponent(() => import('./AssistantDrawer.vue'));

const userStore = useUserStore();
const route = useRoute();
const router = useRouter();
const mode = ref<'hidden' | 'gray' | 'normal'>('hidden');
const drawerOpen = ref(false);
const ballRef = ref<InstanceType<typeof FabBall>>();
const drawerRef = ref<InstanceType<typeof AssistantDrawer>>();

/** 单例只读态：sending→球上旋转环 */
const astate = getAssistant(userStore.userId).state;

/** 身份化快捷三问（原型 ballmenu labels：成员=待办/进度/概念，发布人与管理员=待交/未交/催办） */
const menuItems = computed<BallMenuItem[]>(() =>
  roleOf(userStore.roles, userStore.permissions) === 'member'
    ? [
        { icon: 'clock', label: '我的待办', prompt: '我的待办' },
        { icon: 'chart', label: '任务进度', prompt: '我的任务进度怎么样' },
        { icon: 'lightbulb', label: '广播任务是什么', prompt: '广播任务是什么' }
      ]
    : [
        { icon: 'clock', label: '今天要交什么', prompt: '今天要交什么' },
        { icon: 'clipboard', label: '未交名单', prompt: '查未交名单' },
        { icon: 'megaphone', label: '怎么催办', prompt: '怎么催办' }
      ]
);

function openDrawer() {
  drawerOpen.value = true;
}
/** 快捷问（原型 bmAsk）：关菜单→开抽屉→走抽屉 onSend（含须知门与队列语义） */
function onQuickAsk(q: string) {
  ballRef.value?.closeMenu();
  if (mode.value !== 'normal') {
    return;
  }
  drawerOpen.value = true;
  nextTick(() => drawerRef.value?.ask(q));
}
function onGrayTip() {
  pushToast('管理员还没配置 AI 模型，配好即可用');
}
/** 红点：通知中心有助手相关推送（二站接 notice store；先常暗——拍板 P2-3） */
const hasDot = ref(false);

/* ===== 主动提示卡（原型 proactive）：有 REVISE 待办→每日冒一次「去看退回意见」 ===== */
/** AU-01(1007拍板Q1-C)：卡携带定位字段——todo 行本就返回，只是旧代码只取了 missionName 丢弃其余 */
const proactive = ref<{ missionName: string; missionId?: string; detailId?: string } | null>(null);
const PRO_KEY = 'dcs:assistant:pro-dismissed';
const today = () => new Date().toISOString().slice(0, 10);
function dismissPro() {
  proactive.value = null;
  try {
    localStorage.setItem(PRO_KEY, today());
  } catch {
    /* 忽略 */
  }
}
/** AU-01(拍板Q1-C)主按钮：直达任务详情页退修意见（明细历史→审核记录），不走 AI——
 * AI 工具面无 reviewRemark 数据源，直问只能得套话；真数据在详情页历史抽屉 */
function onProAccept() {
  const p = proactive.value;
  proactive.value = null;
  if (!p?.missionId) {
    // 兜底：todo 行缺 missionId（理论不可达）时退回 AI 问法，不给死按钮
    openDrawer();
    nextTick(() => drawerRef.value?.ask(`${p?.missionName ?? ''}的退修原因`));
    return;
  }
  router.push(`/dcs/mission/detail/${p.missionId}?action=revise-reason`);
  pushToast('已带你去任务详情页，退回意见见页面「重新提交」区红色提示');
}
/** AU-01 次级入口：让 AI 解释怎么改（保留原 AI 路径为辅助，用户主动点才耗额度） */
function onProAi() {
  const name = proactive.value?.missionName ?? '';
  proactive.value = null;
  drawerOpen.value = true;
  nextTick(() => drawerRef.value?.ask(`${name}被退回了，怎么修改？`));
}
async function probeProactive() {
  try {
    if (localStorage.getItem(PRO_KEY) === today()) {
      return;
    }
    // 批5 契约迁移: 旧 todo REVISE 端点退役; 回修A后 my 已回填 mySubmissionStatus, 不再逐任务 getTask
    const res = await myTasks({ pageNum: 1, pageSize: 5 });
    for (const t of (res as { rows?: Array<{ id: string; title: string; status?: string; mySubmissionStatus?: string }> }).rows ?? []) {
      if (t.status && t.status !== 'COLLECTING') continue;
      if (t.mySubmissionStatus === 'REJECTED') {
        proactive.value = { missionName: t.title, missionId: t.id };
        break;
      }
    }
  } catch {
    /* 待办不可达不冒卡 */
  }
}

/** 首探（权限过闸才发 status；mode 已就绪跳过防重复探测；AU-38：gray 态放行重探） */
async function probeStatus() {
  if ((mode.value !== 'hidden' && mode.value !== 'gray') || !checkPermi(['dcs:assistant:use'])) {
    return;
  }
  try {
    const res = await getAssistantStatus();
    const d = (res?.data ?? res) as Record<string, unknown>;
    if (d?.enabled && d?.modelConfigured) {
      mode.value = 'normal';
    } else if (d?.enabled) {
      mode.value = 'gray';
    }
  } catch {
    // 后端不可达等异常：静默不显示（全站其它请求已各自提示）
  }
  if (mode.value === 'normal') {
    probeProactive();
  }
}

/** 首页「双引擎驾驶舱」直问：window 事件解耦，首页无需依赖助手内部单例 */
function onExternalAsk(e: Event) {
  const q = (e as CustomEvent<{ q?: string }>).detail?.q?.trim();
  if (!q) {
    return;
  }
  if (mode.value === 'gray') {
    onGrayTip();
    return;
  }
  if (mode.value === 'normal') {
    onQuickAsk(q);
  }
}

onMounted(() => {
  probeStatus();
  window.addEventListener('dcs:assistant-ask', onExternalAsk);
});
// 权限晚到竞态（E2E 实锤 2026-10-07：后端重启慢加载时 permissions 未就绪，首探
// checkPermi=false 直接 return 且永不重试=悬浮球永久隐藏）：权限列表就绪后再探一次
watch(
  () => userStore.permissions,
  () => probeStatus()
);
// AU-38: 灰球态路由变化重探——管理员/用户在别处配好模型后，跳任一新页悬浮球自动升级，
// 无需整页刷新（探一次 status 很轻；仍灰=原地不动无闪烁）
watch(
  () => route.path,
  () => {
    if (mode.value === 'gray') {
      probeStatus();
    }
  }
);

onUnmounted(() => {
  drawerOpen.value = false;
  window.removeEventListener('dcs:assistant-ask', onExternalAsk);
  // S-38 硬销毁只在登出/换人（布局卸载）时发生——抽屉自身关闭不清会话（原型 stash 语义）
  disposeAssistant(userStore.userId);
});
</script>

<style lang="scss">
/* 批G 无障碍：助手域键盘焦点环（石墨 3px；文本框豁免走原生 caret） */
.dcs-fab,
.dcs-dw,
.dcs-as-session,
.dcs-as-admin,
.dcs-as-memory,
.dcs-toasts {
  :focus-visible {
    outline: 3px solid var(--dcs-border-color-dark, #4c5561);
    outline-offset: 1px;
    border-radius: 4px;
  }
  textarea:focus-visible,
  input:focus-visible {
    outline: none;
  }
}

/* 批I-1 图标体系：原型 .iswap 同款（v-html 动态替换产物与 IconSpan 静态件统一形态；
 * 助手域专属类名，不外溢业务页） */
.iswap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  line-height: 1;
  vertical-align: -0.125em;

  svg {
    display: block;
  }
}
/* ➕/pill 系工具钮内 15px/14px 口径（原型 .btools .bicon/.bpill .iswap svg） */
.btool .iswap svg {
  width: 15px;
  height: 15px;
}
.bpill .iswap svg {
  width: 14px;
  height: 14px;
}
</style>
