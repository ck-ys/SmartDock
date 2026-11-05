<template>
  <div v-if="allowed" class="auto-ws">
    <header class="auto-top">
      <button class="auto-back" type="button" @click="goList">
        <IconSpan name="arrow-left" :size="13" /> 自动化
      </button>
      <span class="auto-sep" aria-hidden="true"></span>
      <nav class="auto-crumb" aria-label="当前位置">
        <span>工作台</span><span class="sl" aria-hidden="true">/</span>
        <span class="crumb-link" role="button" tabindex="0" @click="goList" @keydown.enter="goList">自动化</span>
        <span class="sl" aria-hidden="true">/</span><b>{{ isNew ? '新建' : '编辑' }}</b>
      </nav>
      <span class="auto-scope">{{ isNew ? '建好后到点会自动跑' : `已跑 ${row?.runCount ?? 0} 次 · 成功 ${row?.successCount ?? 0} 次` }}</span>
    </header>

    <main class="auto-main">
      <div class="auto-head">
        <div>
          <h1>{{ isNew ? '新建自动化' : row?.title || '编辑自动化' }}</h1>
          <p>自动化只能用只读查询工具；需要人确认的写操作不会执行。</p>
        </div>
      </div>

      <div v-if="!isNew && loading" class="auto-empty">正在读取…</div>
      <div v-else-if="!isNew && failed" class="auto-empty">
        <b>这条自动化没读出来</b>
        <p>它可能已被删除，或你没有查看权限。</p>
        <button class="btn-ghost" type="button" @click="goList">返回列表</button>
      </div>

      <div v-else class="edit-grid">
        <section class="edit-card" aria-label="自动化设置">
          <div class="field">
            <label for="auto-title">名称</label>
            <el-input id="auto-title" v-model="form.title" maxlength="60" show-word-limit placeholder="例如：本周客户跟进盘点" />
          </div>

          <div class="field">
            <label for="auto-prompt">要它做什么</label>
            <el-input
              id="auto-prompt"
              v-model="form.prompt"
              type="textarea"
              :rows="7"
              maxlength="2000"
              show-word-limit
              placeholder="写一句人话，例如：汇总上周新增的重点客户，按跟进阶段分组，标出 3 天没联系的。"
            />
            <p class="field-hint">到点后模型会自己挑合适的技能和只读工具；写操作一律不执行。</p>
          </div>

          <div class="field">
            <label for="auto-time">什么时候跑</label>
            <div class="sched-row">
              <div class="seg" role="group" aria-label="频率">
                <button
                  type="button"
                  :class="{ on: form.scheduleType === 'DAILY' }"
                  :aria-pressed="form.scheduleType === 'DAILY'"
                  @click="setType('DAILY')"
                >每天</button>
                <button
                  type="button"
                  :class="{ on: form.scheduleType === 'WEEKLY' }"
                  :aria-pressed="form.scheduleType === 'WEEKLY'"
                  @click="setType('WEEKLY')"
                >每周</button>
              </div>
              <!-- id/aria-label 会透传到内层 input（EP 的 picker 是 inheritAttrs:false + v-bind="attrs"） -->
              <el-time-picker
                id="auto-time"
                v-model="form.scheduleTime"
                format="HH:mm"
                value-format="HH:mm"
                :clearable="false"
                aria-label="运行时间"
                placeholder="09:00"
                style="width: 116px"
              />
              <span class="field-hint" style="margin: 0">北京时间</span>
            </div>
            <div v-if="form.scheduleType === 'WEEKLY'" class="day-chips" style="margin-top: 10px">
              <button
                v-for="d in week"
                :key="d.value"
                class="day-chip"
                :class="{ on: form.weekdays.includes(d.value) }"
                type="button"
                :aria-pressed="form.weekdays.includes(d.value)"
                @click="toggleDay(d.value)"
              >
                {{ d.label }}
              </button>
            </div>
            <div class="preview-box">
              <template v-if="preview.length">
                <b>接下来 {{ preview.length }} 次：</b>
                <!-- 一条一个 nowrap，换行只发生在日期之间，不把「2026-11-10 09:00」劈成两半 -->
                <span class="pv-times">
                  <span v-for="t in preview" :key="t" class="pv-time">{{ t }}</span>
                </span>
              </template>
              <template v-else>选好时间后这里会列出接下来 5 次。</template>
            </div>
          </div>

          <div class="edit-actions">
            <button class="btn-primary" type="button" :disabled="saving || !valid" @click="save">
              {{ saving ? '保存中…' : isNew ? '创建' : '保存修改' }}
            </button>
            <!-- 按钮置灰时得说清缺什么，否则用户只看到一个点不动的按钮 -->
            <span v-if="invalidHint" class="edit-hint">{{ invalidHint }}</span>
            <button v-if="!isNew" class="btn-ghost" type="button" :disabled="running" @click="runNow">
              {{ running ? '已提交…' : '立即运行一次' }}
            </button>
            <span class="sp"></span>
            <button v-if="!isNew" class="btn-ghost btn-danger" type="button" @click="remove">删除</button>
            <button class="btn-ghost" type="button" @click="goList">返回</button>
          </div>
        </section>

        <aside>
          <div class="side-card">
            <h3>运行历史</h3>
            <div v-if="isNew" class="run-empty" style="padding: 4px 0">创建并跑过之后，这里能看到每次的结果。</div>
            <div v-else-if="runs.length === 0" class="run-empty" style="padding: 4px 0">还没跑过。</div>
            <template v-else>
              <div v-for="r in runs" :key="r.id" class="hist-row">
                <div class="hist-main">
                  <!-- 元信息拆成可换行的独立块：窄侧栏里「13s」被挤成孤零零一行像是坏了 -->
                  <div class="hist-meta">
                    <span
                      :class="'run-status ' + statusTone(r.status)"
                      :title="r.status === 'MISSED' ? '服务当时停了超过 6 小时，这一次没跑' : undefined"
                    >{{ statusText(r.status) }}</span>
                    <span class="run-time">{{ r.scheduledFor }}</span>
                    <span v-if="r.triggerType === 'MANUAL'" class="run-time">手动</span>
                    <span v-if="r.status === 'SUCCESS' && r.durationMs != null" class="run-time">{{ durationText(r.durationMs) }}</span>
                  </div>
                  <p v-if="r.summary" class="hist-sum">{{ r.summary }}</p>
                  <p v-else-if="r.error" class="hist-sum">{{ r.error }}</p>
                </div>
                <!-- 失败在建会话之前时 conversationId 为空，threadId 也指不到东西 -->
                <button v-if="r.threadId && r.conversationId != null" class="run-link" type="button" @click="openThread(r.threadId)">
                  会话
                </button>
              </div>
            </template>
          </div>

          <div class="side-card">
            <h3>边界说明</h3>
            <ul>
              <li>只读运行：查询、汇总可以做；新增/修改/删除一律不执行。</li>
              <li>成功的通知直达结果会话；失败的直达这个自动化页，能看原因、手动重跑。</li>
              <li>失败不自动重试；连续 3 次失败会自动停用，改好再手动打开。</li>
              <li>每人最多 5 条；只能看到自己的自动化。</li>
            </ul>
          </div>
        </aside>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
