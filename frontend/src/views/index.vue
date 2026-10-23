<template>
  <div class="app-container cockpit">
    <!-- 顶部问候条 + 引擎状态灯（窄条，不做大横幅） -->
    <header class="ck-hero">
      <div class="ck-hero__main">
        <p class="ck-hero__date">{{ today }}</p>
        <h1 class="ck-hero__title">{{ greeting }}，{{ userStore.name }}</h1>
        <p class="ck-hero__sub">画像引擎与 AI 助手已就位——今天从一张图、一句话开始。</p>
      </div>
      <div class="ck-hero__status">
        <span class="ck-chip" :class="assistantReady ? 'is-on' : 'is-off'">
          <i class="ck-dot" />AI 助手 · {{ assistantReady ? '就绪' : '待配置' }}
        </span>
        <span class="ck-chip" :class="profile ? 'is-on' : 'is-off'">
          <i class="ck-dot" />画像引擎 · {{ profile ? `最近计算 ${relTime(profile.lastCalcTime)}` : '暂无数据' }}
        </span>
        <el-button class="ck-refresh" icon="Refresh" circle plain aria-label="刷新数据" @click="loadAll" />
      </div>
    </header>

    <!-- 双引擎主卡：助手（左，宽）+ 画像（右，窄） -->
    <div class="ck-engines">
      <section class="ck-engine ck-engine--ai">
        <header class="ck-engine__head">
          <span class="ck-engine__ic">🤖</span>
          <div>
            <h2>AI 助手</h2>
            <p>一句话圈人、看数、建任务；执行前会先请你确认。</p>
          </div>
          <span class="ck-tier">{{ assistantReady ? '已连接' : '未配置' }}</span>
        </header>
        <div class="ck-ask">
          <el-input v-model="askText" class="ck-ask__input" placeholder="比如：帮我圈出近 30 天没动静的人" clearable @keyup.enter="onAskEnter" />
          <el-button type="primary" class="ck-ask__btn" :disabled="!assistantReady" @click="ask()">问助手</el-button>
        </div>
        <div class="ck-prompts">
          <button v-for="p in prompts" :key="p" type="button" class="ck-prompt" @click="ask(p)">{{ p }}</button>
        </div>
      </section>

      <section v-if="canProfile" class="ck-engine ck-engine--profile">
        <header class="ck-engine__head">
          <span class="ck-engine__ic">👤</span>
          <div>
            <h2>画像引擎</h2>
            <p>评分、分群、复核，一眼看清主体健康度。</p>
          </div>
        </header>
        <div class="ck-metrics">
          <div class="ck-metric">
            <b>{{ profile?.subjectCount ?? '—' }}</b>
            <span>画像主体</span>
          </div>
          <div class="ck-metric">
            <b>{{ profile?.avgScore != null ? Number(profile.avgScore).toFixed(1) : '—' }}</b>
            <span>平均分</span>
          </div>
          <div class="ck-metric is-warn">
            <b>{{ profile?.highRisk ?? '—' }}</b>
            <span>低分主体</span>
          </div>
        </div>
        <div class="ck-links">
          <button type="button" @click="go('/insight/profile')">用户画像</button>
          <button type="button" @click="go('/insight/profile-studio')">画像工作室</button>
          <button v-if="canAnalytics" type="button" @click="go('/insight/profile-analytics')">数据分析</button>
        </div>
      </section>
    </div>

    <!-- 数据脉搏：分析中心迷你指标 + 7 日 PV 柱 -->
    <section v-if="canAnalytics" class="ck-pulse">
      <header class="ck-pulse__head">
        <div>
          <h2>
            数据脉搏 <span>近 7 天 · {{ pulseSource }}</span>
          </h2>
          <p>来源：web 业务源事件账本，实时汇总</p>
        </div>
        <button type="button" class="ck-more" @click="go('/insight/profile-analytics')">打开分析中心 →</button>
      </header>
      <div class="ck-pulse__body">
        <div class="ck-pulse__nums">
          <div v-for="m in pulseMetrics" :key="m.label" class="ck-pulse__num">
            <b>{{ m.value }}</b>
            <span>{{ m.label }}</span>
          </div>
        </div>
        <div class="ck-spark" role="img" aria-label="近 7 天浏览量">
          <i v-for="(bar, i) in sparkBars" :key="i" :style="{ height: bar + '%' }" :title="`PV ${trend[i]?.pv ?? 0}`" />
        </div>
      </div>
      <div class="ck-pulse__foot">
        <span>跳出率 {{ pulse?.bounceRate ?? '—' }}%</span>
        <span>每次访问 {{ pulse?.pagesPerVisit ?? '—' }} 页</span>
        <span>平均停留 {{ avgDuration }}</span>
      </div>
    </section>

    <!-- 底部：任务示例（降权）+ 更新日志摘要 -->
    <div class="ck-bottom">
      <section class="ck-card">
        <header class="ck-card__head">
          <h2>任务采集 <span class="ck-tag">内置示例</span></h2>
          <button type="button" class="ck-more" @click="go('/dcs/todo')">进入任务中心 →</button>
        </header>
        <div class="ck-task-row">
          <button type="button" class="ck-task" @click="go('/dcs/todo')">
            <b>{{ stats?.member?.todo ?? 0 }}</b
            ><span>待我完成</span>
          </button>
          <button type="button" class="ck-task" @click="go('/dcs/todo')">
            <b>{{ stats?.member?.pendingAudit ?? 0 }}</b
            ><span>已提交待审</span>
          </button>
          <button type="button" class="ck-task" @click="go('/dcs/notice')">
            <b>{{ stats?.member?.unreadNotices ?? 0 }}</b
            ><span>未读消息</span>
          </button>
        </div>
        <p class="ck-card__tip">多任务采集是平台内置演示能力；画像与助手才是这里的双引擎。</p>
      </section>

      <section class="ck-card">
        <header class="ck-card__head">
          <h2>最近更新</h2>
          <button type="button" class="ck-more" @click="go('/support/changelog')">全部日志 →</button>
        </header>
        <ul class="ck-log">
          <li v-for="entry in recentLogs" :key="entry.logId">
            <span class="ck-log__ver">{{ entry.version }}</span>
            <div>
              <b>{{ entry.title }}</b>
              <p>{{ entry.itemList?.[0]?.text }}</p>
            </div>
          </li>
          <li v-if="recentLogs.length === 0" class="ck-log__empty">暂无更新记录</li>
        </ul>
      </section>
    </div>
  </div>
