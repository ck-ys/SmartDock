<template>
  <div class="app-container rfm-page">
    <el-card shadow="never" class="hero-card">
      <div class="hero-head">
        <div class="hero-copy">
          <h3>RFM 分层与通用评分模型</h3>
          <p>指标 → 分箱 → 加权 → 快照：同一套模型协议既能跑 RFM，也能跑订单、供应链、工程等任意主体。</p>
        </div>
        <div class="hero-actions">
          <el-select v-model="source" style="width: 150px" @change="onSourceChange">
            <el-option label="dcs 数据采集" value="dcs" />
            <el-option label="risk 风控" value="risk" />
          </el-select>
          <el-select v-model="modelId" placeholder="选择模型" style="width: 220px" @change="loadModelData">
            <el-option v-for="m in models" :key="m.modelId" :label="`${m.modelName} · ${m.modelVersion}`" :value="m.modelId">
              <span>{{ m.modelName }}</span>
              <span class="option-meta">{{ m.modelType }} / {{ m.modelVersion }}</span>
            </el-option>
          </el-select>
          <el-button v-hasPermi="['profile:model:edit']" :disabled="!currentModel" @click="openModelDialog('edit')">编辑模型</el-button>
          <el-button v-hasPermi="['profile:model:edit']" v-if="currentModel && currentModel.builtin !== '1'" type="danger" plain @click="removeCurrent"> 删除 </el-button>
          <el-button v-hasPermi="['profile:model:edit']" type="primary" :disabled="!currentModel" @click="createNewModel">新建模型</el-button>
          <el-button v-hasPermi="['profile:model:edit']" :disabled="!currentModel" :loading="running" @click="runCurrent">运行模型</el-button>
          <el-button :loading="loading" @click="loadAll">刷新</el-button>
        </div>
      </div>

      <el-empty v-if="!loading && models.length === 0" description="当前业务源还没有评分模型">
        <el-button v-hasPermi="['profile:model:edit']" type="primary" @click="createNewModel">用 RFM 预设新建</el-button>
      </el-empty>

      <template v-else>
        <div class="model-meta">
          <el-tag v-if="currentModel?.builtin === '1'" type="info" effect="plain">内置</el-tag>
          <el-tag :type="currentModel?.enabled === '1' ? 'success' : 'info'" effect="plain">
            {{ currentModel?.enabled === '1' ? '启用中' : '已停用' }}
          </el-tag>
          <span class="mono">{{ currentModel?.modelCode }}</span>
          <span class="muted">{{ currentModel?.modelVersion }}</span>
          <span class="muted">{{ currentModel?.remark || '无说明' }}</span>
        </div>

        <div class="stat-grid" v-loading="overviewLoading">
          <div class="stat">
            <span class="stat-label">已评分主体</span>
            <strong>{{ overview.scoredCount ?? '—' }}</strong>
          </div>
          <div class="stat">
            <span class="stat-label">平均标准分</span>
            <strong>{{ formatScore(overview.avgNormalizedScore) }}</strong>
          </div>
          <div class="stat">
            <span class="stat-label">分层数</span>
            <strong>{{ overview.levels?.length ?? '—' }}</strong>
          </div>
          <div class="stat">
            <span class="stat-label">最近计算</span>
            <strong class="stat-time">{{ overview.lastCalcTime || '尚未运行' }}</strong>
          </div>
        </div>
      </template>
    </el-card>

    <template v-if="currentModel">
      <el-row :gutter="14">
        <el-col :xs="24" :lg="12">
          <el-card shadow="never" class="panel-card" v-loading="overviewLoading">
            <template #header>
              <div class="panel-head">
                <span>分层分布</span>
                <span class="muted">共 {{ overview.scoredCount || 0 }} 个主体</span>
              </div>
            </template>
            <div v-if="(overview.levels || []).length" class="level-list">
              <div v-for="item in overview.levels" :key="item.level" class="level-row">
                <div class="level-head">
                  <el-tag :type="levelTagType(item.level)" effect="light">{{ item.level }}</el-tag>
                  <span class="level-count">{{ item.count }} 个 · {{ formatScore(item.rate) }}%</span>
                </div>
                <el-progress :percentage="clamp(Number(item.rate))" :show-text="false" :stroke-width="8" :color="levelColor(item.level)" />
              </div>
            </div>
            <el-empty v-else :image-size="60" description="还没有分层结果，先运行模型" />
          </el-card>
        </el-col>

        <el-col :xs="24" :lg="12">
          <el-card shadow="never" class="panel-card" v-loading="overviewLoading">
            <template #header>
              <div class="panel-head">
                <span>维度质量</span>
                <span class="muted">各维平均分箱得分 / 平均值</span>
              </div>
            </template>
            <div v-if="(overview.dimensions || []).length" class="dim-list">
              <div v-for="dim in overview.dimensions" :key="dim.code" class="dim-row">
                <div class="dim-head">
                  <span class="dim-name"
                    >{{ dim.name || dim.code }} <span class="mono muted">{{ dim.code }}</span></span
                  >
                  <span class="dim-value">均值 {{ formatScore(dim.avgValue) }} · 分箱均值 {{ formatScore(dim.avgScore) }}</span>
                </div>
                <div class="dim-dist">
                  <el-tag v-for="d in dim.distribution" :key="`${dim.code}-${d.score}`" size="small" effect="plain">
                    {{ d.score }} 分 × {{ d.count }}
                  </el-tag>
                </div>
              </div>
            </div>
            <el-empty v-else :image-size="60" description="暂无维度统计" />
          </el-card>
        </el-col>
      </el-row>

      <el-card shadow="never" class="panel-card">
        <template #header>
          <div class="panel-head">
            <span>平均标准分趋势</span>
            <span class="muted">近 {{ trendDays }} 天</span>
          </div>
        </template>
        <v-chart v-if="trend.length" class="trend-chart" :option="trendOption" autoresize />
        <el-empty v-else :image-size="60" description="暂无趋势数据" />
      </el-card>

      <el-card shadow="never" class="table-card">
        <template #header>
          <div class="panel-head">
            <span>评分主体</span>
            <span class="muted">点击「详情」查看分箱过程</span>
          </div>
        </template>
        <div class="filter-bar">
          <el-input v-model="query.keyword" placeholder="主体 ID / 名称" clearable style="width: 200px" @keyup.enter="search" />
          <el-select v-model="query.level" clearable placeholder="全部分层" style="width: 170px" @change="search">
            <el-option v-for="item in overview.levels || []" :key="item.level" :label="item.level" :value="item.level" />
          </el-select>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </div>
        <el-table v-loading="tableLoading" :data="rows" row-key="subjectId" stripe>
          <el-table-column label="主体" min-width="190">
            <template #default="{ row }">
              <div class="subject-cell">
                <span class="subject-name">{{ row.displayName || row.subjectId }}</span>
                <span class="subject-meta">{{ row.subjectType }} · {{ row.subjectId }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="模型分" width="180">
            <template #default="{ row }">
              <div class="score-cell">
                <el-progress
                  :percentage="clamp(Number(row.normalizedScore || 0))"
                  :stroke-width="8"
                  :show-text="false"
                  :color="scoreColor(Number(row.normalizedScore || 0))"
                />
                <span class="score-number">{{ formatScore(row.normalizedScore) }}</span>
              </div>
              <span class="muted small">原始分 {{ formatScore(row.modelScore) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="分箱" min-width="150">
            <template #default="{ row }">
              <span v-for="item in binChips(row)" :key="item.code" class="bin-chip">
                {{ item.code }} <strong>{{ item.score }}</strong>
              </span>
              <span v-if="binChips(row).length === 0" class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="分层" width="140">
            <template #default="{ row }">
              <el-tag :type="levelTagType(row.level)" effect="light">{{ row.level || '未分层' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="eventCount" label="事件数" width="90" />
          <el-table-column label="计算时间" width="170">
            <template #default="{ row }">{{ row.calcTime || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openSubject(row)">详情</el-button>
              <el-button link @click="goProfile(row)">完整画像</el-button>
            </template>
          </el-table-column>
        </el-table>
        <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="loadSubjects" />
      </el-card>
    </template>

    <!-- 模型新建/编辑 -->
    <el-dialog v-model="modelDialogVisible" :title="modelForm.modelId ? '编辑评分模型' : '新建评分模型'" width="760px" destroy-on-close>
      <el-form label-width="96px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="模型编码">
              <el-input v-model="modelForm.modelCode" :disabled="!!modelForm.modelId" maxlength="64" placeholder="如 rfm-default" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模型名称">
              <el-input v-model="modelForm.modelName" maxlength="64" placeholder="如 RFM 分层" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模型类型">
              <el-select v-model="modelForm.modelType" style="width: 100%">
                <el-option label="RFM" value="RFM" />
                <el-option label="通用评分" value="GENERIC" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="版本">
              <el-input v-model="modelForm.modelVersion" maxlength="32" placeholder="v1" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="标准分上限">
              <el-input-number v-model="modelForm.scoreMax" :min="1" :max="1000" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="启用">
              <el-switch v-model="modelForm.enabled" active-value="1" inactive-value="0" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="模型配置">
          <div class="config-toolbar">
            <span class="muted">ProfileModelDefinition JSON：dimensions / calc / bins</span>
            <span>
              <el-button size="small" @click="fillPreset">填入 RFM 预设</el-button>
              <el-button size="small" @click="formatConfig">格式化</el-button>
            </span>
          </div>
          <el-input v-model="modelForm.configText" type="textarea" :rows="14" class="mono config-area" spellcheck="false" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="modelForm.remark" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelDialogVisible = false">取消</el-button>
        <el-button v-hasPermi="['profile:model:edit']" type="primary" :loading="saving" @click="saveModel">保存</el-button>
      </template>
    </el-dialog>

    <!-- 主体分箱详情 -->
    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="620px" destroy-on-close>
      <div v-loading="drawerLoading" class="drawer-body">
        <template v-if="subjectDetail?.latest">
          <div class="drawer-score">
            <div>
              <span class="muted">标准分</span>
              <strong>{{ formatScore(subjectDetail.latest.normalizedScore) }}</strong>
            </div>
            <div>
              <span class="muted">原始分</span>
              <strong>{{ formatScore(subjectDetail.latest.modelScore) }}</strong>
            </div>
            <div>
              <span class="muted">分层</span>
              <el-tag :type="levelTagType(subjectDetail.latest.level)" effect="light">
                {{ subjectDetail.latest.level || '未分层' }}
              </el-tag>
            </div>
          </div>
          <el-table :data="drawerDimensions" size="small" stripe>
            <el-table-column label="维度" min-width="130">
              <template #default="{ row }"
                >{{ row.name }} <span class="mono muted">{{ row.code }}</span></template
              >
            </el-table-column>
            <el-table-column label="指标值" width="100">
              <template #default="{ row }">{{ formatScore(row.value) }}</template>
            </el-table-column>
            <el-table-column label="分箱" width="90">
              <template #default="{ row }">{{ row.score }} / {{ row.maxScore }}</template>
            </el-table-column>
            <el-table-column label="标准分" width="90">
              <template #default="{ row }">
                <span :class="scoreClass(Number(row.normalizedScore))">{{ formatScore(row.normalizedScore) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="权重" width="80">
              <template #default="{ row }">{{ row.weight }}</template>
            </el-table-column>
          </el-table>
          <el-descriptions :column="2" border size="small" class="drawer-desc">
            <el-descriptions-item label="主体类型">{{ subjectDetail.latest.subjectType }}</el-descriptions-item>
            <el-descriptions-item label="主体 ID">{{ subjectDetail.latest.subjectId }}</el-descriptions-item>
            <el-descriptions-item label="模型版本">{{ subjectDetail.latest.modelVersion }}</el-descriptions-item>
            <el-descriptions-item label="计算时间">{{ subjectDetail.latest.calcTime }}</el-descriptions-item>
          </el-descriptions>
          <div class="drawer-history">
            <h4>历史快照（近 20 次）</h4>
            <el-timeline>
              <el-timeline-item v-for="item in subjectDetail.history" :key="item.modelSnapshotId" :timestamp="item.calcTime" placement="top">
                <span class="muted">标准分</span> {{ formatScore(item.normalizedScore) }}
                <el-tag size="small" effect="plain" class="history-tag">{{ item.level }}</el-tag>
              </el-timeline-item>
            </el-timeline>
          </div>
        </template>
        <el-empty v-else description="该主体还没有模型快照" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup name="DcsProfileRfm" lang="ts">
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import VChart from 'vue-echarts';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  createModel,
  deleteModel,
  getModelOverview,
  getModelPreset,
  getModelSubject,
  getModelTrend,
  listModels,
  pageModelSubjects,
  runModel,
  updateModel,
  type ProfileModelOverview,
  type ProfileModelSnapshot,
  type ProfileModelSubjectDetail,
  type ProfileModelSubjectRow,
  type ProfileModelDimensionEntry,
  type ProfileModelTrendPoint,
  type ProfileScoreModel
} from '@/api/dcs/core/profile';

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent]);

const router = useRouter();

const source = ref('dcs');
const models = ref<ProfileScoreModel[]>([]);
const modelId = ref<string>('');
const overview = ref<Partial<ProfileModelOverview>>({});
const trend = ref<ProfileModelTrendPoint[]>([]);
const rows = ref<ProfileModelSubjectRow[]>([]);
const total = ref(0);
const loading = ref(false);
const overviewLoading = ref(false);
const tableLoading = ref(false);
const running = ref(false);
const trendDays = 30;

const query = reactive({ pageNum: 1, pageSize: 20, level: '', keyword: '' });

const currentModel = computed(() => models.value.find((m) => m.modelId === modelId.value) || null);

const clamp = (value: number) => Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0));
const formatScore = (value?: number | string | null) => {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  return Number.isFinite(n) ? n.toFixed(2) : String(value);
};
const scoreColor = (value: number) => (value >= 75 ? '#67c23a' : value >= 60 ? '#e6a23c' : '#f56c6c');
const scoreClass = (value: number) => (value >= 75 ? 'score-good' : value >= 60 ? 'score-mid' : 'score-low');

const levelTagType = (level?: string) => {
  if (!level) return 'info' as const;
  if (level.includes('重要价值') || level.includes('高价值')) return 'success' as const;
  if (level.includes('一般价值') || level.includes('中价值')) return 'primary' as const;
  if (level.includes('发展')) return 'warning' as const;
  if (level.includes('保持') || level.includes('挽留') || level.includes('低价值')) return 'danger' as const;
  return 'info' as const;
};
const levelColor = (level?: string) => {
  const type = levelTagType(level);
  return type === 'success' ? '#67c23a' : type === 'primary' ? '#409eff' : type === 'warning' ? '#e6a23c' : '#f56c6c';
};

const parseJson = <T,>(raw?: string | null): T | null => {
  if (!raw) return null;
  try {
    return (typeof raw === 'string' ? JSON.parse(raw) : raw) as T;
  } catch {
    return null;
  }
};
const binChips = (row: ProfileModelSubjectRow) => {
  const detail = parseJson<{ dimensions?: Record<string, ProfileModelDimensionEntry> }>(row.detail);
  if (!detail?.dimensions) return [];
  return Object.values(detail.dimensions).map((d) => ({ code: d.code, score: d.score }));
};

const trendOption = computed<any>(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 46, right: 20, top: 20, bottom: 32 },
  xAxis: { type: 'category', boundaryGap: false, data: trend.value.map((p) => p.day) },
  yAxis: { type: 'value', min: 0, max: 100 },
  series: [
    {
      type: 'line',
      smooth: true,
      symbolSize: 6,
      areaStyle: { opacity: 0.12 },
      data: trend.value.map((p) => Number(p.avgScore || 0))
    }
  ]
}));

