<template>
  <div class="agent-ws" :class="{ 'has-alert': !!deepLinkError }">
    <!-- 批3.6：工作台壳只剩一条顶栏——会话区整体复用 AssistantWorkspace 积木（本页对话，不再跳转） -->
    <header class="ag-top">
      <span class="brand"><BrandMark :size="22" /> 智能体工作台</span>
      <span class="sp"></span>
      <button v-if="canAudit" class="tb-btn" type="button" @click="go('/agent/settings?s=audit')"><IconSpan name="clipboard" :size="13" /> 审计</button>
      <button class="tb-btn" type="button" @click="go('/index')"><IconSpan name="arrow-up-right" :size="13" /> 返回业务系统</button>
    </header>

    <div v-if="deepLinkError" class="ag-alert" role="alert">
      <span>{{ deepLinkError }}</span>
      <button class="ag-alert-x" type="button" aria-label="关闭提示" @click="deepLinkError = ''">×</button>
    </div>

    <AssistantWorkspace ref="wsRef" variant="agent">
      <!-- 进门内容分两段：标题在输入框上方、技能条在输入框下方（原型 D3 的顺序）。
           两段都只在空态渲染（积木 agEmpty 判据），会话一开始就整块让位。 -->
      <template #intro>
        <div class="ag-dcol">
          <h1 class="ag-h1">今天帮你做点什么</h1>
          <p class="ag-sub">问数据、写周报、盯自动化；@ 带上文件，＋ 选技能，/ 调快捷指令</p>
        </div>
      </template>

      <template #intro-foot>
        <div class="ag-dcol">
          <section class="ag-strip" aria-label="技能">
            <div class="ag-strip-h">
              <b>技能</b>
              <em>点一张，直接开工</em>
              <span class="ag-strip-n">这里 {{ cando.length }} 个 · 共 {{ catalog.length }} 个</span>
            </div>
            <!-- 三张：最近用过的排前，技能库补足；数据全来自 /skills/recent ∪ /skills，没有一张是写死的 -->
            <div v-if="cando.length" class="ag-cards">
              <article
                v-for="s in cando"
                :key="s.id"
                class="ag-card"
                role="button"
                tabindex="0"
                :aria-label="`用「${s.name}」开个头`"
                @click="pick(s)"
                @mouseenter="onCardEnter(s)"
                @mouseleave="onCardLeave(s)"
                @keydown.enter="pick(s)"
                @keydown.space.prevent="pick(s)"
              >
                <div class="ag-card-hd">
                  <span class="ag-tile" aria-hidden="true">{{ s.name.slice(0, 1) }}</span>
                  <b class="ag-card-n">{{ s.name }}</b>
                </div>
                <p class="ag-card-d" :title="skillDesc(s.desc)">{{ skillDesc(s.desc) }}</p>
                <div class="ag-card-m">{{ s.source === 'recent' ? `你用过 ${s.count} 次` : '技能库' }}</div>
              </article>
            </div>
            <p v-else class="ag-strip-empty">{{ stripEmptyText }}</p>
          </section>
        </div>
      </template>
    </AssistantWorkspace>
  </div>
</template>

<script setup lang="ts">
/**
 * 智能体工作台（批3.6 主题化）：壳=顶栏（品牌/审计/返回业务系统）+ 会话工作区公共积木。
 * 批3 的进门页（静态三卡/额度卡/跨页交接）已退役；批3.6 再把「能做什么」从 6 张收到 3 张，
 * 版式对齐原型 D3：标题 + 一句话 → 输入框 → 技能条（3 张卡，289×153）。
 * - 对话在本页进行，会话区 100% 复用 AssistantWorkspace——有什么能力两边同源；
 * - 三张卡两路真实取数：/skills/recent（你最近用过）∪ /skills（技能库），每卡标出处；
 * - 技能/工具/自动化/审计四项功能未建，左栏导航先置灰（积木内），这里不再放任何「即将上线」入口；
 * - 权限自检沿用批3（agentws:workspace:use）。
 */
import { computed, onMounted, ref } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import BrandMark from '@/components/BrandMark.vue';
import IconSpan from '@/components/DcsAssistant/IconSpan.vue';
import AssistantWorkspace from '@/components/DcsAssistant/AssistantWorkspace.vue';
import { useUserStore } from '@/store/modules/user';
import { getAssistantSkillCatalog, getAssistantRecentSkills, getConversationByThread } from '@/api/dcs/assistant';
import { buildSkillDraft, shouldGhostOnHover, skillDesc } from './prefill';
import { setWorkspaceSnapshot, takePendingSkill, takeWorkspaceSnapshot } from './bridge';

