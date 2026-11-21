<template>
  <div v-if="allowed" class="auto-ws">
    <main class="auto-main">
      <!-- 行1：标题 + 副标题（不叫「工作流」——文案写我们真正有的功能） -->
      <div class="auto-head">
        <div>
          <h1>自动化</h1>
          <p>按计划跑一段只读查询，结果进通知和会话</p>
        </div>
        <!-- 行3 右上：刷新图标 + 深色「创建新任务 ▾」下拉（新建定时任务 / 从模板建） -->
        <div class="auto-head-actions">
          <button class="icon-btn" type="button" :disabled="loading" title="刷新" aria-label="刷新" @click="reload()">
            <IconSpan :name="loading ? 'loader-circle' : 'refresh'" :size="14" />
          </button>
          <div class="create-drop" :class="{ open: createOpen }">
            <button
              class="btn-primary"
              type="button"
              :disabled="ownCount >= maxPerUser"
              :title="ownCount >= maxPerUser ? `每人最多 ${maxPerUser} 条自动化，先删掉一条再建` : undefined"
              aria-haspopup="menu"
              :aria-expanded="createOpen"
              @click="toggleCreate"
            >
              <IconSpan name="plus" :size="13" /> 创建新任务 <IconSpan name="chevron-down" :size="12" />
            </button>
            <div v-if="createOpen" class="drop-menu" role="menu">
              <button type="button" role="menuitem" @click="goNew()">新建定时任务</button>
              <button type="button" role="menuitem" @click="jumpTemplates()">从模板建</button>
            </div>
          </div>
        </div>
      </div>

      <!-- 行2：两个标签页——「闲时任务」点开是诚实的空态，不是假数据 -->
      <div class="ag-tabs" role="tablist" aria-label="自动化类型">
        <button type="button" role="tab" :aria-selected="tab === 'cron'" :class="{ on: tab === 'cron' }" @click="tab = 'cron'">定时任务</button>
        <button type="button" role="tab" :aria-selected="tab === 'idle'" :class="{ on: tab === 'idle' }" @click="tab = 'idle'">闲时任务</button>
      </div>

      <template v-if="tab === 'cron'">
        <!-- 行3：五筛选（全部/进行中/已完成/已暂停/失败——「已完成」=最近一次运行成功）+ 配额 -->
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

        <!-- 行5：提示行（纯文字，无开关——网页没有「保持唤醒」这能力，画个开关是骗人） -->
        <p class="tip-line">到点跑一轮，结果进通知和会话</p>

        <div v-if="loading" class="auto-empty">正在读取自动化…</div>
        <div v-else-if="failed" class="auto-empty">
          <b>自动化没读出来</b>
          <p>刷新页面再试；还是空的话找管理员看服务日志。</p>
          <button class="btn-ghost" type="button" @click="reload()">重试</button>
        </div>
        <div v-else-if="shown.length === 0" class="auto-empty">
          <b>{{ rows.length === 0 ? '还没有自动化' : '这个筛选下没有自动化' }}</b>
          <p v-if="rows.length === 0">建一条试试：比如「每周一早上 9 点，汇总上周新增的重点客户」。</p>
          <button v-if="rows.length === 0 && ownCount < maxPerUser" class="btn-primary" type="button" @click="goNew()">
            新建第一条
          </button>
        </div>

        <!-- 行6：两列卡——标题/说明两行/状态徽标/已跑 N 次/下次运行；开关与操作保留（能力不倒退） -->
        <div v-else class="auto-grid">
          <article
            v-for="row in shown"
            :key="row.id"
            class="auto-card"
            :class="{ builtin: row.builtin === '1', clickable: row.canEdit }"
            @click="openCard(row)"
          >
            <div class="ac-hd">
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
              <span class="ac-badge-st" :class="badgeOf(row).cls">{{ badgeOf(row).text }}</span>
              <span v-if="row.runCount > 0" class="ac-last">已跑 {{ row.runCount }} 次</span>
              <span v-if="row.consecutiveFailures > 0" class="ac-last bad" title="连续 3 次失败会自动停用">已连败 {{ row.consecutiveFailures }} 次</span>
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

        <!-- 行7：模板四卡——点一下带真实提示词预填新建页（板上：没有真提示词就是四张空卡） -->
        <section id="auto-templates" class="tpl-sec" aria-label="定时任务模板">
          <div class="tpl-hd">
            <b>定时任务模板</b>
            <span>点一下，带上提示词和时间去新建</span>
          </div>
          <div class="tpl-grid">
            <button v-for="t in templates" :key="t.key" class="tpl-card" type="button" @click="goTemplate(t.key)">
              <span class="tpl-tile" aria-hidden="true">{{ t.title.slice(0, 1) }}</span>
              <span class="tpl-body">
                <b>{{ t.title }}</b>
                <span class="tpl-desc">{{ t.desc }}</span>
                <span class="tpl-time">{{ templateTimeText(t) }}</span>
              </span>
            </button>
          </div>
        </section>
      </template>

      <!-- 闲时任务：能力还没有——诚实空态，不放假数据 -->
      <div v-else class="auto-empty idle-empty">
        <b>暂不支持闲时跑</b>
        <p>闲时（比如深夜服务空闲时）自动跑的能力还没上线；现在只能按「每天 / 每周几 + 时间」定时跑。</p>
      </div>
    </main>
  </div>
  <div v-else class="auto-blocked" aria-hidden="true">
    <!-- 未授权时只留这个真实元素兜底：注释不能和元素做根节点的兄弟——那会编译成 Fragment，
         路由过渡收不到 afterLeave，离开本页整页白屏（2026-11-05 设置页同款 P0；题6 of 14） -->
  </div>