</template>

<script setup name="Index" lang="ts">
import { getAnalyticsOverview, getAnalyticsTrend, type AnalyticsOverview } from '@/api/dcs/core/analytics';
import { getOverview, type ProfileOverview } from '@/api/dcs/core/profile';
import { statsOverview, type DcsStatsOverview } from '@/api/dcs/core/task';
import { getAssistantStatus } from '@/api/dcs/assistant';
import { listChangelog } from '@/api/dcs/changelog';
import type { ChangelogRow } from '@/api/dcs/changelog/types';
import { checkPermi } from '@/utils/permission';
import { useUserStore } from '@/store/modules/user';

const userStore = useUserStore();
const router = useRouter();

const stats = ref<DcsStatsOverview | null>(null);
const profile = ref<ProfileOverview | null>(null);
const pulse = ref<AnalyticsOverview['current'] | null>(null);
const trend = ref<Array<{ pv: number }>>([]);
const pulseSource = ref('web');
const assistantReady = ref(false);
const askText = ref('');

const canProfile = computed(() => checkPermi(['profile:score:query']));
const canAnalytics = computed(() => checkPermi(['analytics:center:query']));

const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' });
const greeting = new Date().getHours() < 12 ? '早上好' : new Date().getHours() < 18 ? '下午好' : '晚上好';

const prompts = ['近 30 天谁最不活跃？', '本周流量趋势怎么样？', '我有哪些待办？'];

const pulseMetrics = computed(() => [
  { label: '浏览量 PV', value: pulse.value?.pv ?? 0 },
  { label: '访客 UV', value: pulse.value?.uv ?? 0 },
  { label: '会话', value: pulse.value?.visits ?? 0 },
  { label: '事件', value: pulse.value?.events ?? 0 }
]);

const maxPv = computed(() => Math.max(1, ...trend.value.map((t) => Number(t.pv) || 0)));
const sparkBars = computed(() => {
  const list = trend.value.slice(-7);
  if (!list.length) {
    return [8, 8, 8, 8, 8, 8, 8];
  }
  return list.map((t) => Math.max(8, Math.round(((Number(t.pv) || 0) / maxPv.value) * 100)));
});

