<template>
  <div v-if="allowed" class="auto-ws">
    <header class="auto-top">
      <button class="auto-back" type="button" @click="go('/agent')">
        <IconSpan name="arrow-left" :size="13" /> 返回工作台
      </button>
      <span class="auto-sep" aria-hidden="true"></span>
      <nav class="auto-crumb" aria-label="当前位置">
        <span>工作台</span><span class="sl" aria-hidden="true">/</span><b>自动化</b>
      </nav>
      <span class="auto-scope">到点替你跑一轮，成功的进通知和会话，失败的页面上能看原因</span>
    </header>

    <main class="auto-main">
      <div class="auto-head">
        <div>
          <h1>自动化</h1>
          <p>写一句要它做什么，选个时间；到点用只读工具跑一轮，结果放通知和会话里。</p>
        </div>
        <div class="auto-head-actions">
          <button class="btn-primary" type="button" :disabled="ownCount >= maxPerUser" @click="goNew">
            <IconSpan name="plus" :size="13" /> 新建自动化
          </button>
        </div>
      </div>

      <div class="auto-filters" role="group" aria-label="状态筛选">
        <button
          v-for="f in filters"
          :key="f.key"
          class="chip"
          :class="{ on: filter === f.key }"
          type="button"
          :aria-pressed="filter === f.key"
          @click="filter = f.key"
        >
          {{ f.label }}<template v-if="f.key !== 'ALL'"> {{ countOf(f.key) }}</template>
        </button>
        <span class="auto-quota">共 {{ ownCount }} / {{ maxPerUser }} 条自建</span>
      </div>

      <div v-if="loading" class="auto-empty">正在读取自动化…</div>
      <div v-else-if="failed" class="auto-empty">
        <b>自动化没读出来</b>
        <p>刷新页面再试；还是空的话找管理员看服务日志。</p>
        <button class="btn-ghost" type="button" @click="load()">重试</button>
      </div>
      <div v-else-if="shown.length === 0" class="auto-empty">
        <b>{{ rows.length === 0 ? '还没有自动化' : '这个筛选下没有自动化' }}</b>
        <p v-if="rows.length === 0">建一条试试：比如「每周一早上 9 点，汇总上周新增的重点客户」。</p>
        <button v-if="rows.length === 0 && ownCount < maxPerUser" class="btn-primary" type="button" @click="goNew">
          新建第一条
        </button>
      </div>

      <div v-else class="auto-grid">
        <article
          v-for="row in shown"
          :key="row.id"
          class="auto-card"
          :class="{ builtin: row.builtin === '1', clickable: row.canEdit }"
          @click="openCard(row)"
        >
          <div class="ac-hd">
            <!-- 标题做成真按钮：卡片整体不能再挂 role=button（里面还有开关和操作按钮，嵌套交互） -->
            <button v-if="row.canEdit" class="ac-title ac-title-btn" type="button" :title="row.title" @click.stop="openCard(row)">
              {{ row.title }}
            </button>
            <span v-else class="ac-title" :title="row.title">{{ row.title }}</span>
            <span v-if="row.builtin === '1'" class="ac-badge">内置</span>
            <el-switch
              class="ac-switch"
              size="small"
              :model-value="row.enabled === '1'"
              :disabled="!row.canToggle || toggling === row.id"
              :title="row.builtin === '1' && !row.canToggle ? '内置项由管理员开关' : row.enabled === '1' ? '暂停' : '开启'"
              @click.stop
              @change="(v: string | number | boolean) => onToggle(row, Boolean(v))"
            />
          </div>

          <p class="ac-desc">{{ descOf(row) }}</p>

          <div class="ac-meta">
            <span class="ac-pill">{{ row.scheduleText }}</span>
            <span v-if="row.enabled === '1' && row.nextRunAt" class="ac-last">下次 {{ row.nextRunAt }}</span>
            <span v-else-if="row.enabled !== '1'" class="ac-last">已暂停</span>
          </div>

          <div class="ac-foot">
            <span class="ac-last" :class="statusTone(row.lastStatus)">
              {{ lastText(row) }}
            </span>
            <!-- 失败预警：连续 3 次会停用，得让用户在停用前看见 -->
            <span v-if="row.consecutiveFailures > 0" class="ac-last bad">已连败 {{ row.consecutiveFailures }} 次</span>
            <span v-if="row.runCount > 0">已跑 {{ row.runCount }} 次</span>
            <div v-if="row.canEdit" class="ac-ops" @click.stop>
              <button class="ac-op" type="button" :disabled="runningId === row.id" @click="onRun(row)">
                {{ runningId === row.id ? '已提交…' : '立即运行' }}
              </button>
              <button class="ac-op" type="button" @click="openCard(row)">编辑</button>
              <button class="ac-op warn" type="button" @click="onDelete(row)">删除</button>
            </div>
          </div>
        </article>
      </div>

      <section class="auto-recent" aria-label="最近运行">
        <h2>最近运行</h2>
        <div class="run-list">
          <div v-if="recent.length === 0" class="run-empty">还没有运行记录。开启的自动化到点后会在这里留痕。</div>
          <div v-for="r in recent" :key="r.id" class="run-row">
            <span class="run-name" :title="r.automationTitle || ''">{{ r.automationTitle || '已删除的自动化' }}</span>
            <span v-if="r.triggerType === 'MANUAL'" class="run-time">手动</span>
            <span class="run-time">{{ r.scheduledFor }}</span>
            <span
              class="run-status"
              :class="statusTone(r.status)"
              :title="r.status === 'MISSED' ? '服务当时停了超过 6 小时，这一次没跑' : undefined"
            >{{ statusText(r.status) }}</span>
            <span v-if="r.status === 'SUCCESS' && r.durationMs != null" class="run-time">{{ durationText(r.durationMs) }}</span>
            <span v-if="r.status === 'FAILED' && r.error" class="run-time" :title="r.error">{{ r.error }}</span>
            <!-- 只有真建出会话才有得看：失败在建会话之前时 conversationId 为空 -->
            <button v-if="r.threadId && r.conversationId != null" class="run-link" type="button" @click="openThread(r.threadId)">
              查看会话
            </button>
          </div>
        </div>
      </section>
    </main>
  </div>
  <div v-else class="auto-blocked" aria-hidden="true">
    <!-- 未授权时只留这个真实元素兜底：注释不能和元素做根节点的兄弟——那会编译成 Fragment，
         路由过渡收不到 afterLeave，离开本页整页白屏（2026-11-05 设置页同款 P0；题6 of 14） -->
  </div>