const loadModels = async () => {
  models.value = await listModels(source.value);
  const preferred = models.value.find((m) => m.modelType === 'RFM' && m.enabled === '1') || models.value[0];
  if (!modelId.value || !models.value.some((m) => m.modelId === modelId.value)) {
    modelId.value = preferred?.modelId || '';
  }
};

const loadModelData = async () => {
  if (!modelId.value) {
    overview.value = {};
    rows.value = [];
    total.value = 0;
    trend.value = [];
    return;
  }
  overviewLoading.value = true;
  try {
    const [ov, tr] = await Promise.all([getModelOverview(modelId.value), getModelTrend(modelId.value, trendDays)]);
    overview.value = ov;
    trend.value = tr;
  } finally {
    overviewLoading.value = false;
  }
  await loadSubjects();
};

const loadSubjects = async () => {
  if (!modelId.value) return;
  tableLoading.value = true;
  try {
    const res = await pageModelSubjects(modelId.value, {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      level: query.level || undefined,
      keyword: query.keyword || undefined
    });
    rows.value = res.rows;
    total.value = res.total;
  } finally {
    tableLoading.value = false;
  }
};

const loadAll = async () => {
  loading.value = true;
  try {
    await loadModels();
    await loadModelData();
  } finally {
    loading.value = false;
  }
};

