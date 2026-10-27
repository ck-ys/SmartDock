<template>
  <div class="app-container cockpit">
    <!-- 标题行：一屏先回答「今天有没有异常」，不做问候语大横幅 -->
    <header class="ov-head">
      <div class="ov-head__main">
        <h1>总览</h1>
        <p>{{ today }}<template v-if="updatedAt"> · 更新于 {{ updatedAt }}</template></p>
      </div>
      <div class="ov-head__actions">
        <!-- 复核入口的数字来自低分主体：队列为空时降成普通按钮，避免整屏最亮的蓝指着一个空队列 -->
        <el-button v-if="canReview && reviewCount > 0" type="primary" @click="go('/insight/profile?review=1')">
          查看待复核（{{ reviewCount }}）
        </el-button>
        <el-button v-else-if="canReview" @click="go('/insight/profile?review=1')">查看待复核</el-button>
        <el-button v-else-if="canAnalytics" @click="go('/insight/profile-analytics')">打开分析中心</el-button>
        <el-button :icon="Refresh" circle plain :loading="refreshing" title="刷新数据" aria-label="刷新数据" @click="loadAll" />
      </div>
    </header>

    <!-- KPI 条：一个容器按 1px 分隔，数字右对齐等宽，不给每格配彩色条 -->
    <!-- 上限按格数算：只有 1-2 个指标时整条不会拉成一张铺满全宽的空卡（低权限账号见过单格撑满） -->
    <section
      v-if="kpis.length"
      class="ov-kpis"
      :style="{ gridTemplateColumns: `repeat(${kpis.length}, minmax(0, 1fr))`, maxWidth: kpiMaxWidth }"
    >
      <div v-for="item in kpis" :key="item.label" class="ov-kpi">
        <span class="k">{{ item.label }}</span>
        <b class="v" :class="item.tone ? `is-${item.tone}` : ''">{{ item.value }}</b>
        <span class="c">{{ item.caption }}</span>
      </div>
    </section>

    <div class="ov-grid" :class="{ 'is-single': !canAnalytics }">
      <!-- 主图：近 7 天浏览量（墨青是整屏唯一的数据色） -->
      <section v-if="canAnalytics" class="ov-panel">
        <header class="ov-panel__head">
          <h2>近 7 天浏览量<span v-if="chart.has">合计 {{ pulse?.pv ?? 0 }}</span></h2>
          <button type="button" class="ov-link" @click="go('/insight/profile-analytics')">分析中心 →</button>
        </header>
        <div v-if="chart.has" class="ov-chart">
          <svg :viewBox="`0 0 ${chart.w} ${chart.h}`" role="img" aria-label="近 7 天浏览量趋势">
            <g class="grid">
              <line v-for="t in chart.ticks" :key="`g${t.v}`" :x1="chart.padL" :x2="chart.w - chart.padR" :y1="t.y" :y2="t.y" />
            </g>
            <g class="tick">
              <text v-for="t in chart.ticks" :key="`t${t.v}`" :x="chart.padL - 8" :y="t.y + 3.5" text-anchor="end">{{ t.v }}</text>
              <text v-for="l in chart.labels" :key="l.text + l.x" :x="l.x" :y="chart.h - 6" :text-anchor="l.anchor">{{ l.text }}</text>
            </g>
            <polyline class="line" :points="chart.pts" />
            <circle class="dot" :cx="chart.endX" :cy="chart.endY" r="3.4" />
            <text class="end" :x="chart.endX + 8" :y="chart.endY + 4">{{ chart.last }}</text>
          </svg>
        </div>
        <!-- 取数失败不能伪装成「还没有数据」：两条路分开说 -->
        <p v-else-if="pulseError" class="ov-empty is-error">统计接口暂时取不到数，点右上角刷新重试。</p>
        <p v-else class="ov-empty">近 7 天还没有汇总数据，事件入库后这里会出现趋势。</p>
        <!-- 底部三项跟着图走：没有趋势时「跳出率 0% / 每次访问 0 页」只是在报空 -->
        <footer v-if="chart.has" class="ov-panel__foot">
          <span>跳出率 {{ fmtBounce }}</span>
          <span>每次访问 {{ fmtPages }}</span>
          <span>平均停留 {{ avgDuration }}</span>
        </footer>
      </section>

      <!-- 右栏：引擎与任务状态，每行一条 1px 规则（不再重复出处已在别处的「待我完成」「来源」） -->
      <section class="ov-panel">
        <header class="ov-panel__head">
          <h2>引擎与任务</h2>
        </header>
        <ul class="ov-rows">
          <!-- 没有助手权限的账号，这行只会常亮「待配置」误导人，索性不摆 -->
          <li v-if="canAsk">
            <span class="k">AI 助手</span>
            <span class="v" :class="{ 'is-warn': !assistantReady }">{{ assistantError ? '状态未知' : assistantReady ? '就绪' : '待配置' }}</span>
          </li>
          <li>
            <span class="k">画像引擎</span>
            <span class="v" :class="{ 'is-warn': profileError }">{{
              profileError ? '最近计算时间取不到' : profile ? `最近计算 ${relTime(profile.lastCalcTime)}` : '暂无数据'
            }}</span>
          </li>
          <li>
            <span class="k">数据源</span>
            <span class="v">{{ pulseSource }}</span>
          </li>
        </ul>
        <footer class="ov-panel__foot">
          <button type="button" class="ov-link" @click="go('/insight/profile')">用户画像 →</button>
          <button type="button" class="ov-link" @click="go('/dcs/todo')">任务中心 →</button>
        </footer>
      </section>
    </div>

    <div class="ov-grid" :class="{ 'is-single': !canAsk }">
      <!-- AI 入口：保留首页直问能力，收成一张普通面板；没有助手权限的账号直接不摆（按钮点了不会有人接） -->
      <section v-if="canAsk" class="ov-panel">
        <header class="ov-panel__head">
          <h2>问 AI 助手<span>{{ assistantReady ? '已连接' : '未配置模型' }}</span></h2>
        </header>
        <div class="ov-ask">
          <el-input v-model="askText" placeholder="比如：帮我圈出近 30 天没动静的人" clearable @keyup.enter="onAskEnter" />
          <el-button type="primary" :disabled="!assistantReady" @click="ask()">问助手</el-button>
        </div>
        <div class="ov-prompts">
          <button v-for="p in prompts" :key="p" type="button" @click="ask(p)">{{ p }}</button>
        </div>
      </section>

      <!-- 任务采集（内置 demo）：三个数字，不抢主图的位置 -->
      <section class="ov-panel">
        <header class="ov-panel__head">
          <h2>任务采集<span>内置示例</span></h2>
        </header>
        <ul class="ov-rows">
          <li>
            <button type="button" class="k" @click="go('/dcs/todo')">待我完成</button>
            <span class="v">{{ statNum('todo') }}</span>
          </li>
          <li>
            <button type="button" class="k" @click="go('/dcs/todo')">已提交待审</button>
            <span class="v">{{ statNum('pendingAudit') }}</span>
          </li>
          <li>
            <button type="button" class="k" @click="go('/dcs/notice')">未读消息</button>
            <span class="v">{{ statNum('unreadNotices') }}</span>
          </li>
        </ul>
      </section>
    </div>

    <!-- 最近更新：整行，两条并排 -->
    <section class="ov-panel">
      <header class="ov-panel__head">
        <h2>最近更新</h2>
        <button type="button" class="ov-link" @click="go('/support/changelog')">全部日志 →</button>
      </header>
      <div class="ov-log">
        <article v-for="entry in recentLogs" :key="entry.logId">
          <span class="ver">{{ entry.version }}</span>
          <div>
            <b>{{ entry.title }}</b>
            <p>{{ entry.itemList?.[0]?.text }}</p>
          </div>
        </article>
        <p v-if="logsError" class="ov-empty is-error">更新日志暂时取不到，点右上角刷新重试。</p>
        <p v-else-if="recentLogs.length === 0" class="ov-empty">暂无更新记录</p>
      </div>
    </section>
  </div>