interface CatalogRow {
  id: string;
  name: string;
  desc: string;
  minRole: 'member' | 'publisher' | 'admin';
}
interface CandoRow extends CatalogRow {
  source: 'recent' | 'catalog';
  count: number;
}

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();
const wsRef = ref<InstanceType<typeof AssistantWorkspace>>();

const catalog = ref<CatalogRow[]>([]);
const recent = ref<Array<{ skillId: string; useCount: number }>>([]);
const loading = ref(true);
/** 目录没读出来时不能只说「技能库是空的」——那是两件事，说错了会让人去翻数据库 */
const failed = ref(false);
/** 通知深链落地失败（会话被归档/清理）：页内常驻提示，不靠一闪而过的 toast */
const deepLinkError = ref('');

const canAudit = computed(() => userStore.permissions.includes('*:*:*') || userStore.permissions.includes('dcs:assistant:audit:query'));
const allowed = computed(() => userStore.permissions.includes('*:*:*') || userStore.permissions.includes('agentws:workspace:use'));

/** 两路合并：最近用过的排前（技能已下架/提权不可见的丢弃），技能库补足到 3 张 */
const cando = computed<CandoRow[]>(() => {
  const byId = new Map(catalog.value.map((s) => [s.id, s]));
  const out: CandoRow[] = [];
  const seen = new Set<string>();
  for (const r of recent.value) {
    const hit = byId.get(r.skillId);
    if (hit && !seen.has(hit.id)) {
      seen.add(hit.id);
      out.push({ ...hit, source: 'recent', count: r.useCount });
    }
  }
  for (const s of catalog.value) {
    if (out.length >= 3) break;
    if (!seen.has(s.id)) {
      seen.add(s.id);
      out.push({ ...s, source: 'catalog', count: 0 });
    }
  }
  return out.slice(0, 3);
});

/** 空态文案分三种：还在读 / 读失败 / 真的没有——三件事不能混成一句「技能库是空的」 */
const stripEmptyText = computed(() => {
  if (loading.value) return '正在读取能力…';
  if (failed.value) return '能力清单没读出来——刷新页面再试；还是空的话找管理员看技能库。';
  return '技能库还是空的——管理员上架技能后，这里会自动出现。';
});

/**
 * 悬停预填（拍板 2026-11-04 题一甲 / 题二甲 / 题四甲；同日用户反馈改灰色半透明幽灵字）：
 * - 句子 = 「用「技能名」帮我：」+ 技能自己的描述（题二甲：不吃第二份手写文案）
 * - 悬停只写进 placeholder（灰字幽灵层，学 WorkBuddy 观感）：真值仍为空、发不出去；
 *   框里有字时占位层不显示，用户输入天然不受碰
 * - 点击 = 整段替换成真文本（学 ZCode），深色可编辑
 * - 手机档（无 hover）不做悬停预填，点一下就是预填（题四·手机档口径）
 */
const hoverFrom = ref<string | null>(null);

function sentenceOf(s: CandoRow): string {
  return buildSkillDraft(s.name, s.desc);
}
function canHoverPrefill(): boolean {
  return typeof window.matchMedia === 'function' ? window.matchMedia('(hover: hover)').matches : false;
}
function draftNow(): string {
  return wsRef.value?.draftText ?? '';
}

function onCardEnter(s: CandoRow) {
  if (!canHoverPrefill()) return;
  // 框里有用户打的字 → 只高亮卡片，灰字层也不换（占位层反正不显示，判空只为干净）
  if (!shouldGhostOnHover(draftNow())) return;
  hoverFrom.value = s.id;
  wsRef.value?.setGhostText(sentenceOf(s));
}

function onCardLeave(s: CandoRow) {
  if (!canHoverPrefill()) return;
  // 从 A 卡滑到 B 卡时，A 的 leave 可能晚于 B 的 enter——只撤自己那张卡的灰字
  if (hoverFrom.value !== s.id) return;
  wsRef.value?.setGhostText(null);
  hoverFrom.value = null;
}

function pick(s: CandoRow) {
  wsRef.value?.setGhostText(null);
  hoverFrom.value = null;
  wsRef.value?.setDraft(sentenceOf(s));
  wsRef.value?.focusComposer();
}

function go(path: string) {
  router.push(path);
}

/** 去设置页时记下当前会话与草稿（2026-11-05 A3）：回来能接上，不用网址参数。
 *  只记去 /agent/settings 这一跳；去业务系统不记（免得回来被旧快照拉回旧会话）。 */
