<template>
  <div class="app-container ops-page">
    <dcs-operations-page-header
      eyebrow="画像引擎"
      title="数据运维"
      subtitle="事件账本只增不改——改错了口径、补了历史数据，用回放按窗口重算派生数据；数据老化按源配置保留期，过期先归档再删；身份合错了可以拆开。"
    >
      <template #actions>
        <el-select v-model="source" class="source-select" @change="loadAll">
          <el-option v-for="s in sourceOptions" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button v-hasPermi="['profile:replay:edit']" type="primary" @click="openCreate">发起回放</el-button>
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
      </template>
    </dcs-operations-page-header>

    <el-card shadow="never">
      <template #header>
        <div class="panel-head">
          <span>回放任务</span>
          <span class="muted small">断点续跑 · 重算幂等（事件账本 append-only）· 完成后对账数字前后对比</span>
        </div>
      </template>
      <el-table v-loading="loading" class="ui-table" :data="tasks">
        <template #empty>
          <div class="ui-empty">
            <p class="ui-empty__title">还没有回放任务</p>
            <p class="ui-empty__hint">选择时间窗发起一次回放，完成后这里会显示前后对账数字</p>
          </div>
        </template>
        <el-table-column label="任务" width="170">
          <template #default="{ row }">
            <span class="mono">{{ shortId(row.replayId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="窗口" width="200">
          <template #default="{ row }">
            {{ day(row.fromDay) }} ~ {{ day(row.toDay) }}
          </template>
        </el-table-column>
        <el-table-column label="链" width="180">
          <template #default="{ row }">
            <el-tag v-for="c in row.chains.split(',')" :key="c" size="small" class="chain-tag" type="info">{{ c }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="160">
          <template #default="{ row }">
            <el-progress
              :percentage="row.daysTotal ? Math.round((row.daysDone / row.daysTotal) * 100) : 0"
              :status="row.status === 'FAILED' ? 'exception' : row.status === 'DONE' ? 'success' : undefined"
            />
            <span class="muted small">{{ row.daysDone }}/{{ row.daysTotal }} 天<template v-if="row.currentDay && row.status !== 'DONE'"> · 断点 {{ day(row.currentDay) }}</template></span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对账（前 → 后）" min-width="220">
          <template #default="{ row }">
            <template v-if="row.status === 'DONE'">
              <span class="small">会话 {{ stat(row.statsBefore, 'sessions') }} → {{ stat(row.statsAfter, 'sessions') }}；PV {{ stat(row.statsBefore, 'pageViews') }} → {{ stat(row.statsAfter, 'pageViews') }}</span>
            </template>
            <span v-else-if="row.errorMsg" class="small err">{{ row.errorMsg }}</span>
            <span v-else class="muted small">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'RUNNING'" v-hasPermi="['profile:replay:edit']" link type="warning" size="small" @click="pause(row)">暂停</el-button>
            <el-button v-if="row.status === 'PAUSED' || row.status === 'FAILED'" v-hasPermi="['profile:replay:edit']" link type="primary" size="small" @click="resume(row)">续跑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="config-card">
      <template #header>
        <div class="panel-head">
          <span>数据保留期（本源）</span>
          <span class="muted small">每日 03:30 清理 · 0 或空 = 继承全局默认（730 天）· 归档 = 过期先迁冷库可查，直接删 = 找不回</span>
        </div>
      </template>
      <div class="config-row" v-loading="configLoading">
        <div class="config-item">
          <span class="config-label">保留天数</span>
          <el-input-number v-model="retention.eventRetentionDays" :min="0" :max="36500" controls-position="right" style="width: 150px" :placeholder="'默认 ' + 730" />
          <span class="muted small">天（热数据）</span>
        </div>
        <div class="config-item">
          <span class="config-label">过期去向</span>
          <el-switch v-model="retention.eventArchive" active-value="1" inactive-value="0" active-text="先归档再删" inactive-text="直接删" />
        </div>
        <el-button v-hasPermi="['profile:review:edit']" :loading="savingConfig" @click="saveRetention">保存</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="panel-head">
          <span>身份合并记录</span>
          <span class="muted small">合并只迁链接不改历史事件 · 拆分会把误并的匿名事件归属改回并重算（不可逆，需二次确认）</span>
        </div>
      </template>
      <el-table v-loading="mergeLoading" class="ui-table" :data="merges">
        <template #empty>
          <div class="ui-empty">
            <p class="ui-empty__title">该源还没有身份合并记录</p>
            <p class="ui-empty__hint">共享浏览器换账号时会自动生成匿名身份链接</p>
          </div>
        </template>
        <el-table-column label="发生时间" width="170">
          <template #default="{ row }">{{ fmt(row.occurredAt) }}</template>
        </el-table-column>
        <el-table-column label="从（被并入）" min-width="180">
          <template #default="{ row }">
            <span class="mono small">{{ row.fromSubjectId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="并入" min-width="180">
          <template #default="{ row }">
            <span class="mono small">{{ row.toSubjectId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="原因" width="130" prop="reason" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.reversedAt" type="info" size="small">已拆分</el-tag>
            <el-tag v-else type="success" size="small">生效中</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button v-if="!row.reversedAt" v-hasPermi="['profile:identity:edit']" link type="danger" size="small" @click="confirmUnmerge(row)">拆分</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="发起回放" width="480px">
      <el-form label-width="90px">
        <el-form-item label="业务源">
          <el-input v-model="source" disabled style="width: 200px" />
        </el-form-item>
        <el-form-item label="时间窗">
          <el-date-picker
            v-model="range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日"
            end-placeholder="结束日（含）"
            style="width: 100%"
            :disabled-date="(d: Date) => d.getTime() > Date.now()"
          />
        </el-form-item>
        <el-form-item label="回放链">
          <el-checkbox-group v-model="chains">
            <el-checkbox label="SESSION">会话</el-checkbox>
            <el-checkbox label="DAILY">日汇总</el-checkbox>
            <el-checkbox label="SNAPSHOT">模型快照</el-checkbox>
          </el-checkbox-group>
          <span class="muted small">会话+日汇总按天重算；模型快照重放该源全部已发布模型</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">开始回放</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="ProfileOps">
import {
  listReplayTasks,
  startReplay,
  resumeReplay,
  pauseReplay,
  getEngineConfig,
  updateEngineConfig,
  listIdentityMerges,
  unmergeIdentity,
  type ProfileReplayTask,
  type ProfileIdentityMerge,
} from '@/api/dcs/core/profile';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';

const sourceOptions = ['web', 'order', 'supply', 'dcs', 'risk'];
const source = ref('web');
const loading = ref(false);
const tasks = ref<ProfileReplayTask[]>([]);
const mergeLoading = ref(false);
const merges = ref<ProfileIdentityMerge[]>([]);
const createVisible = ref(false);
const creating = ref(false);
const range = ref<[string, string] | null>(null);
const chains = ref<string[]>(['SESSION', 'DAILY']);

const configLoading = ref(false);
const savingConfig = ref(false);
const retention = ref<{ eventRetentionDays?: number | null; eventArchive?: string }>({
  eventRetentionDays: null,
  eventArchive: '1',
});

const shortId = (id: string) => (id ? 'RP-' + id.slice(-4) : '');
const day = (v?: string) => (v ? String(v).slice(0, 10) : '—');
const fmt = (v?: string) => (v ? String(v).replace('T', ' ').slice(0, 19) : '—');

const statusType = (s: string): 'primary' | 'success' | 'warning' | 'info' | 'danger' =>
  (({ RUNNING: 'primary', PAUSED: 'warning', DONE: 'success', FAILED: 'danger' }) as Record<string, 'primary' | 'success' | 'warning' | 'info' | 'danger'>)[s] || 'info';
const statusLabel = (s: string) =>
  ({ RUNNING: '执行中', PAUSED: '已暂停', DONE: '完成', FAILED: '失败' })[s] || s;

const stat = (json: string | undefined, key: string) => {
  try {
    const parsed = json ? JSON.parse(json) : {};
    return parsed[key] ?? 0;
  } catch {
    return 0;
  }
};

let pollTimer: ReturnType<typeof setTimeout> | null = null;

const loadTasks = async () => {
  loading.value = true;
  try {
    tasks.value = await listReplayTasks(source.value);
    // 执行中的任务 5s 轮询进度; 离开页面即停
    if (tasks.value.some((t) => t.status === 'RUNNING')) {
      pollTimer = setTimeout(() => {
        if (tasks.value.some((t) => t.status === 'RUNNING')) loadTasks();
      }, 5000);
    }
  } finally {
    loading.value = false;
  }
};

onUnmounted(() => {
  if (pollTimer) clearTimeout(pollTimer);
});

const loadConfig = async () => {
  configLoading.value = true;
  try {
    const config = await getEngineConfig(source.value);
    retention.value.eventRetentionDays = config.eventRetentionDays ?? null;
    retention.value.eventArchive = config.eventArchive || '1';
  } finally {
    configLoading.value = false;
  }
};

const loadMerges = async () => {
  mergeLoading.value = true;
  try {
    merges.value = await listIdentityMerges(source.value);
  } finally {
    mergeLoading.value = false;
  }
};

const loadAll = () => {
  loadTasks();
  loadConfig();
  loadMerges();
};

const openCreate = () => {
  range.value = null;
  chains.value = ['SESSION', 'DAILY'];
  createVisible.value = true;
};

const create = async () => {
  if (!range.value || range.value.length !== 2) {
    ElMessage.warning('请选择回放时间窗');
    return;
  }
  if (!chains.value.length) {
    ElMessage.warning('至少勾选一条回放链');
    return;
  }
  creating.value = true;
  try {
    await startReplay({ source: source.value, fromDate: range.value[0], toDate: range.value[1], chains: chains.value });
    ElMessage.success('回放任务已发起');
    createVisible.value = false;
    loadTasks();
  } finally {
    creating.value = false;
  }
};

const pause = async (row: ProfileReplayTask) => {
  await pauseReplay(row.replayId);
  ElMessage.success('已请求暂停（当前天算完即停）');
  loadTasks();
};

const resume = async (row: ProfileReplayTask) => {
  await resumeReplay(row.replayId);
  ElMessage.success('已从断点续跑');
  loadTasks();
};

const saveRetention = async () => {
  savingConfig.value = true;
  try {
    await updateEngineConfig({
      source: source.value,
      // 0 或清空都显式传 0: 后端 0=清自定义回到继承全局(不传字段才是不动现值)
      eventRetentionDays: retention.value.eventRetentionDays ?? 0,
      eventArchive: retention.value.eventArchive,
    });
    ElMessage.success('保留期配置已保存（每日 03:30 生效）');
  } finally {
    savingConfig.value = false;
  }
};

const confirmUnmerge = (row: ProfileIdentityMerge) => {
  ElMessageBox.confirm(
    `确认拆分「${row.fromSubjectId} → ${row.toSubjectId}」？拆分后：误并的匿名事件归属会改回原主体，并重算该时间段会话与双方分数。此操作不可逆（要合并只能重新触发合并条件）。`,
    '身份拆分确认',
    { type: 'warning', confirmButtonText: '确认拆分', cancelButtonText: '再想想' }
  ).then(async () => {
    const out = await unmergeIdentity(row.mergeId);
    ElMessage.success(`拆分完成：${out.eventsReassigned} 条事件归属已改回，派生数据${out.derivedReplayed ? '已重算' : '重算中'}`);
    loadMerges();
    loadTasks();
  });
};

onMounted(loadAll);
</script>

<style scoped>
.ops-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.ops-page :deep(.dcs-ops-header) {
  margin-bottom: 4px;
}
.ops-page :deep(.dcs-ops-header__actions) {
  flex-wrap: wrap;
}
@media (min-width: 993px) {
  .source-select {
    width: 150px;
  }
}
/* el-table 走 CSS 变量：表头无底带、行线走令牌、正文灰阶（不重写组件） */
.ops-page :deep(.el-table) {
  --el-table-border-color: var(--app-rule);
  --el-table-header-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--app-raised);
}
/* 状态药丸：统一浅底深字，形状/字号与 .ui-pill 同口径 */
.ops-page :deep(.el-tag) {
  --el-tag-bg-color: var(--dcs-pill-off-bg);
  --el-tag-text-color: var(--dcs-pill-off-ink);
  --el-tag-border-color: transparent;
  height: 22px;
  padding: 0 10px;
  border: 0;
  border-radius: var(--dcs-pill-radius);
  font-size: var(--dcs-font-xs);
  font-weight: 500;
}
.ops-page :deep(.el-tag--success) {
  --el-tag-bg-color: var(--dcs-pill-pos-bg);
  --el-tag-text-color: var(--dcs-pill-pos-ink);
}
.ops-page :deep(.el-tag--warning) {
  --el-tag-bg-color: var(--dcs-pill-warn-bg);
  --el-tag-text-color: var(--dcs-pill-warn-ink);
}
.ops-page :deep(.el-tag--danger) {
  --el-tag-bg-color: var(--dcs-pill-neg-bg);
  --el-tag-text-color: var(--dcs-pill-neg-ink);
}
.ops-page :deep(.el-card__body) {
  min-width: 0;
}
.panel-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
.muted { color: var(--el-text-color-secondary); }
.small { font-size: 12px; }
.mono { font-family: Consolas, monospace; font-size: 12px; }
.err { color: var(--el-color-danger); }
.chain-tag { margin-right: 4px; }
.config-row { display: flex; gap: 24px; align-items: center; flex-wrap: wrap; }
.config-item { display: flex; align-items: center; gap: 8px; }
.config-label { font-size: 13px; color: var(--el-text-color-regular); }
</style>