</template>

<script setup name="Index" lang="ts">
import { Refresh } from '@element-plus/icons-vue';
import { getAnalyticsOverview, getAnalyticsTrend, type AnalyticsOverview, type AnalyticsTrendPoint } from '@/api/dcs/core/analytics';
import { getOverview, type ProfileOverview } from '@/api/dcs/core/profile';
import { statsOverview, type DcsStatsOverview } from '@/api/dcs/core/task';
import { getAssistantStatus } from '@/api/dcs/assistant';
import { listChangelog } from '@/api/dcs/changelog';
import type { ChangelogRow } from '@/api/dcs/changelog/types';
import { checkPermi } from '@/utils/permission';
import { useWindowSize } from '@vueuse/core';

const router = useRouter();
const { width: viewportWidth } = useWindowSize();

const stats = ref<DcsStatsOverview | null>(null);
const statsError = ref(false);
const profile = ref<ProfileOverview | null>(null);
const profileError = ref(false);
const pulse = ref<AnalyticsOverview['current'] | null>(null);
const pulseError = ref(false);
const trend = ref<AnalyticsTrendPoint[]>([]);
/** 分析口径走事件源，画像口径走主体源——两个源可以不同，格子里必须写明白，否则「3 个主体 / 0 条事件」看着像坏账 */
const pulseSource = ref('web');
const profileSource = ref('dcs');
const assistantReady = ref(false);
const assistantError = ref(false);
const logsError = ref(false);
const askText = ref('');
const refreshing = ref(false);
const updatedAt = ref('');