/**
 * 自动化整页新建/编辑（2026-11-05 拍板 题1 乙/题4 乙/题7 乙）。
 * 日程只收「每天/每周几 + HH:mm」，不碰 cron；右栏是运行历史与边界说明。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import IconSpan from '@/components/DcsAssistant/IconSpan.vue';
import { useUserStore } from '@/store/modules/user';
import {
  createAutomation,
  deleteAutomation,
  getAutomation,
  listAutomationRuns,
  listAutomations,
  previewSchedule,
  runAutomation,
  updateAutomation,
  type AutomationRow,
  type AutomationRunRow
} from '@/api/dcs/assistant/automation';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const MAX_PER_USER = 5;

const week = [
  { label: '一', value: 1 },
  { label: '二', value: 2 },
  { label: '三', value: 3 },
  { label: '四', value: 4 },
  { label: '五', value: 5 },
  { label: '六', value: 6 },
  { label: '日', value: 7 }
];

const id = computed(() => String(route.params.id ?? 'new'));
const isNew = computed(() => id.value === 'new');

const form = reactive({
  title: '',
  prompt: '',
  scheduleType: 'DAILY' as 'DAILY' | 'WEEKLY',
  scheduleTime: '09:00',
  weekdays: [1] as number[]
});

const row = ref<AutomationRow | null>(null);
const runs = ref<AutomationRunRow[]>([]);
const preview = ref<string[]>([]);
const loading = ref(false);
const failed = ref(false);
const saving = ref(false);
const running = ref(false);

const perms = computed(() => userStore.permissions);
const allowed = computed(
  () => perms.value.includes('*:*:*') || (perms.value.includes('agentws:workspace:use') && perms.value.includes('dcs:assistant:automation'))
);

const valid = computed(() => !invalidHint.value);

/** 置灰原因（按钮旁边直接写出来，不让人猜） */
const invalidHint = computed(() => {
  if (!form.title.trim()) return '先起个名字';
  if (!form.prompt.trim()) return '写一句要它做什么';
  if (!form.scheduleTime) return '选一个运行时间';
  if (form.scheduleType === 'WEEKLY' && form.weekdays.length === 0) return '至少选一天';
  return '';
});