onBeforeRouteLeave((to) => {
  if (to.path === '/agent/settings') {
    setWorkspaceSnapshot({
      threadId: wsRef.value?.currentThreadId ?? '',
      draft: wsRef.value?.draftText ?? '',
      // 题7②：连标题一起带走——首屏 50 条外的旧会话回来时列表找不到行，不能叫「新会话」
      title: wsRef.value?.currentTitle ?? ''
    });
  }
});

/** 设置页「试一句」回来：先恢复会话与草稿，再填这句。
 *  「框里有字」必须排除我们自己刚恢复的旧草稿——点「试一句」本身就是明确选择；
 *  那字如果是出发前的自己，再问一遍「要不要换」就自相矛盾（三审 P1：选「保留原样」还会把这句静默丢掉）。
 *  只有往返窗口里用户新打的字才问。 */
async function applyHandoff() {
  const pending = takePendingSkill();
  const snap = takeWorkspaceSnapshot();
  // 没有快照（直开设置页/停留过久）时用单例里还在的会话兜底：标题与左栏高亮不掉回「新会话」
  const threadId = snap?.threadId || (wsRef.value?.hasSession ? wsRef.value.currentThreadId : '');
  try {
    if (threadId) {
      await wsRef.value?.restoreSession(threadId, snap?.draft, snap?.title);
    }
  } catch {
    // 会话没接上不影响下面填句子
  }
  if (!pending) {
    return;
  }
  const sentence = buildSkillDraft(pending.name, pending.desc);
  const current = draftNow().trim();
  const restored = (snap?.draft ?? '').trim();
  if (current && current !== restored && current !== sentence.trim()) {
    try {
      await ElMessageBox.confirm(`输入框里已经有字了，要换成「${pending.name}」这句吗？`, '换成这句？', {
        confirmButtonText: '换成这句',
        cancelButtonText: '保留原样',
        type: 'warning'
      });
    } catch {
      return;
    }
  }
  wsRef.value?.setDraft(sentence);
  wsRef.value?.focusComposer();
}

onMounted(async () => {
  // 独立分区不进动态路由，权限在这里自检；未授权统一进 /403 说明页（保留业务壳层）
  if (!allowed.value) {
    router.replace('/403');
    return;
  }
  // 交接与技能目录并行（三审 J）：慢网恢复/等确认时，技能条照常出，不空转
  const handoff = applyHandoff().catch(() => {});
  // 深链取会话也提前发出去（不等技能目录）：慢网时三件事并行，不串成一条长队
  const deep = fetchDeepLink().catch(() => null);
  const [catRes, recRes] = await Promise.allSettled([getAssistantSkillCatalog(), getAssistantRecentSkills()]);
  catalog.value = catRes.status === 'fulfilled' ? ((catRes.value as unknown as { data?: CatalogRow[] })?.data ?? []) : [];
  recent.value =
    recRes.status === 'fulfilled' ? ((recRes.value as unknown as { data?: Array<{ skillId: string; useCount: number }> })?.data ?? []) : [];
  failed.value = catRes.status === 'rejected';
  loading.value = false;
  await handoff;
  // 自动化通知深链 /agent?thread=…：最后应用，覆盖交接恢复的会话
  await applyDeepLink(await deep);
});

interface DeepLinkRow {
  id: number | string;
  threadId: string;
  title: string | null;
  lastMessageTime: string;
  status: string;
}

function deepLinkThread(): string {
  return String(route.query.thread ?? '').trim();
}

async function fetchDeepLink(): Promise<DeepLinkRow | null> {
  const thread = deepLinkThread();
  if (!thread) return null;
  const res = await getConversationByThread(thread);
  return ((res as unknown as { data?: DeepLinkRow })?.data ?? null) as DeepLinkRow | null;
}

/**
 * 通知深链（2026-11-05 自动化批·题5 甲）：按 threadId 取会话再打开。
 * 不走 restoreSession（它要求会话已在首屏 50 条里，且会被"用户已选别的会话"守卫拦下）。
 * - 成功后才抹掉 ?thread=：取不到时保留地址，刷新还能再试（先 replace 会让链接一刷就丢）
 * - 打开失败只由 openConversation 自己提示一次，这里不再叠第二个 toast
 */
async function applyDeepLink(row: DeepLinkRow | null) {
  if (!deepLinkThread()) return;
  if (!row) {
    // 深链落地失败：页内提示兜底（不靠 toast——它 3 秒就没了，用户可能还没反应过来）
    // 文案压到 24 字内：手机档 390 也不折行，提示条高度才敢写死 34px
    deepLinkError.value = '这条结果会话没打开（可能已被清理），刷新再试。';
    return;
  }
  await wsRef.value?.openConversation(row as never);
  router.replace({ path: '/agent' });
}
</script>