const canProfile = computed(() => checkPermi(['profile:score:query']));
const canAnalytics = computed(() => checkPermi(['analytics:center:query']));
const canReview = computed(() => checkPermi(['profile:review:query']));
/** 首页直问是发 window 事件给壳层助手抽屉；没这个权限抽屉不会接，卡片摆出来就是死按钮 */
const canAsk = computed(() => checkPermi(['dcs:assistant:use']));
const reviewCount = computed(() => Number(profile.value?.highRisk ?? 0));

/** 取数失败时给破折号，不能悄悄显示成 0（0 是真实业务值，和「没取到」是两回事） */
function statNum(key: 'todo' | 'pendingAudit' | 'unreadNotices'): string {
  if (statsError.value) {
    return '—';
  }
  return String(stats.value?.member?.[key] ?? 0);
}

const fmtBounce = computed(() => (pulse.value?.bounceRate == null ? '—' : `${pulse.value.bounceRate}%`));
const fmtPages = computed(() => (pulse.value?.pagesPerVisit == null ? '—' : `${pulse.value.pagesPerVisit} 页`));

const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' });
const prompts = ['近 30 天谁最不活跃？', '本周流量趋势怎么样？', '我有哪些待办？'];

/** KPI 只列当前账号真能拿到的数，拿不到的不摆空格子 */
const kpis = computed(() => {
  const list: Array<{ label: string; value: string | number; caption: string; tone?: 'warn' }> = [];
  if (canProfile.value) {
    list.push({
      label: '画像主体',
      value: profile.value?.subjectCount ?? '—',
      caption: `${profileSource.value} 源 · 累计去重`
    });
  }
  if (canAnalytics.value) {
    list.push({
      label: '近 7 天事件',
      value: pulse.value?.events ?? '—',
      caption: 'web 业务源'
    });
  }
  if (canProfile.value) {
    list.push({
      label: '低分主体',
      value: profile.value?.highRisk ?? '—',
      caption: '低于复核阈值',
      // 需要人动手的数字才上语义色（整屏仅此一处），见 dcs-ui-kit 的 KPI 口径
      tone: Number(profile.value?.highRisk ?? 0) > 0 ? 'warn' : undefined
    });
    list.push({
      label: '平均分',
      value: profile.value?.avgScore != null ? Number(profile.value.avgScore).toFixed(1) : '—',
      caption: '满分 100'
    });
  }
  // 画像/分析一个都拿不到的账号不摆假指标：任务数字在右下「任务采集」里，这里空着比摆「—」诚实
  return list;
});

/** 窄档让 KPI 条吃满宽度（两列排布），宽档按格数封顶，避免 1-2 格拉成一张巨卡 */
const kpiMaxWidth = computed(() => (viewportWidth.value <= 768 ? 'none' : `${kpis.value.length * 320}px`));

const avgDuration = computed(() => {
  const sec = Number(pulse.value?.avgDuration ?? 0);
  if (!sec) {
    return '—';
  }
  return sec >= 60 ? `${Math.round(sec / 60)} 分钟` : `${Math.round(sec)} 秒`;
});