function setType(t: 'DAILY' | 'WEEKLY') {
  form.scheduleType = t;
}

function toggleDay(d: number) {
  const i = form.weekdays.indexOf(d);
  if (i >= 0) form.weekdays.splice(i, 1);
  else form.weekdays.push(d);
}

function weekdayParam(): string {
  return [...form.weekdays].sort((a, b) => a - b).join(',');
}

let previewTimer: ReturnType<typeof setTimeout> | null = null;
function schedulePreview() {
  if (previewTimer) clearTimeout(previewTimer);
  previewTimer = setTimeout(async () => {
    if (!form.scheduleTime) {
      preview.value = [];
      return;
    }
    if (form.scheduleType === 'WEEKLY' && form.weekdays.length === 0) {
      preview.value = [];
      return;
    }
    try {
      const res = await previewSchedule({
        scheduleType: form.scheduleType,
        scheduleTime: form.scheduleTime,
        weekdays: form.scheduleType === 'WEEKLY' ? weekdayParam() : undefined
      });
      preview.value = ((res as unknown as { data?: string[] })?.data ?? []) as string[];
    } catch {
      preview.value = [];
    }
  }, 250);
}

watch(() => [form.scheduleType, form.scheduleTime, form.weekdays.join(',')], schedulePreview);

function statusText(s: string): string {
  if (s === 'SUCCESS') return '成功';
  if (s === 'FAILED') return '失败';
  if (s === 'MISSED') return '错过';
  if (s === 'RUNNING') return '运行中';
  return s;
}
function statusTone(s: string): string {
  if (s === 'SUCCESS') return 'ok';
  if (s === 'FAILED') return 'bad';
  if (s === 'MISSED') return 'warn';
  if (s === 'RUNNING') return 'live';
  return '';
}
function durationText(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  return `${Math.floor(s / 60)}m${String(s % 60).padStart(2, '0')}s`;
}

function goList() {
  router.push('/agent/automation');
}

function openThread(threadId: string | null) {
  if (!threadId) return;
  router.push({ path: '/agent', query: { thread: threadId } });
}