<style lang="scss" scoped>
/* 壳层口径：--app-chrome 归零（无业务壳页头），积木高度扣顶栏 48px；双肤沿用批3 令牌 */
.agent-ws {
  --bd: rgba(13, 13, 13, 0.1);
  --bg: #fafafa;
  --panel: #ffffff;
  --text: #0d0d0d;
  --mut: #52525b;
  --faint: #6b6b6b;
  --tile: #f0f0f0;
  --acc: #0d0d0d;
  --acc-txt: #ffffff;
  /* 提示条用的警示色（深度链落地失败）：亮档用深琥珀，深档换亮一档才读得清 */
  --warn: #9a6700;
  /* 批4：白卡原来和面板共用 --bd（0.10），全页 6 条同重线；卡边单独收淡一档，
     读起来是「柔边」而不是「画了一根线」。环色 / 落影色都从这里取。
     静止态故意不到 3:1（要到得用 #949494 中灰实线，和整站安静边框冲突），
     「我是可以点的」由交互态补：指针移上/键盘聚焦时边框转主色（#2f6feb 对白 4.57:1） */
  --card-ring: rgba(16, 24, 40, 0.07);
  --card-ring-2: rgba(16, 24, 40, 0.04);
  --card-ring-hover: var(--el-color-primary);
  --card-ring-2-hover: rgba(16, 24, 40, 0.06);
  --app-chrome: 0px;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  color: var(--text);
}
/* T7 深色：跟 T7 深皮肤的面板/画布/描边（2026-11-04 三审 P2） */
html[data-skin='t7'].dark .agent-ws {
  --bd: #333333;
  --bg: #101010;
  --panel: #242424;
  --tile: rgba(255, 255, 255, 0.07);
}
html.dark .agent-ws {
  --bd: rgba(255, 255, 255, 0.12);
  --bg: #0a0a0a;
  --panel: #1b1b1b;
  --text: #e9ebee;
  --mut: #a8adb5;
  --faint: #8d929a;
  --tile: rgba(255, 255, 255, 0.06);
  --acc: #e9ebee;
  --acc-txt: #17191c;
  --warn: #d4a72c;
  /* 深色档不能照搬亮色的黑影：卡片本来就比容器亮一点点（1.05:1），再压一层黑影
     四周反而更暗，读起来像「挖了个坑」。深色下取消落影，靠底色抬升 + 一条更亮的边成形 */
  --card-ring: rgba(255, 255, 255, 0.13);
  --card-ring-2: transparent;
  --card-ring-hover: var(--el-color-primary);
  --card-ring-2-hover: transparent;
}

/* 顶栏：字号 12/14、间距 4/8/12、圆角 4/8，全部对齐 dcs-tokens 六档（原 13.5px/650/6px/30px 在档外） */
.ag-top {
  flex: none;
  height: 48px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  border-bottom: 1px solid var(--bd);
  background: var(--panel);
  font-size: 13px;
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
}
.sp {
  flex: 1;
}
.tb-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--bd);
  border-radius: 8px;
  background: var(--panel);
  color: var(--mut);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
}
.tb-btn:hover {
  color: var(--text);
  border-color: var(--text);
}

/* 深链落地失败的页内提示：一条安静的通栏，不抢会话区的注意力。
   高度固定 34px（文案压在一行内），会话区按 48+34 扣，和顶栏口径一致 */