const onSourceChange = () => {
  modelId.value = '';
  query.pageNum = 1;
  loadAll();
};
const search = () => {
  query.pageNum = 1;
  loadSubjects();
};
const resetQuery = () => {
  query.level = '';
  query.keyword = '';
  query.pageNum = 1;
  loadSubjects();
};

const runCurrent = async () => {
  if (!currentModel.value) return;
  await ElMessageBox.confirm(`将对「${currentModel.value.modelName}」的全部主体重新分箱并写入快照，确定继续？`, '运行模型', { type: 'warning' });
  running.value = true;
  try {
    const run = await runModel(modelId.value);
    if (run.status === 'DONE') {
      ElMessage.success(`运行完成：${run.doneSubjects} 个主体`);
    } else {
      ElMessage.error(run.errorMsg || `运行失败：${run.status}`);
    }
    await loadModelData();
  } finally {
    running.value = false;
  }
};

// ---------------------------------------------------------------- 模型编辑
const modelDialogVisible = ref(false);
const saving = ref(false);
const modelForm = reactive({
  modelId: '',
  modelCode: '',
  modelName: '',
  modelType: 'RFM',
  modelVersion: 'v1',
  scoreMax: 100,
  enabled: '1',
  remark: '',
  configText: '',
  revision: 0
});

