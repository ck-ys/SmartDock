<template>
  <div class="agent-ws">
    <!-- 批3.6：工作台壳只剩一条顶栏——会话区整体复用 AssistantWorkspace 积木（本页对话，不再跳转） -->
    <header class="ag-top">
      <span class="brand"><span class="mk">S</span> 智能体工作台</span>
      <span class="sp"></span>
      <button v-if="canAudit" class="tb-btn" type="button" @click="go('/ai/assistant-audit')"><IconSpan name="clipboard" :size="13" /> 审计</button>
      <button class="tb-btn" type="button" @click="go('/index')"><IconSpan name="arrow-up-right" :size="13" /> 返回业务系统</button>
    </header>

    <AssistantWorkspace ref="wsRef" variant="agent">
      <!-- 进门内容分两段：标题在输入框上方、技能条在输入框下方（原型 D3 的顺序）。
           两段都只在空态渲染（积木 agEmpty 判据），会话一开始就整块让位。 -->
      <template #intro>
        <div class="ag-dcol">
          <h1 class="ag-h1">今天帮你做点什么</h1>
          <p class="ag-sub">问数据、写周报、盯定时任务；@ 带上文件，/ 调技能与指令</p>
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
                @keydown.enter="pick(s)"
                @keydown.space.prevent="pick(s)"
              >
                <div class="ag-card-hd">
                  <span class="ag-tile" aria-hidden="true">{{ s.name.slice(0, 1) }}</span>
                  <b class="ag-card-n">{{ s.name }}</b>
                </div>
                <p class="ag-card-d">{{ descOf(s.desc) }}</p>
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
import { useRouter } from 'vue-router';
import IconSpan from '@/components/DcsAssistant/IconSpan.vue';
import AssistantWorkspace from '@/components/DcsAssistant/AssistantWorkspace.vue';
import { useUserStore } from '@/store/modules/user';
import { getAssistantSkillCatalog, getAssistantRecentSkills } from '@/api/dcs/assistant';

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
const userStore = useUserStore();
const wsRef = ref<InstanceType<typeof AssistantWorkspace>>();

const catalog = ref<CatalogRow[]>([]);
const recent = ref<Array<{ skillId: string; useCount: number }>>([]);
const loading = ref(true);
/** 目录没读出来时不能只说「技能库是空的」——那是两件事，说错了会让人去翻数据库 */
const failed = ref(false);

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

/** 技能描述是 SKILL.md 原文，十个里有十个长成「用户说「A/B/C」时，真正的能力描述」。
 *  卡片正文只放后半句：前半句是触发词，放进卡片会把正文挤成半句就截断（「用户说「写周报/帮我
 *  写本周周报/周报怎么…」），读着像没写完。不是这套句式的技能原样透传，不改作者文案。 */
function descOf(d: string): string {
  const s = (d || '').trim();
  const hit = s.match(/^用户说[「『][^」』]*[」』]\s*时[，,]\s*([\s\S]+)$/);
  return (hit && hit[1].trim()) || s;
}

/** 空态文案分三种：还在读 / 读失败 / 真的没有——三件事不能混成一句「技能库是空的」 */
const stripEmptyText = computed(() => {
  if (loading.value) return '正在读取能力…';
  if (failed.value) return '能力清单没读出来——刷新页面再试；还是空的话找管理员看技能库。';
  return '技能库还是空的——管理员上架技能后，这里会自动出现。';
});

function pick(s: CandoRow) {
  wsRef.value?.setDraft(`用「${s.name}」帮我：`);
  wsRef.value?.focusComposer();
}

function go(path: string) {
  router.push(path);
}

onMounted(async () => {
  // 独立分区不进动态路由，权限在这里自检；未授权统一进 /403 说明页（保留业务壳层）
  if (!allowed.value) {
    router.replace('/403');
    return;
  }
  const [catRes, recRes] = await Promise.allSettled([getAssistantSkillCatalog(), getAssistantRecentSkills()]);
  catalog.value = catRes.status === 'fulfilled' ? ((catRes.value as unknown as { data?: CatalogRow[] })?.data ?? []) : [];
  recent.value =
    recRes.status === 'fulfilled' ? ((recRes.value as unknown as { data?: Array<{ skillId: string; useCount: number }> })?.data ?? []) : [];
  failed.value = catRes.status === 'rejected';
  loading.value = false;
});
</script>

<style lang="scss" scoped>
/* 壳层口径：--app-chrome 归零（无业务壳页头），积木高度扣顶栏 48px；双肤沿用批3 令牌 */
.agent-ws {
  --bd: rgba(13, 13, 13, 0.1);
  --bg: #f8f8f8;
  --panel: #ffffff;
  --text: #0d0d0d;
  --mut: #52525b;
  --faint: #6b6b6b;
  --tile: #f2f3f5;
  --acc: #0d0d0d;
  --acc-txt: #ffffff;
  --app-chrome: 0px;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  color: var(--text);
}
html.dark .agent-ws {
  --bd: rgba(255, 255, 255, 0.12);
  --bg: #101216;
  --panel: #1b1e24;
  --text: #e9ebee;
  --mut: #a8adb5;
  --faint: #8d929a;
  --tile: rgba(255, 255, 255, 0.06);
  --acc: #e9ebee;
  --acc-txt: #17191c;
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
.mk {
  width: 22px;
  height: 22px;
  border-radius: 4px;
  background: var(--acc);
  color: var(--acc-txt);
  font-size: 12px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
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

/* 积木占满余高（扣顶栏）；.ws-in-agent 钩子留给积木内后续微调 */
.agent-ws :deep(.dcs-as-session) {
  height: calc(100vh - 48px);
  height: calc(100dvh - 48px);
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
  border: 1px solid var(--bd);
  border-radius: 12px;
  background: var(--panel);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.16s,
    background-color 0.16s;
}
.ag-card:hover {
  border-color: rgba(13, 13, 13, 0.28);
}
html.dark .ag-card:hover {
  border-color: rgba(255, 255, 255, 0.32);
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
}
</style>