</template>

<script setup lang="ts">
/**
 * 自动化一级页（2026-11-05 拍板 题7 乙）：卡片列表 + 状态筛选 + 整页新建/编辑。
 * - 只看自己的自建项 + 本租户内置摘要；每人 5 条（服务端硬校验，这里只做按钮置灰）。
 * - 内置项（每日摘要）只读，只有管理员能开关；成员看得到、点不动，不做假开关。
 * - 结果会话不在这里内嵌：点通知/「查看会话」回工作台打开（/agent?thread=…）。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import IconSpan from '@/components/DcsAssistant/IconSpan.vue';
import { useUserStore } from '@/store/modules/user';
import {
  deleteAutomation,
  listAutomations,
  listRecentAutomationRuns,
  runAutomation,
  toggleAutomation,
  toggleBuiltinAutomation,
  type AutomationRow,
  type AutomationRunRow
} from '@/api/dcs/assistant/automation';

const router = useRouter();
const userStore = useUserStore();

const MAX_PER_USER = 5;

const rows = ref<AutomationRow[]>([]);
const recent = ref<AutomationRunRow[]>([]);
const loading = ref(true);
const failed = ref(false);
const filter = ref<'ALL' | 'ENABLED' | 'PAUSED' | 'FAILED'>('ALL');
const toggling = ref<number | string | null>(null);
/** 单行「立即运行」的提交闸：请求在途时按钮不能再点（连点会排出两条运行记录） */
const runningId = ref<number | string | null>(null);

const filters = [
  { key: 'ALL' as const, label: '全部' },
  { key: 'ENABLED' as const, label: '进行中' },
  { key: 'PAUSED' as const, label: '已暂停' },
  { key: 'FAILED' as const, label: '失败' }
];

const perms = computed(() => userStore.permissions);
const allowed = computed(
  () => perms.value.includes('*:*:*') || (perms.value.includes('agentws:workspace:use') && perms.value.includes('dcs:assistant:automation'))
);

const ownCount = computed(() => rows.value.filter((r) => r.builtin !== '1').length);
const maxPerUser = MAX_PER_USER;
const shown = computed(() => rows.value.filter((r) => matches(r, filter.value)));

/** 按 VO 的 enabled 判（内置项的真值来自租户配置，表里的 enabled 列只是占位） */
function matches(r: AutomationRow, key: string): boolean {
  if (key === 'ENABLED') return r.enabled === '1';
  if (key === 'PAUSED') return r.enabled !== '1';
  if (key === 'FAILED') return r.lastStatus === 'FAILED';
  return true;
}

function countOf(key: string): number {
  return rows.value.filter((r) => matches(r, key)).length;
}