.ag-alert {
  flex: none;
  height: 34px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  border-bottom: 1px solid var(--bd);
  background: var(--panel);
  color: var(--warn, #9a6700);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
}
.ag-alert-x {
  margin-left: auto;
  border: 0;
  background: none;
  color: var(--mut);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
.ag-alert-x:hover {
  color: var(--text);
}

/* 积木占满余高（扣顶栏）；.ws-in-agent 钩子留给积木内后续微调 */
.agent-ws :deep(.dcs-as-session) {
  height: calc(100vh - 48px);
  height: calc(100dvh - 48px);
}
/* 有提示条时再扣 34px，否则会话区会被顶出视口底 */
.agent-ws.has-alert :deep(.dcs-as-session) {
  height: calc(100vh - 48px - 34px);
  height: calc(100dvh - 48px - 34px);
}

/* 进门内容：与输入框同一条列几何。宽度必须读 --dcs-col-max（Composer 观测宿主宽写的
   单一真源：宿主 ≥1380 时 900 → 1080），不能写死 900——写死的话全屏下输入框 1080、
   标题和卡片还是 900，三张卡比上面的输入框窄一圈、左边缘也对不齐。 */
.ag-dcol {
  width: 100%;
  max-width: var(--dcs-col-max, 900px);
  margin-inline: auto;
  /* 容器基线定 13：不写就继承 body 的 16px，档外字号会从这里漏进新加的元素 */
  font-size: 13px;
}
.ag-h1 {
  margin: 0;
  font-size: 26px;
  line-height: 32px;
  font-weight: 500;
  letter-spacing: -0.2px;
  text-align: center;
}
.ag-sub {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 20px;
  color: var(--mut);
  text-align: center;
}

/* 技能条 */
.ag-strip {
  margin-top: 24px;
}
.ag-strip-h {
  display: flex;
  align-items: baseline;
  gap: 8px;

  b {
    font-size: 14px;
    font-weight: 600;
  }
  em {
    font-style: normal;
    font-size: 13px;
    font-weight: 400;
    color: var(--faint);
  }
  /* 「全部 N 个 →」原本是蓝链接，但技能管理页还没建——不做假入口，退成灰色计数 */
  .ag-strip-n {
    margin-left: auto;
    font-size: 13px;
    color: var(--faint);
  }
}
.ag-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-top: 12px;
  align-items: stretch;
}
.ag-card {
  display: flex;
  flex-direction: column;
  padding: 16px;
  /* 批4：白卡原来靠 1px 边成形，和面板边同重、全页 6 条同色线；把同一条边的颜色调淡一档，
     读起来比线软。**不用外扩的环阴影**：0 0 0 1px 画在 border-box 外面，卡会比输入框
     左右各宽 1px、16px 的 gap 读成 14px——本页刚把「卡和输入框不同宽」当过问题修 */
  border: 1px solid var(--card-ring);
  border-radius: 12px;
  background: var(--panel);
  box-shadow: 0 1px 2px var(--card-ring-2);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.16s,
    box-shadow 0.16s;
}
/* 键盘聚焦与指针悬停同一条反馈：边界转主色——静止态那条淡边不够 3:1（见上方令牌注释），
   交互态这条 4.57:1 才过 WCAG 1.4.11 */
.ag-card:hover,
.ag-card:focus-visible {
  border-color: var(--card-ring-hover);
  box-shadow: 0 2px 6px var(--card-ring-2-hover);
}
/* 颜色预算（批4 题6 甲）：平时中性灰块，指针移上去才转主色——色只放在"要动手"的地方 */
.ag-card:hover .ag-tile,
.ag-card:focus-visible .ag-tile {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.ag-card-hd {
  display: flex;
  align-items: center;
  gap: 12px;
}
/* 图标位用技能名首字：技能是动态数据，硬编码图标会瞎配（10 个技能里 4 个换过名字） */
.ag-tile {
  width: 40px;
  height: 40px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: var(--tile);
  color: var(--text);
  font-size: 15px;
  font-weight: 500;
  transition:
    background-color 0.16s,
    color 0.16s;
}
.ag-card-n {
  font-size: 14px;
  font-weight: 600;
  line-height: 20px;
}
.ag-card-d {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 20px;
  color: var(--mut);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  /* 批4：三卡并排时眼睛横扫的是同一条线，正文一行 / 两行不定会让出处行一高一低
     （实测 720 / 720 / 740，差 20px）。给正文一个两行高，出处自然对齐。
     断点口径跟全仓统一在 992（不另写 993，否则 992~993 之间那 1px 落空档）：
     手机档在下方 ≤992 里归零——那是竖排，没有横向对齐这回事，加了两张卡白长 20px */
  min-height: 40px;
}
/* 原来 min-height:40 + margin-top:auto 把「技能库」顶到卡片底部：正文两行撑满、
   底下一条孤零零的字，卡片看着又高又空。现在正文按实际行数走，出处紧跟其后 */
.ag-card-m {
  margin-top: 8px;
  font-size: 12px;
  color: var(--faint);
}
.ag-strip-empty {
  margin: 12px 0 0;
  font-size: 13px;
  color: var(--faint);
}

.agent-ws :is(button, [role='button']):focus-visible {
  outline: 2px solid var(--text);
  outline-offset: 2px;
}

@media (max-width: 992px) {
  .ag-cards {
    grid-template-columns: 1fr;
  }
  .ag-h1 {
    font-size: 20px;
    line-height: 26px;
  }
  /* 手机档输入框自身左右各让 24px（实测 .box 距主区边 25px），标题和卡片跟上，
     否则卡片比输入框宽出一圈，读起来是两套边 */
  .ag-dcol {
    padding: 0 24px;
  }
  /* 竖排没有「横向对齐出处行」这回事，两行高只让每张卡白长 20px */
  .ag-card-d {
    min-height: 0;
  }
}
</style>