/** 7 天折线：手算 SVG，颜色全部走 --app-accent / --app-rule，深浅主题自动跟随 */
const chart = computed(() => {
  const rows = trend.value.slice(-7);
  const vals = rows.map((r) => Number(r.pv) || 0);
  // 手机档换窄画布：viewBox 随宽度缩时字会一起缩到看不清，宁可少画几列像素
  const w = viewportWidth.value <= 768 ? 322 : 560;
  const h = 170;
  const padL = 34;
  const padR = 26;
  const padT = 16;
  const padB = 24;
  const maxRaw = Math.max(1, ...vals);
  const step = maxRaw <= 10 ? 2 : maxRaw <= 50 ? 10 : maxRaw <= 200 ? 50 : 100;
  const max = Math.ceil(maxRaw / step) * step;
  const x = (i: number) => padL + (i * (w - padL - padR)) / Math.max(1, vals.length - 1);
  const y = (v: number) => padT + (1 - v / max) * (h - padT - padB);
  const labelIdx = vals.length > 4 ? [0, 2, 4, 6] : vals.map((_, i) => i);
  return {
    // 全 0 不画线：一条贴着底轴的平线看着像「有数据但没量」，其实是没数据
    has: vals.some((v) => v > 0),
    w,
    h,
    padL,
    padR,
    pts: vals.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' '),
    ticks: [0, max / 2, max].map((v) => ({ v: Number(v.toFixed(2)), y: Number(y(v).toFixed(1)) })),
    labels: labelIdx
      .filter((i) => i < rows.length)
      .map((i) => ({
        x: Number(x(i).toFixed(1)),
        text: (rows[i]?.statDate || '').slice(5),
        anchor: i === 0 ? 'start' : i === rows.length - 1 ? 'end' : 'middle'
      })),
    endX: Number(x(vals.length - 1).toFixed(1)),
    endY: Number(y(vals[vals.length - 1]).toFixed(1)),
    last: vals[vals.length - 1] ?? 0
  };
});

const recentLogs = ref<ChangelogRow[]>([]);

const go = (path: string) => router.push(path);

/** 中文输入法回车=确认候选词，不能当成发送（Composer 同款 IME 守卫） */
function onAskEnter(e: KeyboardEvent) {
  if (e.isComposing) {
    return;
  }
  ask();
}

function relTime(value?: string): string {
  if (!value) {
    return '—';
  }
  // 后端固定输出 GMT+8 墙钟；无时区后缀时显式补 +08:00，避免非东八区用户算成未来
  const iso = value.includes('T') ? value : value.replace(' ', 'T');
  const zoned = /(?:Z|[+-]\d{2}:?\d{2})$/.test(iso) ? iso : `${iso}+08:00`;
  const t = new Date(zoned).getTime();
  if (Number.isNaN(t)) {
    return value;
  }
  const diff = Date.now() - t;
  if (diff < 60_000) {
    return '刚刚';
  }
  if (diff < 3_600_000) {
    return `${Math.floor(diff / 60_000)} 分钟前`;
  }
  if (diff < 86_400_000) {
    return `${Math.floor(diff / 3_600_000)} 小时前`;
  }
  return `${Math.floor(diff / 86_400_000)} 天前`;
}

/** 首页直问：交给壳层里的助手抽屉（window 事件解耦，避免首页依赖助手内部单例） */
function ask(text?: string) {
  const q = (text ?? askText.value).trim();
  if (!q) {
    return;
  }
  if (!assistantReady.value) {
    ElMessage.warning('管理员还没配置 AI 模型，配好即可用');
    return;
  }
  window.dispatchEvent(new CustomEvent('dcs:assistant-ask', { detail: { q } }));
  askText.value = '';
}

async function loadAssistant() {
  if (!canAsk.value) {
    assistantReady.value = false;
    return;
  }
  try {
    const res: any = await getAssistantStatus();
    const d = res?.data ?? res;
    assistantReady.value = !!(d?.enabled && d?.modelConfigured);
    assistantError.value = false;
  } catch {
    // 取不到 ≠ 没配置：状态行必须能区分「待配置」和「这次没问出来」
    assistantReady.value = false;
    assistantError.value = true;
  }
}

async function loadStats() {
  try {
    stats.value = await statsOverview();
    statsError.value = false;
  } catch {
    stats.value = null;
    statsError.value = true;
  }
}

async function loadProfile() {
  if (!canProfile.value) {
    return;
  }
  try {
    profile.value = await getOverview(profileSource.value);
    profileError.value = false;
  } catch {
    profile.value = null;
    profileError.value = true;
  }
}

