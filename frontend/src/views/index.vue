<template>
  <div class="app-container cockpit">
    <!-- 标题行：一屏先回答「今天有没有异常」，不做问候语大横幅 -->
    <header class="ov-head">
      <div class="ov-head__main">
        <h1>总览</h1>
        <p>{{ today }} · 近 7 天事件 {{ pulse?.events ?? '—' }} 条，浏览量 {{ pulse?.pv ?? '—' }}</p>
      </div>
      <div class="ov-head__actions">
        <el-button v-if="canReview" type="primary" @click="go('/insight/profile-review')">查看待复核</el-button>
        <el-button v-else-if="canAnalytics" @click="go('/insight/profile-analytics')">打开分析中心</el-button>
        <el-button :icon="Refresh" circle plain aria-label="刷新数据" @click="loadAll" />
      </div>
    </header>

    <!-- KPI 条：一个容器按 1px 分隔，数字右对齐等宽，不给每格配彩色条 -->
    <section class="ov-kpis" :style="{ gridTemplateColumns: `repeat(${kpis.length}, minmax(0, 1fr))` }">
      <div v-for="item in kpis" :key="item.label" class="ov-kpi">
        <span class="k">{{ item.label }}</span>
        <b class="v" :class="item.tone ? `is-${item.tone}` : ''">{{ item.value }}</b>
        <span class="c">{{ item.caption }}</span>
      </div>
    </section>

    <div class="ov-grid">
      <!-- 主图：近 7 天浏览量（墨青是整屏唯一的数据色） -->
      <section class="ov-panel">
        <header class="ov-panel__head">
          <h2>近 7 天浏览量<span>合计 {{ pulse?.pv ?? 0 }}</span></h2>
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
        <p v-else class="ov-empty">近 7 天还没有汇总数据，事件入库后这里会出现趋势。</p>
        <footer class="ov-panel__foot">
          <span>跳出率 {{ pulse?.bounceRate ?? '—' }}%</span>
          <span>每次访问 {{ pulse?.pagesPerVisit ?? '—' }} 页</span>
          <span>平均停留 {{ avgDuration }}</span>
          <span class="ov-source">来源 {{ pulseSource }}</span>
        </footer>
      </section>

      <!-- 右栏：引擎与任务状态，每行一条 1px 规则 -->
      <section class="ov-panel">
        <header class="ov-panel__head">
          <h2>引擎与任务<span>实时</span></h2>
        </header>
        <ul class="ov-rows">
          <li>
            <span class="k">AI 助手</span>
            <span class="v" :class="{ 'is-warn': !assistantReady }">{{ assistantReady ? '就绪' : '待配置' }}</span>
          </li>
          <li>
            <span class="k">画像引擎</span>
            <span class="v">{{ profile ? `最近计算 ${relTime(profile.lastCalcTime)}` : '暂无数据' }}</span>
          </li>
          <li>
            <span class="k">数据源</span>
            <span class="v">{{ pulseSource }}</span>
          </li>
          <li>
            <span class="k">待我完成</span>
            <span class="v">{{ stats?.member?.todo ?? 0 }} 件</span>
          </li>
        </ul>
        <footer class="ov-panel__foot">
          <button type="button" class="ov-link" @click="go('/insight/profile')">用户画像 →</button>
          <button type="button" class="ov-link" @click="go('/dcs/todo')">任务中心 →</button>
        </footer>
      </section>
    </div>

    <div class="ov-grid">
      <!-- AI 入口：保留首页直问能力，收成一张普通面板 -->
      <section class="ov-panel">
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
            <span class="v">{{ stats?.member?.todo ?? 0 }}</span>
          </li>
          <li>
            <button type="button" class="k" @click="go('/dcs/todo')">已提交待审</button>
            <span class="v">{{ stats?.member?.pendingAudit ?? 0 }}</span>
          </li>
          <li>
            <button type="button" class="k" @click="go('/dcs/notice')">未读消息</button>
            <span class="v">{{ stats?.member?.unreadNotices ?? 0 }}</span>
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
        <p v-if="recentLogs.length === 0" class="ov-empty">暂无更新记录</p>
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
const profile = ref<ProfileOverview | null>(null);
const pulse = ref<AnalyticsOverview['current'] | null>(null);
const trend = ref<AnalyticsTrendPoint[]>([]);
const pulseSource = ref('web');
const assistantReady = ref(false);
const askText = ref('');

const canProfile = computed(() => checkPermi(['profile:score:query']));
const canAnalytics = computed(() => checkPermi(['analytics:center:query']));
const canReview = computed(() => checkPermi(['profile:review:query']));

const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' });
const prompts = ['近 30 天谁最不活跃？', '本周流量趋势怎么样？', '我有哪些待办？'];

/** KPI 只列当前账号真能拿到的数，拿不到的不摆空格子 */
const kpis = computed(() => {
  const list: Array<{ label: string; value: string | number; caption: string; tone?: 'warn' }> = [];
  if (canProfile.value) {
    list.push({
      label: '画像主体',
      value: profile.value?.subjectCount ?? '—',
      caption: '累计去重'
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
  if (!list.length) {
    list.push({ label: '近 7 天浏览量', value: pulse.value?.pv ?? '—', caption: 'web 业务源' });
  }
  return list;
});

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
    has: vals.length > 0,
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
  try {
    const res: any = await getAssistantStatus();
    const d = res?.data ?? res;
    assistantReady.value = !!(d?.enabled && d?.modelConfigured);
  } catch {
    assistantReady.value = false;
  }
}

async function loadStats() {
  stats.value = await statsOverview().catch(() => null);
}

async function loadProfile() {
  if (!canProfile.value) {
    return;
  }
  profile.value = await getOverview('dcs').catch(() => null);
}

async function loadPulse() {
  if (!canAnalytics.value) {
    return;
  }
  try {
    pulseSource.value = 'web';
    const [overview, trendData] = await Promise.all([
      getAnalyticsOverview(pulseSource.value, 7).catch(() => null),
      getAnalyticsTrend(pulseSource.value, 7).catch(() => [])
    ]);
    pulse.value = overview?.current ?? null;
    trend.value = trendData || [];
  } catch {
    pulse.value = null;
    trend.value = [];
  }
}

async function loadLogs() {
  try {
    const res: any = await listChangelog({ pageNum: 1, pageSize: 2 });
    recentLogs.value = res.rows || [];
  } catch {
    recentLogs.value = [];
  }
}

function loadAll() {
  loadAssistant();
  loadStats();
  loadProfile();
  loadPulse();
  loadLogs();
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
    font-size: 11.5px;
    color: var(--app-faint);
  }
}

/* 通用面板 */
.ov-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
  gap: 24px;
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
        font-size: 11.5px;
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
  font-size: 12.5px;
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
    font-size: 11px;
    font-family: var(--el-font-family);
    font-variant-numeric: tabular-nums;
  }
}

.ov-source {
  margin-left: auto;
}

.ov-empty {
  margin: 18px 0 6px;
  font-size: 12.5px;
  color: var(--app-faint);
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
    font-size: 12.5px;
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
      font-size: 13.5px;
      font-weight: 500;
      color: var(--app-text);
      line-height: 1.5;
    }
    p {
      margin: 3px 0 0;
      font-size: 12.5px;
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
    font-size: 11px;
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