const avgDuration = computed(() => {
  const sec = Number(pulse.value?.avgDuration ?? 0);
  if (!sec) {
    return '—';
  }
  return sec >= 60 ? `${Math.round(sec / 60)} 分钟` : `${Math.round(sec)} 秒`;
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
  --ck-line: var(--dcs-border-color-lighter, #e4e7ed);
  --ck-soft: #f7f9fc;
  --ck-ink: var(--dcs-text-primary, #1f2d3d);
  --ck-sub: var(--dcs-text-placeholder, #8a94a6);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 顶部问候条 */
.ck-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 20px 22px;
  border-radius: 16px;
  border: 1px solid var(--ck-line);
  background: radial-gradient(900px 200px at 8% -30%, rgba(59, 130, 246, 0.14), transparent 60%), linear-gradient(135deg, #f8fbff, #fff);
}
.ck-hero__date {
  margin: 0 0 4px;
  font-size: 12.5px;
  color: var(--ck-sub);
}
.ck-hero__title {
  margin: 0;
  font-size: 23px;
  line-height: 1.35;
  color: var(--ck-ink);
}
.ck-hero__sub {
  margin: 6px 0 0;
  font-size: 13.5px;
  color: var(--ck-sub);
}
.ck-hero__status {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ck-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid var(--ck-line);
  background: #fff;
  font-size: 12.5px;
  color: var(--ck-sub);
  white-space: nowrap;
  .ck-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #c0c4cc;
  }
  &.is-on {
    color: #0e9f6e;
    border-color: #b7e4cd;
    background: #f4fcf8;
    .ck-dot {
      background: #0e9f6e;
      box-shadow: 0 0 0 3px rgba(14, 159, 110, 0.14);
    }
  }
}

/* 双引擎 */
.ck-engines {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(280px, 0.85fr);
  gap: 16px;
}
.ck-engine {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px 20px;
  border-radius: 16px;
  border: 1px solid var(--ck-line);
  background: #fff;
  box-shadow: 0 1px 2px rgba(16, 34, 64, 0.03);
  &--ai {
    border-color: #cfe0ff;
    background: linear-gradient(180deg, #f7faff, #fff 42%);
  }
  &--profile {
    border-color: #d9ecdf;
    background: linear-gradient(180deg, #f7fdf9, #fff 42%);
  }
  &__head {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    h2 {
      margin: 0;
      font-size: 17px;
      color: var(--ck-ink);
    }
    p {
      margin: 3px 0 0;
      font-size: 12.5px;
      line-height: 1.6;
      color: var(--ck-sub);
    }
  }
  &__ic {
    flex: none;
    width: 36px;
    height: 36px;
    display: grid;
    place-items: center;
    border-radius: 10px;
    background: #fff;
    border: 1px solid var(--ck-line);
    font-size: 18px;
  }
}
.ck-tier {
  margin-left: auto;
  flex: none;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 11.5px;
  color: #2563eb;
  background: #e8f1ff;
}
.ck-ask {
  display: flex;
  gap: 8px;
  &__input {
    flex: 1;
  }
  &__btn {
    flex: none;
  }
}
.ck-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.ck-prompt {
  border: 1px dashed #c9d8f4;
  background: #fff;
  color: #33507f;
  border-radius: 999px;
  padding: 6px 13px;
  font-size: 12.5px;
  line-height: 1.5;
  cursor: pointer;
  transition: all 0.16s ease;
  &:hover {
    border-style: solid;
    border-color: #3b82f6;
    color: #1d4ed8;
    background: #f5f9ff;
  }
}

.ck-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.ck-metric {
  padding: 10px 12px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid var(--ck-line);
  b {
    display: block;
    font-size: 24px;
    line-height: 1.2;
    color: var(--ck-ink);
    font-variant-numeric: tabular-nums;
  }
  span {
    font-size: 11.5px;
    color: var(--ck-sub);
  }
  &.is-warn b {
    color: #b8860b;
  }
}
.ck-links {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  button {
    border: 1px solid var(--ck-line);
    background: #fff;
    border-radius: 8px;
    padding: 6px 12px;
    font-size: 12.5px;
    color: #33507f;
    cursor: pointer;
    &:hover {
      border-color: #0e9f6e;
      color: #0e9f6e;
    }
  }
}

/* 数据脉搏 */
.ck-pulse {
  padding: 16px 20px 14px;
  border-radius: 16px;
  border: 1px solid var(--ck-line);
  background: #fff;
  &__head {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 12px;
    h2 {
      margin: 0;
      font-size: 16px;
      color: var(--ck-ink);
      span {
        margin-left: 6px;
        font-size: 11.5px;
        font-weight: 400;
        color: var(--ck-sub);
      }
    }
    p {
      margin: 3px 0 0;
      font-size: 12px;
      color: var(--ck-sub);
    }
  }
  &__body {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 220px;
    gap: 20px;
    align-items: end;
    margin-top: 12px;
  }
  &__nums {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 10px;
  }
  &__num {
    b {
      display: block;
      font-size: 22px;
      line-height: 1.2;
      color: var(--ck-ink);
      font-variant-numeric: tabular-nums;
    }
    span {
      font-size: 11.5px;
      color: var(--ck-sub);
    }
  }
  &__foot {
    display: flex;
    gap: 18px;
    margin-top: 10px;
    padding-top: 10px;
    border-top: 1px dashed var(--ck-line);
    font-size: 12px;
    color: var(--ck-sub);
  }
}
.ck-spark {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  height: 62px;
  padding-bottom: 2px;
  i {
    flex: 1;
    min-width: 8px;
    border-radius: 5px 5px 2px 2px;
    background: linear-gradient(180deg, #6aa5ff, #dbe9ff);
    transition: height 0.3s ease;
  }
}

/* 底部两卡 */
.ck-bottom {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}
.ck-card {
  padding: 16px 20px;
  border-radius: 16px;
  border: 1px solid var(--ck-line);
  background: #fff;
  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    h2 {
      margin: 0;
      font-size: 15.5px;
      color: var(--ck-ink);
    }
  }
  &__tip {
    margin: 12px 0 0;
    font-size: 12px;
    color: var(--ck-sub);
  }
}
.ck-tag {
  margin-left: 6px;
  padding: 1px 8px;
  border-radius: 999px;
  background: #f4f4f5;
  color: #909399;
  font-size: 11px;
  font-weight: 400;
}
.ck-more {
  border: 0;
  background: transparent;
  color: #3b82f6;
  font-size: 12.5px;
  cursor: pointer;
  padding: 0;
  &:hover {
    text-decoration: underline;
  }
}
.ck-task-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 12px;
}
.ck-task {
  text-align: left;
  padding: 11px 14px;
  border-radius: 12px;
  border: 1px solid var(--ck-line);
  background: var(--ck-soft);
  cursor: pointer;
  transition: all 0.16s ease;
  b {
    display: block;
    font-size: 24px;
    line-height: 1.2;
    color: var(--ck-ink);
  }
  span {
    font-size: 11.5px;
    color: var(--ck-sub);
  }
  &:hover {
    border-color: #93c5fd;
    background: #f5f9ff;
  }
}
.ck-log {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  li {
    display: flex;
    gap: 10px;
    padding: 8px 0;
    & + li {
      border-top: 1px dashed var(--ck-line);
    }
    b {
      display: block;
      font-size: 13px;
      color: var(--ck-ink);
      line-height: 1.5;
    }
    p {
      margin: 2px 0 0;
      font-size: 12px;
      color: var(--ck-sub);
      line-height: 1.55;
    }
  }
}
.ck-log__empty {
  display: block !important;
  font-size: 12.5px;
  color: var(--ck-sub);
}
.ck-log__ver {
  flex: none;
  height: 20px;
  padding: 0 8px;
  border-radius: 999px;
  background: #e8f1ff;
  color: #2563eb;
  font-size: 11px;
  line-height: 20px;
}

@media (max-width: 992px) {
  .ck-engines,
  .ck-bottom,
  .ck-pulse__body {
    grid-template-columns: 1fr;
  }
  .ck-pulse__body {
    gap: 12px;
  }
  .ck-spark {
    height: 48px;
  }
}
@media (max-width: 768px) {
  .cockpit {
    gap: 12px;
  }
  .ck-hero {
    padding: 16px;
  }
  .ck-hero__title {
    font-size: 20px;
  }
  .ck-engines,
  .ck-bottom {
    gap: 12px;
  }
  .ck-engine,
  .ck-pulse,
  .ck-card {
    padding: 14px;
    border-radius: 14px;
  }
  .ck-ask {
    flex-direction: column;
  }
  .ck-ask__btn {
    width: 100%;
    height: 44px;
  }
  .ck-pulse__nums {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .ck-task-row {
    grid-template-columns: 1fr;
  }
  .ck-task {
    display: flex;
    align-items: baseline;
    gap: 10px;
    min-height: 44px;
  }
}
</style>