async function loadPulse() {
  if (!canAnalytics.value) {
    return;
  }
  pulseSource.value = 'web';
  try {
    const [overview, trendData] = await Promise.all([
      getAnalyticsOverview(pulseSource.value, 7),
      getAnalyticsTrend(pulseSource.value, 7)
    ]);
    pulse.value = overview?.current ?? null;
    trend.value = trendData || [];
    pulseError.value = false;
  } catch {
    // 接口挂了与「筛出来是空」必须分开：前者要说取不到，后者才说没数据
    pulse.value = null;
    trend.value = [];
    pulseError.value = true;
  }
}

async function loadLogs() {
  try {
    const res: any = await listChangelog({ pageNum: 1, pageSize: 2 });
    recentLogs.value = res.rows || [];
    logsError.value = false;
  } catch {
    recentLogs.value = [];
    logsError.value = true;
  }
}

async function loadAll() {
  if (refreshing.value) {
    return;
  }
  refreshing.value = true;
  const started = Date.now();
  try {
    await Promise.all([loadAssistant(), loadStats(), loadProfile(), loadPulse(), loadLogs()]);
  } finally {
    // 本地接口往往几十毫秒就回，转圈一闪而过=「点了没反应」；补到 320ms 让动作看得见
    await new Promise((resolve) => setTimeout(resolve, Math.max(0, 320 - (Date.now() - started))));
    // 精确到秒：只到分钟时连点两下文案一模一样，用户会怀疑没刷新
    updatedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
    refreshing.value = false;
  }
}

onMounted(loadAll);
</script>

<style scoped lang="scss">
.cockpit {
  /* 蓝本 shadcn-admin：超宽屏内容封顶 1280 居中（上下 24 / 左右 16 覆写 .app-container 的统一 16） */
  max-width: 1280px;
  margin: 0 auto;
  padding: 24px 16px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* 标题行 */
.ov-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  h1 {
    margin: 0;
    font-size: var(--dcs-font-page);
    line-height: 1.25;
    font-weight: 600;
    letter-spacing: -0.01em;
    color: var(--app-text);
  }
  p {
    margin: 6px 0 0;
    font-size: 13px;
    color: var(--app-faint);
  }
  &__actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }
}

/* KPI 条：一个容器切格，格间 1px */
.ov-kpis {
  display: grid;
  border: var(--app-card-border);
  border-radius: var(--app-card-radius);
  background: var(--app-surface);
  overflow: hidden;
}

.ov-kpi {
  padding: 16px 20px;
  border-right: 1px solid var(--app-rule);
  &:last-child {
    border-right: 0;
  }
  .k {
    display: block;
    font-size: 12px;
    color: var(--app-faint);
  }
  .v {
    display: block;
    margin-top: 4px;
    font-size: var(--dcs-kpi-size);
    line-height: 1.15;
    font-weight: 400;
    color: var(--app-text);
    font-variant-numeric: tabular-nums;
    /* 语义色只认显式声明（低分主体 > 0 才变橙），不按数值自动染色 */
    &.is-warn {
      color: var(--el-color-warning);
    }
  }
  .c {
    display: block;
    margin-top: 4px;
    font-size: 12px;
    color: var(--app-faint);
  }
}

/* 通用面板 */
.ov-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
  gap: 24px;
  /* 右栏消失时（账号没有助手权限）不要留半屏空白 */
  &.is-single {
    grid-template-columns: minmax(0, 1fr);
  }
}

.ov-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 18px 20px;
  border: var(--app-card-border);
  border-radius: var(--app-card-radius);
  background: var(--app-surface);
  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    h2 {
      margin: 0;
      font-size: 15px;
      font-weight: 500;
      color: var(--app-text);
      span {
        margin-left: 8px;
        font-size: 12px;
        font-weight: 400;
        color: var(--app-faint);
      }
    }
  }
  &__foot {
    display: flex;
    align-items: center;
    gap: 18px;
    flex-wrap: wrap;
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px solid var(--app-rule);
    font-size: 12px;
    color: var(--app-faint);
  }
}

.ov-link {
  border: 0;
  background: transparent;
  padding: 0;
  font-size: 13px;
  color: var(--app-muted);
  cursor: pointer;
  &:hover {
    color: var(--app-text);
    text-decoration: underline;
  }
}