const resetModelForm = () => {
  Object.assign(modelForm, {
    modelId: '',
    modelCode: '',
    modelName: '',
    modelType: 'RFM',
    modelVersion: 'v1',
    scoreMax: 100,
    enabled: '1',
    remark: '',
    configText: '',
    revision: 0
  });
};

const createNewModel = async () => {
  resetModelForm();
  try {
    await fillPreset();
  } catch {
    // 预设接口失败不阻塞手填
  }
  modelDialogVisible.value = true;
};

const openModelDialog = (mode: 'edit') => {
  const m = currentModel.value;
  if (!m || mode !== 'edit') return;
  Object.assign(modelForm, {
    modelId: m.modelId,
    modelCode: m.modelCode,
    modelName: m.modelName,
    modelType: m.modelType,
    modelVersion: m.modelVersion,
    scoreMax: Number(m.scoreMax),
    enabled: m.enabled,
    remark: m.remark || '',
    configText: prettyJson(m.config),
    revision: m.revision
  });
  modelDialogVisible.value = true;
};

const prettyJson = (raw?: string) => {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
};

const fillPreset = async () => {
  const preset = await getModelPreset();
  modelForm.modelType = 'RFM';
  if (!modelForm.modelName) modelForm.modelName = preset.name;
  if (!modelForm.modelCode) modelForm.modelCode = `rfm-${Date.now().toString(36)}`;
  modelForm.configText = JSON.stringify(preset.config, null, 2);
  ElMessage.success('已填入 RFM 预设，可按业务替换事件与属性');
};