async function load() {
  if (isNew.value) {
    row.value = null;
    runs.value = [];
    schedulePreview();
    await checkQuota();
    return;
  }
  loading.value = true;
  failed.value = false;
  try {
    const res = await getAutomation(id.value);
    const data = (res as unknown as { data?: AutomationRow })?.data;
    if (!data) throw new Error('empty');
    if (data.builtin === '1') {
      ElMessage.info('内置项只能开关，不能编辑');
      goList();
      return;
    }
    row.value = data;
    form.title = data.title;
    form.prompt = data.prompt;
    form.scheduleType = data.scheduleType === 'WEEKLY' ? 'WEEKLY' : 'DAILY';
    form.scheduleTime = data.scheduleTime || '09:00';
    form.weekdays = (data.weekdays || '1')
      .split(',')
      .map((v) => Number(v))
      .filter((v) => v >= 1 && v <= 7);
    if (form.weekdays.length === 0) form.weekdays = [1];
    await loadRuns();
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
}

/**
 * 上限预检：直接打开 /agent/automation/new 会绕过列表页的按钮置灰。
 * 读不到就交给服务端兜底（保存时返回 400 并提示），这里不挡人。
 */
async function checkQuota() {
  try {
    const res = await listAutomations();
    const list = ((res as unknown as { data?: AutomationRow[] })?.data ?? []) as AutomationRow[];
    if (list.filter((r) => r.builtin !== '1').length >= MAX_PER_USER) {
      ElMessage.warning(`每人最多 ${MAX_PER_USER} 条自动化，先删掉一条再建`);
      goList();
    }
  } catch {
    // 交给服务端校验
  }
}

async function loadRuns() {
  if (isNew.value) return;
  const res = await listAutomationRuns(id.value, 20);
  runs.value = ((res as unknown as { data?: AutomationRunRow[] })?.data ?? []) as AutomationRunRow[];
}

async function save() {
  if (!valid.value || saving.value) return;
  saving.value = true;
  const payload = {
    title: form.title.trim(),
    prompt: form.prompt.trim(),
    scheduleType: form.scheduleType,
    scheduleTime: form.scheduleTime,
    weekdays: form.scheduleType === 'WEEKLY' ? weekdayParam() : undefined
  };
  try {
    const res = isNew.value ? await createAutomation(payload) : await updateAutomation(id.value, payload);
    const data = (res as unknown as { data?: AutomationRow })?.data;
    ElMessage.success(isNew.value ? '已创建，到点会自动跑' : '已保存');
    if (isNew.value && data?.id != null) {
      router.replace(`/agent/automation/${data.id}`);
      row.value = data;
      await loadRuns();
    } else if (data) {
      row.value = data;
    }
  } catch {
    // 拦截器统一提示
  } finally {
    saving.value = false;
  }
}

async function runNow() {
  if (running.value) return;
  running.value = true;
  try {
    await runAutomation(id.value);
    ElMessage.success('已开始运行，跑完会发站内通知');
    await loadRuns();
    startPoll();
  } catch {
    // 拦截器统一提示
  } finally {
    running.value = false;
  }
}

/**
 * 运行中每 5s 刷一次历史；跑完自动停并回读一次计数。
 * 只回读 runCount/successCount，不碰表单——用户可能正在改提示词。
 */
let pollTimer: ReturnType<typeof setInterval> | null = null;
function startPoll() {
  if (pollTimer) return;
  pollTimer = setInterval(async () => {
    await loadRuns();
    if (runs.value.some((r) => r.status === 'RUNNING')) return;
    stopPoll();
    if (isNew.value) return;
    try {
      const res = await getAutomation(id.value);
      const data = (res as unknown as { data?: AutomationRow })?.data;
      if (data) row.value = data;
    } catch {
      // 计数刷新失败不影响页面
    }
  }, 5000);
}
function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

async function remove() {
  if (!row.value) return;
  try {
    await ElMessageBox.confirm(`删除「${row.value.title}」？删除后不再到点运行，已有的运行记录也一并隐藏。`, '删除自动化', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await deleteAutomation(id.value);
    ElMessage.success('已删除');
    goList();
  } catch {
    // 拦截器统一提示
  }
}

onMounted(async () => {
  if (!allowed.value) {
    router.replace('/403');
    return;
  }
  await load();
  // 进页面时若还有在跑的（刷新页面/从通知回来），继续追到结束
  if (runs.value.some((r) => r.status === 'RUNNING')) startPoll();
});

onBeforeUnmount(() => {
  if (previewTimer) clearTimeout(previewTimer);
  stopPoll();
});
</script>

<style lang="scss" scoped>
@use '@/assets/styles/dcs-agent-automation.scss';

.crumb-link {
  cursor: pointer;
}
.crumb-link:hover {
  color: var(--text);
  text-decoration: underline;
}
</style>