</template>

<script setup lang="ts">
/**
 * 自动化列表（批 A Q3 · 2026-11-14 拍板案1，照 ZCode 交互重写）：
 * 渲染在工作台主区槽内（/agent 的子路由），左栏/顶栏不动、不出面包屑——点侧栏「自动化」
 * 只换中间这块。元素七行照板：标题副标题 / 定时·闲时两页签 / 五筛选+刷新+创建下拉 /
 * 纯文字提示行 / 两列卡 / 模板四卡。数据全走既有接口（列表/开关/运行/删除），
 * 「已完成」筛选=最近一次运行 SUCCESS（后端 filter 同步补了 SUCCESS 档）。
 * 「最近运行」表已并入编辑页「历史」页签（Q4），本页不再重复。
 */
import { computed, onBeforeUnmount, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import IconSpan from '@/components/Assistant/IconSpan.vue';
import { useUserStore } from '@/store/modules/user';
import { AUTOMATION_TEMPLATES, type AutomationTemplate } from './templates';
import {
  deleteAutomation,
  listAutomations,
  runAutomation,
  toggleAutomation,
  toggleBuiltinAutomation,
  type AutomationRow
} from '@/api/smartdock/assistant/automation';

const router = useRouter();
const userStore = useUserStore();

const MAX_PER_USER = 5;

const rows = ref<AutomationRow[]>([]);
const loading = ref(true);
const failed = ref(false);
/** 批 A Q3：两页签（闲时=诚实空态）+ 五筛选（补「已完成」=最近一次 SUCCESS） */
const tab = ref<'cron' | 'idle'>('cron');
const filter = ref<'ALL' | 'ENABLED' | 'SUCCESS' | 'PAUSED' | 'FAILED'>('ALL');
const createOpen = ref(false);
const templates = AUTOMATION_TEMPLATES;
const toggling = ref<number | string | null>(null);
/** 单行「立即运行」的提交闸：请求在途时按钮不能再点（连点会排出两条运行记录） */
const runningId = ref<number | string | null>(null);

const filters = [
  { key: 'ALL' as const, label: '全部' },
  { key: 'ENABLED' as const, label: '进行中' },
  { key: 'SUCCESS' as const, label: '已完成' },
  { key: 'PAUSED' as const, label: '已暂停' },
  { key: 'FAILED' as const, label: '失败' }
];

const perms = computed(() => userStore.permissions);
const allowed = computed(
  () => perms.value.includes('*:*:*') || (perms.value.includes('agentws:workspace:use') && perms.value.includes('smartdock:assistant:automation'))
);

const ownCount = computed(() => rows.value.filter((r) => r.builtin !== '1').length);
const maxPerUser = MAX_PER_USER;
const shown = computed(() => rows.value.filter((r) => matches(r, filter.value)));

/** 状态徽标（板上三态=已完成/进行中/失败；保留我们真实存在的已暂停/错过/运行中态） */
function badgeOf(r: AutomationRow): { text: string; cls: string } {
  if (r.builtin !== '1' && r.enabled !== '1') {
    return { text: '已暂停', cls: 'st-paused' };
  }
  if (r.lastStatus === 'SUCCESS') return { text: '已完成', cls: 'st-ok' };
  if (r.lastStatus === 'FAILED') return { text: '失败', cls: 'st-bad' };
  if (r.lastStatus === 'RUNNING') return { text: '运行中', cls: 'st-live' };
  if (r.lastStatus === 'MISSED') return { text: '错过', cls: 'st-warn' };
  return { text: '还没跑过', cls: 'st-none' };
}

/** 按 VO 的 enabled 判（内置项的真值来自租户配置，表里的 enabled 列只是占位） */
function matches(r: AutomationRow, key: string): boolean {
  // 批A三审 P1：四个状态档与后端 filter 同口径=只筛自建项（mapper 四档都带 builtin='0'），
  // 内置项只在「全部」里出现——否则「已完成 N」的 N 与后端 filter=SUCCESS 的条数对不上
  if (key !== 'ALL' && r.builtin === '1') return false;
  if (key === 'ENABLED') return r.enabled === '1';
  if (key === 'SUCCESS') return r.lastStatus === 'SUCCESS';
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

function toggleCreate() {
  if (ownCount.value >= MAX_PER_USER) {
    ElMessage.warning(`每人最多 ${MAX_PER_USER} 条自动化，先删掉一条再建`);
    return;
  }
  createOpen.value = !createOpen.value;
}

/** 点外面收起「创建新任务」下拉 */
function onDocClick(e: MouseEvent) {
  if (!(e.target as HTMLElement).closest?.('.create-drop')) {
    createOpen.value = false;
  }
}

function templateTimeText(t: AutomationTemplate): string {
  if (t.scheduleType === 'DAILY') return `每天 ${t.scheduleTime}`;
  const names = ['一', '二', '三', '四', '五', '六', '日'];
  return `每周${t.weekdays.map((d) => names[d - 1]).join('、')} ${t.scheduleTime}`;
}

function goNew() {
  if (ownCount.value >= MAX_PER_USER) {
    ElMessage.warning(`每人最多 ${MAX_PER_USER} 条自动化，先删掉一条再建`);
    return;
  }
  router.push('/agent/automation/new');
}

function goTemplate(key: string) {
  if (ownCount.value >= MAX_PER_USER) {
    ElMessage.warning(`每人最多 ${MAX_PER_USER} 条自动化，先删掉一条再建`);
    return;
  }
  router.push({ path: '/agent/automation/new', query: { tpl: key } });
}

function jumpTemplates() {
  createOpen.value = false;
  tab.value = 'cron';
  document.getElementById('auto-templates')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function openCard(r: AutomationRow) {
  if (!r.canEdit) return;
  router.push(`/agent/automation/${r.id}`);
}

/** silent=true 用于轮询：只换数据，不把整块卡片区换成「正在读取自动化…」 */
async function load(silent = false) {
  if (!silent) {
    loading.value = true;
    failed.value = false;
  }
  const listRes = await Promise.allSettled([listAutomations()]);
  // 批A三审 P2：开关/立即跑在途时丢弃本帧快照——10s 轮询的旧响应晚落地会把刚点的新状态盖回去
  const busy = toggling.value != null || runningId.value != null;
  if (listRes[0].status === 'fulfilled' && !busy) {
    rows.value = ((listRes[0].value as unknown as { data?: AutomationRow[] })?.data ?? []) as AutomationRow[];
  } else if (listRes[0].status === 'fulfilled' && busy && !silent) {
    loading.value = false;
    return; // 在途动作自己会回填行数据，这里不覆盖
  } else if (!silent) {
    failed.value = true;
    rows.value = [];
  }
  loading.value = false;
}

async function reload() {
  await load();
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
  document.addEventListener('click', onDocClick);
  if (!allowed.value) {
    router.replace('/403');
    return;
  }
  await load();
  startPoll();
});

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick);
  stopPoll();
});
onUnmounted(stopPoll);
</script>

<style lang="scss" scoped>
@use '@/assets/styles/dcs-agent-automation.scss';
</style>