const formatConfig = () => {
  modelForm.configText = prettyJson(modelForm.configText);
};

const saveModel = async () => {
  if (!modelForm.modelName.trim()) return ElMessage.warning('模型名称不能为空');
  if (!modelForm.modelId && !modelForm.modelCode.trim()) return ElMessage.warning('模型编码不能为空');
  let config: Record<string, any>;
  try {
    config = JSON.parse(modelForm.configText);
  } catch {
    return ElMessage.error('模型配置不是合法 JSON');
  }
  saving.value = true;
  try {
    const payload: Record<string, any> = {
      source: source.value,
      modelName: modelForm.modelName.trim(),
      modelType: modelForm.modelType,
      modelVersion: modelForm.modelVersion.trim() || 'v1',
      scoreMax: modelForm.scoreMax,
      enabled: modelForm.enabled,
      remark: modelForm.remark,
      config
    };
    if (modelForm.modelId) {
      payload.modelId = modelForm.modelId;
      payload.revision = modelForm.revision;
      await updateModel(payload);
      ElMessage.success('模型已更新');
    } else {
      payload.modelCode = modelForm.modelCode.trim();
      const created = await createModel(payload);
      modelId.value = created.modelId;
      ElMessage.success('模型已创建');
    }
    modelDialogVisible.value = false;
    await loadAll();
  } finally {
    saving.value = false;
  }
};

