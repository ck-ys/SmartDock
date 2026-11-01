<template>
  <div class="dcs-ops-page">
    <DcsOperationsPageHeader
      title="写操作审计"
      eyebrow="AI 助手"
      subtitle="AI 替成员执行过的每一笔写操作：怎么放行的、谁确认的、结果如何。支持按成员、工具、放行方式和时间追溯，可导出留档。"
      :metrics="headerMetrics"
    >
      <template #actions>
        <el-button :icon="Refresh" @click="loadRows">刷新数据</el-button>
      </template>
    </DcsOperationsPageHeader>

    <el-card shadow="never" class="dcs-ops-table-card">
      <template #header>
        <div class="filter-row">
          <!-- 四维筛选（三批③）：成员昵称 / 工具 / 放行方式 / 时间闭区间——与 CSV 导出同口径 -->
          <el-input
            v-model="queryParams.keyword"
            clearable
            placeholder="成员昵称（模糊）"
            style="width: 180px"
            @keyup.enter="handleQuery"
            @clear="handleQuery"
          />
          <el-select
            v-model="queryParams.toolName"
            clearable
            filterable
            allow-create
            placeholder="工具"
            style="width: 170px"
            @change="handleQuery"
          >
            <el-option v-for="t in TOOL_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
          <el-select
            v-model="queryParams.releaseMode"
            clearable
            placeholder="放行方式"
            style="width: 150px"
            @change="handleQuery"
          >
            <el-option v-for="[value, label] in RELEASE_OPTIONS" :key="value" :label="label" :value="value" />
          </el-select>
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm:ss"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            style="width: 340px"
            @change="handleQuery"
          />
          <el-button type="primary" :icon="Search" @click="handleQuery">查询</el-button>
          <el-button :icon="RefreshLeft" @click="resetQuery">重置</el-button>
          <el-button v-hasPermi="['dcs:assistant:audit:query']" :icon="Download" :loading="exporting" @click="handleExport">
            导出 CSV
          </el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="rows" class="ui-table dcs-table-only">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ fmtIsoTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="成员" width="130">
          <template #default="{ row }">{{ row.userName || '—' }}</template>
        </el-table-column>
        <el-table-column label="工具" width="150">
          <template #default="{ row }">{{ toolLabel(row.toolName) }}</template>
        </el-table-column>
        <el-table-column width="120">
          <template #header>
            放行方式
            <el-tooltip
              placement="top"
              content="这笔写操作是怎么被放行的：确认后执行=用户在确认卡点过确认；同类已确认=本会话确认过同类，自动放行；全自动=按用户的档位直接执行；平台放行=工具本身无需确认（如保存记忆）"
            >
              <span class="col-info">ⓘ</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <!-- REJECTED 行/历史行无放行动作→长破折（不猜） -->
            <span :class="{ muted: !row.releaseMode }">{{ releaseLabel(row.releaseMode) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="logSt(row.status).type" size="small">{{ logSt(row.status).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果摘要" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row.resultSummary || '—' }}</template>
        </el-table-column>
        <template #empty>
          <el-empty
            :description="
              loading
                ? '加载中…'
                : searched
                  ? '没有符合条件的审计记录——换个筛选条件或放宽时间范围试试'
                  : 'AI 替成员执行的写操作（建任务、保存记忆等）会按放行方式逐笔记在这里；现在还没有记录'
            "
            :image-size="60"
          />
        </template>
      </el-table>
      <pagination
        v-show="total > 0"
        v-model:page="queryParams.pageNum"
        v-model:limit="queryParams.pageSize"
        :total="total"
        @pagination="loadRows"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
/**
 * 写操作审计（三批③，2026-11-01）：动作账本 dcs_assistant_action 的四维查询独立页
 * （菜单 11936，perm=dcs:assistant:audit:query）。与管理页「运行记录」页签（/action/log，
 * LoginHelper 登录快照租户）分立：本页租户=动态优先（超管切租户只见该租户行）；
 * 多出「放行方式」维（confirm/once/auto/allow，建 agent 时的权限规则快照判定）；
 * args_json 永不出网（列表/导出都不含工具参数）。
 */
import { Search, Refresh, RefreshLeft, Download } from '@element-plus/icons-vue';
import {
  pageAssistantActionAudit,
  exportAssistantActionAudit,
  type AssistantActionAuditRow
} from '@/api/dcs/assistant';
import { ACT, logSt } from '@/components/DcsAssistant/useAssistant';
import { fmtIsoTime } from '@/components/DcsAssistant/util';

defineOptions({ name: 'DcsAssistantAudit' });

const loading = ref(false);
const exporting = ref(false);
const searched = ref(false);
const rows = ref<AssistantActionAuditRow[]>([]);
const total = ref(0);

const queryParams = reactive({
  pageNum: 1,
  pageSize: 20,
  keyword: '',
  toolName: '',
  releaseMode: ''
});
/** 时间闭区间（与 /action/log 同格式 yyyy-MM-dd HH:mm:ss，精确到秒） */
const timeRange = ref<[string, string] | []>([]);

/** 工具下拉：内置已知写工具 + allow-create（业务 provider 新工具不必改前端即可筛） */
const TOOL_OPTIONS = [
  { value: 'save_memory', label: '保存长期记忆' },
  { value: 'dcs_create_task', label: '建任务' }
];

const RELEASE_OPTIONS: [string, string][] = [
  ['confirm', '确认后执行'],
  ['once', '同类已确认'],
  ['auto', '全自动'],
  ['allow', '平台放行']
];

const headerMetrics = computed(() => [
  { label: '当前结果', value: total.value, hint: '符合筛选条件的审计行', tone: 'neutral' as const }
]);

const releaseLabel = (mode?: string) => {
  if (!mode) return '—';
  return RELEASE_OPTIONS.find(([v]) => v === mode)?.[1] ?? mode;
};

const toolLabel = (name?: string) => (name ? ACT[name] ?? name : '—');

const buildQuery = () => {
  const [begin, end] = timeRange.value || [];
  return {
    ...queryParams,
    keyword: queryParams.keyword || undefined,
    toolName: queryParams.toolName || undefined,
    releaseMode: queryParams.releaseMode || undefined,
    beginTime: begin || undefined,
    endTime: end || undefined
  };
};

const loadRows = async () => {
  loading.value = true;
  try {
    // axios 实例裸调用的返回类型收窄——照 admin/index.vue loadActionLog 的既有消费口径
    const res = (await pageAssistantActionAudit(buildQuery())) as unknown as {
      rows?: AssistantActionAuditRow[];
      total?: number;
    };
    rows.value = res?.rows ?? [];
    total.value = res?.total ?? 0;
  } finally {
    loading.value = false;
  }
};

const handleQuery = () => {
  queryParams.pageNum = 1;
  searched.value = true;
  loadRows();
};

const resetQuery = () => {
  queryParams.keyword = '';
  queryParams.toolName = '';
  queryParams.releaseMode = '';
  timeRange.value = [];
  handleQuery();
};

const handleExport = async () => {
  exporting.value = true;
  try {
    // 导出与列表共用筛选口径；分页参数不参与（后端 5 万行上限内全量导）
    const { pageNum: _pn, pageSize: _ps, ...rest } = buildQuery();
    await exportAssistantActionAudit(rest);
  } catch {
    // 下载失败已由 axios 拦截器 toast，这里只复位按钮态
  } finally {
    exporting.value = false;
  }
};

onMounted(() => loadRows());
</script>