function descOf(r: AutomationRow): string {
  if (r.builtin === '1') {
    return '每天 08:00 把还没交任务的人汇总，推给任务发布人。只能开关，不能改提示词。';
  }
  return r.prompt;
}

function statusText(s: string | null): string {
  if (s === 'SUCCESS') return '成功';
  if (s === 'FAILED') return '失败';
  if (s === 'MISSED') return '错过';
  if (s === 'RUNNING') return '运行中';
  return '还没跑过';
}

function statusTone(s: string | null): string {
  if (s === 'SUCCESS') return 'ok';
  if (s === 'FAILED') return 'bad';
  if (s === 'MISSED') return 'warn';
  if (s === 'RUNNING') return 'live';
  return '';
}

function lastText(r: AutomationRow): string {
  if (r.lastStatus === 'RUNNING') return '正在运行…';
  if (!r.lastStatus) return '还没跑过';
  const when = r.lastRunAt ? ` · ${r.lastRunAt}` : '';
  return `上次${statusText(r.lastStatus)}${when}`;
}

function durationText(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  return `${Math.floor(s / 60)}m${String(s % 60).padStart(2, '0')}s`;
}

function go(path: string) {
  router.push(path);
}

function goNew() {
  if (ownCount.value >= MAX_PER_USER) {
    ElMessage.warning(`每人最多 ${MAX_PER_USER} 条自动化，先删掉一条再建`);
    return;
  }
  router.push('/agent/automation/new');
}

function openCard(r: AutomationRow) {
  if (!r.canEdit) return;
  router.push(`/agent/automation/${r.id}`);
}

function openThread(threadId: string | null) {
  if (!threadId) return;
  router.push({ path: '/agent', query: { thread: threadId } });
}

/** silent=true 用于轮询：只换数据，不把整块卡片区换成「正在读取自动化…」 */
async function load(silent = false) {
  if (!silent) {
    loading.value = true;
    failed.value = false;
  }
  const [listRes, recentRes] = await Promise.allSettled([listAutomations(), listRecentAutomationRuns(6)]);
  if (listRes.status === 'fulfilled') {
    rows.value = ((listRes.value as unknown as { data?: AutomationRow[] })?.data ?? []) as AutomationRow[];
  } else if (!silent) {
    failed.value = true;
    rows.value = [];
  }
  recent.value =
    recentRes.status === 'fulfilled'
      ? (((recentRes.value as unknown as { data?: AutomationRunRow[] })?.data ?? []) as AutomationRunRow[])
      : [];
  loading.value = false;
}

async function onToggle(row: AutomationRow, enabled: boolean) {
  toggling.value = row.id;
  try {
    const res =
      row.builtin === '1'
        ? await toggleBuiltinAutomation(enabled)
        : await toggleAutomation(row.id, enabled);
    const next = (res as unknown as { data?: AutomationRow })?.data;
    if (next) {
      const idx = rows.value.findIndex((r) => String(r.id) === String(row.id));
      if (idx >= 0) rows.value[idx] = next;
    }
    ElMessage.success(enabled ? '已开启' : '已暂停');
  } catch {
    // 错误 toast 由 request 拦截器统一弹
  } finally {
    toggling.value = null;
  }
}

async function onRun(row: AutomationRow) {
  if (runningId.value != null) return;
  runningId.value = row.id;
  try {
    await runAutomation(row.id);
    ElMessage.success('已提交，跑完会发站内通知');
    await load(true);
    startPoll();
  } catch {
    // 409/错误提示由拦截器弹
  } finally {
    runningId.value = null;
  }
}

async function onDelete(row: AutomationRow) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.title}」？删除后不再到点运行，已有的运行记录也一并隐藏。`,
      '删除自动化',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    );
  } catch {
    return;
  }
  try {
    await deleteAutomation(row.id);
    ElMessage.success('已删除');
    await load();
  } catch {
    // 拦截器统一提示
  }
}

let pollTimer: ReturnType<typeof setInterval> | null = null;
function startPoll() {
  if (pollTimer) return;
  pollTimer = setInterval(async () => {
    // 静默刷：先刷再判，跑完的那一 tick 也要把状态与次数带回来，
    // 但不要把卡片区整块换成「正在读取自动化…」（否则按钮每 10 秒消失一次）
    await load(true);
    if (!rows.value.some((r) => r.lastStatus === 'RUNNING')) stopPoll();
  }, 10000);
}
function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

onMounted(async () => {
  if (!allowed.value) {
    router.replace('/403');
    return;
  }
  await load();
  startPoll();
});

onBeforeUnmount(stopPoll);
</script>

<style lang="scss" scoped>
@use '@/assets/styles/dcs-agent-automation.scss';
</style>