/* 折线图 */
.ov-chart {
  margin-top: 10px;
  svg {
    display: block;
    width: 100%;
    height: auto;
  }
  .grid line {
    stroke: var(--app-rule);
    stroke-width: 1;
  }
  .tick text {
    fill: var(--app-faint);
    font-size: var(--dcs-font-xs);
    font-family: var(--el-font-family);
  }
  .line {
    fill: none;
    stroke: var(--app-accent);
    stroke-width: 2;
    stroke-linejoin: round;
    stroke-linecap: round;
  }
  .dot {
    fill: var(--app-accent);
  }
  .end {
    fill: var(--app-accent);
    font-size: 12px;
    font-family: var(--el-font-family);
    font-variant-numeric: tabular-nums;
  }
}

.ov-empty {
  /* 图表区空着时面板会被右栏撑高：让这行字在空白里居中，而不是贴着顶边像没加载完 */
  display: flex;
  align-items: center;
  flex: 1;
  margin: 0;
  padding: 24px 0;
  font-size: 13px;
  color: var(--app-faint);
  &.is-error {
    color: var(--el-color-warning);
  }
}

/* 状态/任务行 */
.ov-rows {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  li {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    gap: 12px;
    min-height: 42px;
    border-top: 1px solid var(--app-rule);
    &:first-child {
      border-top: 0;
    }
  }
  .k {
    border: 0;
    background: transparent;
    padding: 0;
    text-align: left;
    font-size: 13px;
    color: var(--app-muted);
  }
  .v {
    font-size: 13px;
    color: var(--app-text);
    text-align: right;
    font-variant-numeric: tabular-nums;
    &.is-warn {
      color: var(--el-color-warning);
    }
  }
  button.k {
    cursor: pointer;
    &:hover {
      color: var(--app-text);
    }
  }
}

/* AI 直问 */
.ov-ask {
  display: flex;
  gap: 8px;
  margin-top: 14px;
  :deep(.el-input) {
    flex: 1;
    max-width: 460px;
  }
}

.ov-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
  button {
    padding: 6px 12px;
    border: 1px solid var(--app-control);
    border-radius: var(--app-control-radius);
    background: transparent;
    font-size: 13px;
    color: var(--app-muted);
    cursor: pointer;
    transition:
      border-color 0.15s ease,
      color 0.15s ease;
    &:hover {
      border-color: var(--app-muted);
      color: var(--app-text);
    }
  }
}

/* 更新日志 */
.ov-log {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 24px;
  margin-top: 12px;
  article {
    display: flex;
    gap: 12px;
    min-width: 0;
    padding-top: 12px;
    border-top: 1px solid var(--app-rule);
    b {
      display: block;
      font-size: 14px;
      font-weight: 500;
      color: var(--app-text);
      line-height: 1.5;
    }
    p {
      margin: 3px 0 0;
      font-size: 12px;
      color: var(--app-faint);
      line-height: 1.6;
    }
  }
  .ver {
    flex: none;
    height: 20px;
    padding: 0 7px;
    border-radius: var(--app-control-radius);
    background: var(--app-raised);
    color: var(--app-muted);
    font-size: 12px;
    line-height: 20px;
    font-family: 'IBM Plex Mono', ui-monospace, monospace;
  }
}

@media (max-width: 1100px) {
  .ov-grid,
  .ov-log {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 768px) {
  .cockpit {
    gap: 16px;
  }
  .ov-kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr)) !important;
  }
  .ov-kpi {
    border-bottom: 1px solid var(--app-rule);
    &:nth-last-child(-n + 2) {
      border-bottom: 0;
    }
  }
  /* U-21：奇数格时末格跨满整行（复用 dcs-ui-kit 的 :last-child:nth-child(odd) 方案），右侧不再落空 */
  .ov-kpi:last-child:nth-child(odd) {
    grid-column: 1 / -1;
  }
  /* 末格跨满后它独占末行：倒数第二格回到上一行，需要恢复行底分隔线（偶数总数时该格是奇数位，不会命中） */
  .ov-kpi:nth-last-child(2):nth-child(even) {
    border-bottom: 1px solid var(--app-rule);
  }
  .ov-head__actions {
    width: 100%;
    .el-button {
      flex: 1;
    }
    .el-button.is-circle {
      flex: none;
      width: 36px;
    }
  }
  .ov-ask {
    flex-direction: column;
    :deep(.el-input) {
      max-width: none;
    }
  }
}
</style>