const removeCurrent = async () => {
  const m = currentModel.value;
  if (!m || m.builtin === '1') return;
  await ElMessageBox.confirm(`删除模型「${m.modelName}」？历史快照不会自动清理。`, '删除模型', { type: 'warning' });
  await deleteModel(m.modelId);
  ElMessage.success('模型已删除');
  modelId.value = '';
  await loadAll();
};

// ---------------------------------------------------------------- 主体详情
const drawerVisible = ref(false);
const drawerLoading = ref(false);
const subjectDetail = ref<ProfileModelSubjectDetail | null>(null);
const drawerTitle = computed(() => {
  const latest = subjectDetail.value?.latest as ProfileModelSnapshot | null;
  return latest ? `${subjectDetail.value?.subject?.displayName || latest.subjectId} · 分箱详情` : '主体分箱详情';
});
const drawerDimensions = computed<ProfileModelDimensionEntry[]>(() => {
  const raw = parseJson<{ dimensions?: Record<string, ProfileModelDimensionEntry> }>(subjectDetail.value?.latest?.detail);
  return raw?.dimensions ? Object.values(raw.dimensions) : [];
});

const openSubject = async (row: ProfileModelSubjectRow) => {
  drawerVisible.value = true;
  drawerLoading.value = true;
  subjectDetail.value = null;
  try {
    subjectDetail.value = await getModelSubject(modelId.value, row.subjectId, row.subjectType);
  } finally {
    drawerLoading.value = false;
  }
};
const goProfile = (row: { subjectId: string; subjectType?: string }) =>
  router.push(`/dcs/profile-detail/${source.value}/${row.subjectId}?subjectType=${encodeURIComponent(row.subjectType || 'user')}`);

onMounted(loadAll);
</script>

<style scoped>
.rfm-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.hero-card :deep(.el-card__body) {
  padding: 18px 20px;
}
.hero-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}
.hero-copy h3 {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 650;
}
.hero-copy p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  max-width: 720px;
}
.hero-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.option-meta {
  float: right;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-left: 12px;
}
.model-meta {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 13px;
}
.mono {
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
}
.muted {
  color: var(--el-text-color-secondary);
}
.small {
  font-size: 12px;
}
.stat-grid {
  margin-top: 14px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}
.stat {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 10px 12px;
  background: var(--el-fill-color-blank);
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.stat strong {
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}
.stat-time {
  font-size: 14px !important;
  font-weight: 600;
}
.panel-card :deep(.el-card__body) {
  padding-top: 12px;
}
.panel-card {
  height: 100%;
}
.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.level-list,
.dim-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.level-head,
.dim-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  margin-bottom: 5px;
}
.level-count,
.dim-value {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.dim-name {
  font-size: 13px;
  font-weight: 600;
}
.dim-dist {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.trend-chart {
  height: 240px;
  width: 100%;
}
.table-card :deep(.el-card__body) {
  padding-top: 12px;
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
}
.subject-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.subject-name {
  font-weight: 600;
}
.subject-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.score-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.score-cell :deep(.el-progress) {
  flex: 1;
  min-width: 56px;
}
.score-number {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.bin-chip {
  display: inline-block;
  margin-right: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.bin-chip strong {
  color: var(--el-text-color-primary);
}
.score-good {
  color: var(--el-color-success);
  font-weight: 600;
}
.score-mid {
  color: var(--el-color-warning);
  font-weight: 600;
}
.score-low {
  color: var(--el-color-danger);
  font-weight: 600;
}
.config-toolbar {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.config-area :deep(textarea) {
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
  font-size: 12px;
  line-height: 1.5;
}
.drawer-body {
  min-height: 200px;
}
.drawer-score {
  display: flex;
  gap: 28px;
  align-items: center;
  margin-bottom: 16px;
}
.drawer-score > div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.drawer-score strong {
  font-size: 22px;
  font-variant-numeric: tabular-nums;
}
.drawer-desc {
  margin-top: 14px;
}
.drawer-history {
  margin-top: 20px;
}
.drawer-history h4 {
  margin: 0 0 10px;
  font-size: 14px;
}
.history-tag {
  margin-left: 8px;
}
@media (max-width: 1100px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
