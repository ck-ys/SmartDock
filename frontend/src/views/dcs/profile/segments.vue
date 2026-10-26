<template>
  <div class="app-container segment-page">
    <dcs-operations-page-header
      eyebrow="画像引擎"
      title="标签分群"
      subtitle="动态分群按画像快照实时求值，静态分群按名单导入；成员与运行记录都可追溯。"
      :metrics="segmentMetrics"
    >
      <template #actions>
        <el-select v-model="source" class="source-select" @change="loadAll">
          <el-option v-for="s in sourceOpts" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-button v-hasPermi="['profile:segment:edit']" type="primary" @click="openCreate">新建分群</el-button>
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
      </template>
    </dcs-operations-page-header>

    <div v-loading="loading" class="segment-grid">
      <el-card v-for="segment in segments" :key="segment.segmentId" shadow="hover" class="segment-card">
        <div class="segment-top">
          <div>
            <div class="segment-name">{{ segment.segmentName }}</div>
            <div class="segment-code mono">{{ segment.segmentCode }}</div>
          </div>
          <div class="segment-tags">
            <el-tag :type="segment.segmentType === 'DYNAMIC' ? 'primary' : 'info'" effect="plain" size="small">
              {{ segment.segmentType === 'DYNAMIC' ? '动态' : '静态' }}
            </el-tag>
            <el-tag v-if="refreshModeLabel(segment.refreshMode)" type="success" effect="plain" size="small">
              {{ refreshModeLabel(segment.refreshMode) }}
            </el-tag>
            <el-tag :type="segment.status === 'ENABLED' ? 'success' : 'info'" effect="plain" size="small">
              {{ segment.status === 'ENABLED' ? '启用' : '停用' }}
            </el-tag>
            <el-tag v-if="segment.builtin === '1'" type="warning" effect="plain" size="small">内置</el-tag>
          </div>
        </div>

        <p class="segment-desc">{{ segment.description || '暂无说明' }}</p>

        <div class="segment-metrics">
          <div>
            <span>当前成员</span>
            <strong>{{ segment.memberCount ?? 0 }}</strong>
          </div>
          <div>
            <span>最近评估</span>
            <strong class="metric-time">{{ segment.lastEvalTime || '从未' }}</strong>
            <el-tag v-if="segment.lastRunStatus === 'SKIPPED'" size="small" type="warning" class="run-status-tag"> 超限跳过 </el-tag>
            <el-tag v-else-if="segment.lastRunStatus === 'FAILED'" size="small" type="danger" class="run-status-tag"> 上次失败 </el-tag>
          </div>
        </div>

        <div class="segment-rule">
          <span class="rule-label">规则</span>
          <span class="rule-text">{{ ruleSummary(segment) }}</span>
        </div>

        <div class="segment-actions">
          <el-button
            v-hasPermi="['profile:segment:edit']"
            v-if="segment.segmentType === 'DYNAMIC'"
            :loading="evaluatingId === segment.segmentId"
            @click="evaluate(segment)"
          >
            评估
          </el-button>
          <el-button v-hasPermi="['profile:segment:edit']" v-else @click="openImport(segment)">导入名单</el-button>
          <el-button @click="openMembers(segment)">成员</el-button>
          <el-button @click="openRuns(segment)">运行记录</el-button>
          <el-dropdown trigger="click" @command="(cmd: string) => onMoreCommand(cmd, segment)">
            <el-button>
              更多<el-icon class="el-icon--right"><arrow-down /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-hasPermi="['profile:reach:edit']" command="notify">发通知（站内信）</el-dropdown-item>
                <el-dropdown-item command="records">触达 / 导出记录</el-dropdown-item>
                <el-dropdown-item v-hasPermi="['profile:reach:edit']" command="export">导出成员 CSV</el-dropdown-item>
                <el-dropdown-item v-hasPermi="['profile:segment:edit']" command="edit" divided>编辑</el-dropdown-item>
                <el-dropdown-item v-hasPermi="['profile:segment:edit']" v-if="segment.builtin !== '1'" command="delete">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-card>
      <el-empty v-if="!loading && segments.length === 0" description="当前业务源还没有分群">
        <el-button v-hasPermi="['profile:segment:edit']" @click="openCreate">新建第一个分群</el-button>
      </el-empty>
    </div>

    <!-- 新建/编辑 -->
    <el-dialog v-model="dialogVisible" :title="form.segmentId ? '编辑分群' : '新建分群'" width="min(780px, 94%)" destroy-on-close>
      <el-form label-width="92px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="分群编码">
              <el-input v-model="form.segmentCode" :disabled="!!form.segmentId" maxlength="64" placeholder="如 high_value_active" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="分群名称">
              <el-input v-model="form.segmentName" maxlength="64" placeholder="如 高价值活跃" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="分群类型">
              <el-select v-model="form.segmentType" style="width: 100%" @change="onTypeChange">
                <el-option label="动态（规则求值）" value="DYNAMIC" />
                <el-option label="静态（名单导入）" value="STATIC" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-select v-model="form.status" style="width: 100%">
                <el-option label="启用" value="ENABLED" />
                <el-option label="停用" value="DISABLED" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col v-if="form.segmentType === 'DYNAMIC'" :span="12">
            <el-form-item label="自动刷新">
              <el-select v-model="form.refreshMode" style="width: 100%">
                <el-option label="手动（点「评估」）" value="OFF" />
                <el-option label="每天自动" value="DAILY" />
                <el-option label="每小时自动" value="HOURLY" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" placeholder="这个分群用来做什么" />
        </el-form-item>

        <template v-if="form.segmentType === 'DYNAMIC'">
          <el-divider content-position="left">规则条件</el-divider>
          <div class="rule-toolbar">
            <span>条件之间</span>
            <el-select v-model="form.match" style="width: 120px">
              <el-option label="全部满足" value="ALL" />
              <el-option label="任一满足" value="ANY" />
            </el-select>
            <el-button size="small" @click="addCondition">添加条件</el-button>
            <span class="muted small">字段支持 totalScore / eventCount / lastEventDays / property.xxx / dimension.xxx</span>
          </div>
          <div v-for="(cond, index) in form.conditions" :key="index" class="condition-row">
            <el-select v-model="cond.field" filterable allow-create default-first-option placeholder="字段" style="width: 220px">
              <el-option v-for="field in fieldOptions" :key="field.value" :label="field.label" :value="field.value" />
            </el-select>
            <el-select v-model="cond.op" style="width: 130px">
              <el-option v-for="op in opOptions" :key="op.value" :label="op.label" :value="op.value" />
            </el-select>
            <el-input
              v-model="cond.value"
              :disabled="cond.op === 'EXISTS' || cond.op === 'NOT_EXISTS'"
              placeholder="值（IN/NOT_IN 用逗号分隔）"
              style="flex: 1"
            />
            <el-button link type="danger" @click="removeCondition(index)">移除</el-button>
          </div>
          <el-alert
            v-if="previewResult"
            class="preview-alert"
            type="success"
            :closable="false"
            show-icon
            :title="`预览命中 ${previewResult.matched} / ${previewResult.totalCandidates}（${formatScore(previewResult.rate)}%）：${previewResult.summary}`"
          />
        </template>
        <el-alert
          v-else
          class="preview-alert"
          type="info"
          :closable="false"
          show-icon
          title="静态分群保存后，在卡片上点「导入名单」粘贴主体 ID 即可。"
        />
      </el-form>
      <template #footer>
        <el-button v-if="form.segmentType === 'DYNAMIC'" :loading="previewing" @click="preview">预览命中</el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button v-hasPermi="['profile:segment:edit']" type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 静态名单导入 -->
    <el-dialog v-model="importVisible" title="导入分群名单" width="min(560px, 94%)" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="导入方式">
          <el-radio-group v-model="importForm.mode">
            <el-radio value="REPLACE">覆盖为本批成员</el-radio>
            <el-radio value="APPEND">在当前成员上追加</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="主体类型">
          <el-select v-model="importForm.subjectType" clearable placeholder="匹配全部类型" style="width: 100%">
            <el-option label="用户" value="user" />
            <el-option label="订单" value="order" />
            <el-option label="访客" value="visitor" />
          </el-select>
        </el-form-item>
        <el-form-item label="主体 ID">
          <el-input v-model="importForm.text" type="textarea" :rows="9" placeholder="每行一个，或用逗号分隔；不存在的 ID 会自动跳过" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button v-hasPermi="['profile:segment:edit']" type="primary" :loading="importing" @click="doImport">导入</el-button>
      </template>
    </el-dialog>

    <!-- 成员 -->
    <el-drawer v-model="memberVisible" :title="`成员 · ${activeSegment?.segmentName || ''}`" size="min(720px, 92vw)" destroy-on-close>
      <div class="drawer-toolbar">
        <el-input v-model="memberQuery.keyword" placeholder="主体 ID / 名称" clearable style="width: 220px" @keyup.enter="loadMembers" />
        <el-button type="primary" @click="loadMembers">查询</el-button>
        <span class="muted small">当前分群 {{ activeSegment?.memberCount ?? 0 }} 人</span>
      </div>
      <el-table v-loading="memberLoading" class="ui-table" :data="members">
        <template #empty>
          <div class="ui-empty">
            <p class="ui-empty__title">该分群暂无成员</p>
            <p class="ui-empty__hint">先评估动态分群，或为静态分群导入名单</p>
          </div>
        </template>
        <el-table-column label="主体" min-width="180">
          <template #default="{ row }">
            <div class="subject-cell">
              <span class="subject-name">{{ row.displayName || row.subjectId }}</span>
              <span class="subject-meta">{{ row.subjectType }} · {{ row.subjectId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="总分" width="90" align="right">
          <template #default="{ row }">{{ formatScore(row.totalScore) }}</template>
        </el-table-column>
        <el-table-column prop="eventCount" label="事件数" width="80" align="right" />
        <el-table-column label="命中原因" min-width="200">
          <template #default="{ row }"
            ><span class="muted">{{ row.reason || '—' }}</span></template
          >
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goProfile(row.subjectType, row.subjectId)">看画像</el-button>
          </template>
        </el-table-column>
        <el-table-column label="最近事件" width="170">
          <template #default="{ row }">{{ row.lastEventTime || '—' }}</template>
        </el-table-column>
      </el-table>
      <pagination
        v-show="memberTotal > 0"
        v-model:page="memberQuery.pageNum"
        v-model:limit="memberQuery.pageSize"
        :total="memberTotal"
        @pagination="loadMembers"
      />
    </el-drawer>

    <!-- 运行记录（含人数趋势） -->
    <el-drawer v-model="runVisible" :title="`运行记录 · ${activeSegment?.segmentName || ''}`" size="min(720px, 92vw)" destroy-on-close>
      <v-chart v-if="runs.length >= 2" class="run-trend-chart" :option="runTrendOption" autoresize />
      <el-table v-loading="runLoading" class="ui-table" :data="runs">
        <template #empty>
          <div class="ui-empty">
            <p class="ui-empty__title">还没有运行记录</p>
            <p class="ui-empty__hint">点击分群卡片的「评估」后会留下运行留痕</p>
          </div>
        </template>
        <el-table-column label="运行 ID" prop="runId" min-width="180" />
        <el-table-column label="触发" width="110">
          <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'DONE' ? 'success' : row.status === 'FAILED' ? 'danger' : 'warning'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalCandidates" label="候选数" width="90" align="right" />
        <el-table-column prop="memberCount" label="命中数" width="90" align="right" />
        <el-table-column label="完成时间" width="170">
          <template #default="{ row }">{{ row.finishedAt || row.startedAt || '—' }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!runLoading && runs.length === 0" description="还没有运行记录" />
    </el-drawer>

    <!-- 发通知向导（预览 → 发送） -->
    <el-dialog v-model="reachVisible" :title="`发通知 · ${reachSegment?.segmentName || ''}`" width="min(620px, 94%)" destroy-on-close>
      <div v-loading="reachPreviewLoading" class="drawer-body">
        <template v-if="reachPreviewData">
          <div class="reach-summary">
            <div class="reach-stat">
              <span>当前成员</span>
              <strong>{{ reachPreviewData.memberCount }}</strong>
            </div>
            <div class="reach-stat">
              <span>可触达（登录用户）</span>
              <strong>{{ reachPreviewData.reachableCount }}</strong>
            </div>
            <div class="reach-stat">
              <span>跳过（非用户主体）</span>
              <strong>{{ reachPreviewData.skippedNonUser }}</strong>
            </div>
          </div>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            class="reach-alert"
            :title="`发送时冻结当前成员快照，逐人去重（同一人只收一次）；渠道：站内信`"
          />
          <el-form label-width="80px">
            <el-form-item label="标题">
              <el-input v-model="reachForm.title" maxlength="100" placeholder="如：低分预警周报" />
            </el-form-item>
            <el-form-item label="内容">
              <el-input v-model="reachForm.content" type="textarea" :rows="5" maxlength="1000" placeholder="站内信正文，支持简单文本" />
            </el-form-item>
          </el-form>
        </template>
      </div>
      <template #footer>
        <el-button @click="reachVisible = false">取消</el-button>
        <el-button
          v-hasPermi="['profile:reach:edit']"
          type="primary"
          :loading="reachSending"
          :disabled="!reachPreviewData || reachPreviewData.reachableCount === 0"
          @click="sendReach"
        >
          发送给 {{ reachPreviewData?.reachableCount ?? 0 }} 人
        </el-button>
      </template>
    </el-dialog>

    <!-- 触达/导出记录 -->
    <el-drawer v-model="recordsVisible" :title="`触达与导出记录 · ${activeSegment?.segmentName || ''}`" size="min(760px, 92vw)" destroy-on-close>
      <el-table
        v-loading="recordsLoading"
        class="ui-table"
        :data="reachBatches"
        row-key="reachId"
        @expand-change="(row: ProfileReachBatch, expanded: ProfileReachBatch[]) => expanded.length && loadItems(row)"
      >
        <template #empty>
          <div class="ui-empty">
            <p class="ui-empty__title">还没有触达或导出记录</p>
          </div>
        </template>
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="reach-items-wrap">
              <el-table v-loading="itemsLoadingId === row.reachId" class="ui-table" :data="reachItemsData[row.reachId] || []">
                <el-table-column prop="subjectId" label="主体 ID" min-width="160" />
                <el-table-column prop="userName" label="接收用户" width="120">
                  <template #default="{ row: item }">{{ item.userName || item.userId || '—' }}</template>
                </el-table-column>
                <el-table-column label="状态" width="90">
                  <template #default="{ row: item }">
                    <el-tag :type="item.status === 'SUCCESS' ? 'success' : item.status === 'FAILED' ? 'danger' : 'info'" size="small">
                      {{ item.status === 'SUCCESS' ? '成功' : item.status === 'FAILED' ? '失败' : '待发' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="errorMsg" label="失败原因" min-width="140">
                  <template #default="{ row: item }"
                    ><span class="muted">{{ item.errorMsg || '—' }}</span></template
                  >
                </el-table-column>
                <el-table-column prop="sentTime" label="发送时间" width="160">
                  <template #default="{ row: item }">{{ item.sentTime || '—' }}</template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="渠道" width="80">
          <template #default="{ row }">{{ row.channel === 'export' ? '导出' : '站内信' }}</template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="150" />
        <el-table-column label="结果" width="130">
          <template #default="{ row }">
            成功 {{ row.successCount ?? 0 }} / 失败 {{ row.failCount ?? 0 }}
            <el-tag v-if="row.masked === '1'" size="small" effect="plain" class="masked-tag">脱敏</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="reachStatusType(row.status)" size="small">{{ reachStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="160" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.failCount > 0"
              v-hasPermi="['profile:reach:edit']"
              link
              type="warning"
              :loading="retryingId === row.reachId"
              @click="retryReach(row)"
            >
              重试失败
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination
        v-show="recordsTotal > 0"
        v-model:page="recordsQuery.pageNum"
        v-model:limit="recordsQuery.pageSize"
        :total="recordsTotal"
        @pagination="loadRecords"
      />
      <el-empty v-if="!recordsLoading && reachBatches.length === 0" description="还没有触达或导出记录" />
    </el-drawer>

    <!-- 导出成员 -->
    <el-dialog v-model="exportVisible" :title="`导出成员 · ${activeSegment?.segmentName || ''}`" width="min(480px, 94%)" destroy-on-close>
      <el-form label-width="100px">
        <el-form-item label="脱敏导出">
          <el-switch v-model="exportForm.masked" active-text="隐藏中间字符" inactive-text="明文（会记审计）" />
        </el-form-item>
      </el-form>
      <el-alert
        :type="exportForm.masked ? 'info' : 'warning'"
        :closable="false"
        show-icon
        :title="exportForm.masked ? 'ID 与名称只保留首尾字符，如 use***01。' : '明文导出会记录一条审计，请确认有权限与必要性。'"
      />
      <template #footer>
        <el-button @click="exportVisible = false">取消</el-button>
        <el-button v-hasPermi="['profile:reach:edit']" type="primary" :loading="exporting" @click="doExport">导出 CSV</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="DcsProfileSegments" lang="ts">
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import VChart from 'vue-echarts';
import { useChartTokens } from '@/utils/chart-theme';
import { ElMessage, ElMessageBox } from 'element-plus';
import { download } from '@/utils/request';
import {
  createSegment,
  deleteSegment,
  evaluateSegment,
  importSegmentMembers,
  listDimensions,
  listSegmentRuns,
  listSegments,
  pageSegmentMembers,
  previewSegment,
  reachItems,
  reachPreview,
  reachRecords,
  reachRetry,
  reachSend,
  updateSegment,
  type ProfileDimension,
  type ProfileReachBatch,
  type ProfileReachItem,
  type ProfileReachPreview,
  type ProfileSegment,
  type ProfileSegmentMemberRow,
  type ProfileSegmentPreview,
  type ProfileSegmentRun
} from '@/api/dcs/core/profile';
import { useSourceOptions } from '@/composables/useProfileSources';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';
const { list: sourceOpts, ensureSources } = useSourceOptions('subject');
const router = useRouter();
/** 批2 看画像: 成员行直达画像详情页(成员必有主体, 无需禁用态) */
const goProfile = (subjectType: string, subjectId: string) => {
  router.push(
    `/insight/profile-detail/${activeSegment.value?.source || source.value}/${subjectId}?subjectType=${encodeURIComponent(subjectType || 'user')}`
  );
};
ensureSources();

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent]);

const source = ref('dcs');
const segments = ref<ProfileSegment[]>([]);
const loading = ref(false);
const evaluatingId = ref('');
/** 图表色随主题切换 */
const ct = useChartTokens();

const dynamicCount = computed(() => segments.value.filter((s) => s.segmentType === 'DYNAMIC').length);
const staticCount = computed(() => segments.value.filter((s) => s.segmentType === 'STATIC').length);
const segmentMemberTotal = computed(() => segments.value.reduce((sum, s) => sum + Number(s.memberCount || 0), 0));
const segmentMetrics = computed(() => [
  { label: '分群总数', value: segments.value.length },
  { label: '动态分群', value: dynamicCount.value },
  { label: '静态分群', value: staticCount.value },
  { label: '成员人次', value: segmentMemberTotal.value }
]);

const refreshModeLabel = (mode?: string) => {
  if (mode === 'HOURLY') return '自动·每小时';
  if (mode === 'DAILY') return '自动·每天';
  return '';
};

const formatScore = (value?: number | string | null) => {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  return Number.isFinite(n) ? n.toFixed(2) : String(value);
};

const parseRule = (segment: ProfileSegment): { match?: string; conditions?: { field: string; op: string; value?: any }[] } => {
  try {
    return segment.ruleConfig ? JSON.parse(segment.ruleConfig) : {};
  } catch {
    return {};
  }
};
const ruleSummary = (segment: ProfileSegment) => {
  const rule = parseRule(segment);
  const conditions = rule.conditions || [];
  if (!conditions.length) return '未配置条件';
  return conditions
    .map((c) => {
      const value = c.value === undefined || c.value === null || c.value === '' ? '' : ` ${String(c.value)}`;
      return `${c.field} ${c.op}${value}`;
    })
    .join(rule.match === 'ANY' ? ' 或 ' : ' 且 ');
};

const loadAll = async () => {
  loading.value = true;
  try {
    segments.value = await listSegments(source.value);
  } finally {
    loading.value = false;
  }
  loadDimensionFields();
};

const evaluate = async (segment: ProfileSegment) => {
  evaluatingId.value = segment.segmentId;
  try {
    const run = await evaluateSegment(segment.segmentId);
    if (run.status === 'DONE') {
      ElMessage.success(`评估完成：命中 ${run.memberCount} / ${run.totalCandidates}`);
    } else {
      ElMessage.error(run.errorMsg || `评估失败：${run.status}`);
    }
    await loadAll();
  } finally {
    evaluatingId.value = '';
  }
};

const remove = async (segment: ProfileSegment) => {
  await ElMessageBox.confirm(`删除分群「${segment.segmentName}」？历史运行记录会保留。`, '删除分群', { type: 'warning' });
  await deleteSegment(segment.segmentId);
  ElMessage.success('分群已删除');
  await loadAll();
};

// ---------------------------------------------------------------- 新建/编辑
const dialogVisible = ref(false);
const saving = ref(false);
const previewing = ref(false);
const previewResult = ref<ProfileSegmentPreview | null>(null);
/** 引擎级基础字段(任何源都有) */
const baseFieldOptions = [
  { label: '总分', value: 'totalScore' },
  { label: '事件数', value: 'eventCount' },
  { label: '最近事件天数', value: 'lastEventDays' },
  { label: '主体类型', value: 'subjectType' },
  { label: '主体 ID', value: 'subjectId' },
  { label: '显示名称', value: 'displayName' }
];
/** R2-1: 维度字段随源生成(listDimensions), 只列该源真实启用的维度, 死分群杜绝 */
const dimensionFields = ref<{ label: string; value: string }[]>([]);
const fieldOptions = computed(() => [...baseFieldOptions, ...dimensionFields.value]);
const loadDimensionFields = async () => {
  try {
    const dims = await listDimensions(source.value);
    dimensionFields.value = (dims || [])
      .filter((d) => d.enabled === '1')
      .map((d) => ({ label: '维度分：' + d.dimName, value: 'dimension.' + d.dimCode }));
  } catch {
    dimensionFields.value = [];
  }
};
const opOptions = [
  { label: '等于', value: 'EQ' },
  { label: '不等于', value: 'NEQ' },
  { label: '大于', value: 'GT' },
  { label: '大于等于', value: 'GTE' },
  { label: '小于', value: 'LT' },
  { label: '小于等于', value: 'LTE' },
  { label: '属于', value: 'IN' },
  { label: '不属于', value: 'NOT_IN' },
  { label: '包含', value: 'CONTAINS' },
  { label: '存在', value: 'EXISTS' },
  { label: '不存在', value: 'NOT_EXISTS' }
];

interface ConditionForm {
  field: string;
  op: string;
  value: string;
}
const form = reactive({
  segmentId: '',
  segmentCode: '',
  segmentName: '',
  segmentType: 'DYNAMIC',
  status: 'ENABLED',
  refreshMode: 'OFF',
  description: '',
  match: 'ALL',
  conditions: [] as ConditionForm[],
  revision: 0
});

const resetForm = () => {
  Object.assign(form, {
    segmentId: '',
    segmentCode: '',
    segmentName: '',
    segmentType: 'DYNAMIC',
    status: 'ENABLED',
    refreshMode: 'OFF',
    description: '',
    match: 'ALL',
    conditions: [{ field: 'totalScore', op: 'GTE', value: '75' }],
    revision: 0
  });
  previewResult.value = null;
};

const openCreate = () => {
  resetForm();
  dialogVisible.value = true;
};

const openEdit = (segment: ProfileSegment) => {
  resetForm();
  const rule = parseRule(segment);
  Object.assign(form, {
    segmentId: segment.segmentId,
    segmentCode: segment.segmentCode,
    segmentName: segment.segmentName,
    segmentType: segment.segmentType,
    status: segment.status,
    refreshMode: segment.refreshMode || 'OFF',
    description: segment.description || '',
    match: rule.match || 'ALL',
    conditions: (rule.conditions || []).map((c) => ({
      field: c.field,
      op: c.op,
      value: c.value === undefined || c.value === null ? '' : String(c.value)
    })),
    revision: segment.revision
  });
  if (form.conditions.length === 0) form.conditions = [{ field: 'totalScore', op: 'GTE', value: '75' }];
  dialogVisible.value = true;
};

const onTypeChange = () => {
  if (form.segmentType === 'DYNAMIC' && form.conditions.length === 0) {
    form.conditions = [{ field: 'totalScore', op: 'GTE', value: '75' }];
  }
};
const addCondition = () => {
  if (form.conditions.length >= 20) return ElMessage.warning('最多 20 个条件');
  form.conditions.push({ field: 'totalScore', op: 'GTE', value: '' });
};
const removeCondition = (index: number) => form.conditions.splice(index, 1);

const buildRule = () => ({
  match: form.match,
  conditions: form.conditions
    .filter((c) => c.field && c.op)
    .map((c) => {
      const item: Record<string, any> = { field: c.field.trim(), op: c.op };
      if (c.op !== 'EXISTS' && c.op !== 'NOT_EXISTS') {
        item.value = c.value;
      }
      return item;
    })
});

const preview = async () => {
  previewing.value = true;
  try {
    previewResult.value = await previewSegment({
      source: source.value,
      segmentType: form.segmentType,
      ruleConfig: buildRule()
    });
  } finally {
    previewing.value = false;
  }
};

const save = async () => {
  if (!form.segmentName.trim()) return ElMessage.warning('分群名称不能为空');
  if (!form.segmentId && !form.segmentCode.trim()) return ElMessage.warning('分群编码不能为空');
  if (form.segmentType === 'DYNAMIC' && form.conditions.filter((c) => c.field && c.op).length === 0) {
    return ElMessage.warning('动态分群至少需要一个条件');
  }
  saving.value = true;
  try {
    const payload: Record<string, any> = {
      source: source.value,
      segmentName: form.segmentName.trim(),
      segmentType: form.segmentType,
      status: form.status,
      refreshMode: form.segmentType === 'DYNAMIC' ? form.refreshMode : 'OFF',
      description: form.description,
      ruleConfig: form.segmentType === 'DYNAMIC' ? buildRule() : {}
    };
    if (form.segmentId) {
      payload.segmentId = form.segmentId;
      payload.revision = form.revision;
      await updateSegment(payload);
      ElMessage.success('分群已更新');
    } else {
      payload.segmentCode = form.segmentCode.trim();
      await createSegment(payload);
      ElMessage.success('分群已创建');
    }
    dialogVisible.value = false;
    await loadAll();
  } finally {
    saving.value = false;
  }
};

// ---------------------------------------------------------------- 名单导入
const importVisible = ref(false);
const importing = ref(false);
const importSegment = ref<ProfileSegment | null>(null);
const importForm = reactive({ mode: 'REPLACE', subjectType: '', text: '' });

const openImport = (segment: ProfileSegment) => {
  importSegment.value = segment;
  importForm.mode = 'REPLACE';
  importForm.subjectType = 'user';
  importForm.text = '';
  importVisible.value = true;
};
const doImport = async () => {
  if (!importSegment.value) return;
  if (!importForm.text.trim()) return ElMessage.warning('请粘贴主体 ID');
  importing.value = true;
  try {
    const result = await importSegmentMembers(importSegment.value.segmentId, {
      mode: importForm.mode,
      subjectType: importForm.subjectType || undefined,
      subjectIds: importForm.text
        .split(/[\s,;，；]+/)
        .map((v) => v.trim())
        .filter(Boolean)
    });
    ElMessage.success(`导入完成：成员 ${result.memberCount ?? 0} 人，未找到 ${result.notFound ?? 0} 个`);
    importVisible.value = false;
    await loadAll();
  } finally {
    importing.value = false;
  }
};

// ---------------------------------------------------------------- 成员 / 运行记录
const activeSegment = ref<ProfileSegment | null>(null);
const memberVisible = ref(false);
const memberLoading = ref(false);
const members = ref<ProfileSegmentMemberRow[]>([]);
const memberTotal = ref(0);
const memberQuery = reactive({ pageNum: 1, pageSize: 20, keyword: '' });

const openMembers = async (segment: ProfileSegment) => {
  activeSegment.value = segment;
  memberVisible.value = true;
  memberQuery.pageNum = 1;
  memberQuery.keyword = '';
  await loadMembers();
};
const loadMembers = async () => {
  if (!activeSegment.value) return;
  memberLoading.value = true;
  try {
    const res = await pageSegmentMembers(activeSegment.value.segmentId, {
      pageNum: memberQuery.pageNum,
      pageSize: memberQuery.pageSize,
      keyword: memberQuery.keyword || undefined
    });
    members.value = res.rows;
    memberTotal.value = res.total;
  } finally {
    memberLoading.value = false;
  }
};

const runVisible = ref(false);
const runLoading = ref(false);
const runs = ref<ProfileSegmentRun[]>([]);
const openRuns = async (segment: ProfileSegment) => {
  activeSegment.value = segment;
  runVisible.value = true;
  runLoading.value = true;
  try {
    runs.value = await listSegmentRuns(segment.segmentId);
  } finally {
    runLoading.value = false;
  }
};
const triggerLabel = (trigger?: string) =>
  trigger === 'IMPORT' ? '名单导入' : trigger === 'INIT' ? '初始化' : trigger === 'SCHEDULE' ? '定时' : '手动';

/** 人数趋势：运行记录倒序，反转为时间正序 */
const runTrendOption = computed<any>(() => {
  const ordered = [...runs.value].reverse();
  return {
    color: ct.value.series,
    title: { text: '人数趋势（按运行记录）', left: 0, top: 0, textStyle: { fontSize: 13, fontWeight: 600, color: ct.value.ink } },
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 16, top: 34, bottom: 26 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: ordered.map((run, i) => (run.finishedAt || run.startedAt || `#${i + 1}`).slice(5, 16)),
      axisLabel: { color: ct.value.faint },
      axisLine: { lineStyle: { color: ct.value.border } }
    },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: ct.value.faint }, splitLine: { lineStyle: { color: ct.value.rule } } },
    series: [
      {
        type: 'line',
        smooth: true,
        symbolSize: 6,
        areaStyle: { opacity: 0.12, color: ct.value.accent },
        itemStyle: { color: ct.value.accent },
        lineStyle: { color: ct.value.accent },
        data: ordered.map((run) => run.memberCount)
      }
    ]
  };
});

// ---------------------------------------------------------------- 触达向导 / 记录 / 导出（批9）
const onMoreCommand = (cmd: string, segment: ProfileSegment) => {
  if (cmd === 'notify') openReach(segment);
  else if (cmd === 'records') openRecords(segment);
  else if (cmd === 'export') openExport(segment);
  else if (cmd === 'edit') openEdit(segment);
  else if (cmd === 'delete') remove(segment);
};

const reachVisible = ref(false);
const reachPreviewLoading = ref(false);
const reachSending = ref(false);
const reachSegment = ref<ProfileSegment | null>(null);
const reachPreviewData = ref<ProfileReachPreview | null>(null);
const reachForm = reactive({ title: '', content: '' });

const openReach = async (segment: ProfileSegment) => {
  reachSegment.value = segment;
  reachVisible.value = true;
  reachPreviewLoading.value = true;
  reachPreviewData.value = null;
  reachForm.title = '';
  reachForm.content = '';
  try {
    reachPreviewData.value = await reachPreview(segment.segmentId);
  } finally {
    reachPreviewLoading.value = false;
  }
};
const sendReach = async () => {
  if (!reachSegment.value) return;
  if (!reachForm.title.trim()) return ElMessage.warning('请填写标题');
  if (!reachForm.content.trim()) return ElMessage.warning('请填写内容');
  reachSending.value = true;
  try {
    const batch = await reachSend(reachSegment.value.segmentId, { title: reachForm.title.trim(), content: reachForm.content.trim() });
    if (batch.status === 'DONE') {
      ElMessage.success(`发送完成：成功 ${batch.successCount} 人`);
    } else if (batch.status === 'PARTIAL') {
      ElMessage.warning(`部分成功：成功 ${batch.successCount}，失败 ${batch.failCount}（可在触达记录里重试）`);
    } else {
      ElMessage.error(batch.errorMsg || `发送失败：${batch.status}`);
    }
    reachVisible.value = false;
    await loadAll();
  } finally {
    reachSending.value = false;
  }
};

const recordsVisible = ref(false);
const recordsLoading = ref(false);
const reachBatches = ref<ProfileReachBatch[]>([]);
const recordsTotal = ref(0);
const recordsQuery = reactive({ pageNum: 1, pageSize: 10 });
const itemsLoadingId = ref('');
/** 逐人明细按批次分桶: 多行同时展开时不能共用一份数据 */
const reachItemsData = ref<Record<string, ProfileReachItem[]>>({});
const retryingId = ref('');

const openRecords = async (segment: ProfileSegment) => {
  activeSegment.value = segment;
  recordsVisible.value = true;
  recordsQuery.pageNum = 1;
  await loadRecords();
};
const loadRecords = async () => {
  if (!activeSegment.value) return;
  recordsLoading.value = true;
  try {
    const res = await reachRecords(activeSegment.value.segmentId, {
      pageNum: recordsQuery.pageNum,
      pageSize: recordsQuery.pageSize
    });
    reachBatches.value = res.rows;
    recordsTotal.value = res.total;
    reachItemsData.value = {};
  } finally {
    recordsLoading.value = false;
  }
};
const reachStatusType = (status?: string) =>
  status === 'DONE' ? ('success' as const) : status === 'FAILED' ? ('danger' as const) : ('warning' as const);
const reachStatusLabel = (status?: string) =>
  status === 'DONE' ? '完成' : status === 'PARTIAL' ? '部分成功' : status === 'FAILED' ? '失败' : '进行中';

/** 展开行时加载该批次逐人明细 */
const loadItems = async (row: ProfileReachBatch) => {
  if (itemsLoadingId.value === row.reachId) return;
  itemsLoadingId.value = row.reachId;
  try {
    reachItemsData.value[row.reachId] = await reachItems(row.reachId, 200);
  } finally {
    itemsLoadingId.value = '';
  }
};
const retryReach = async (row: ProfileReachBatch) => {
  await ElMessageBox.confirm(`重试批次中失败的 ${row.failCount} 人？已成功的不重发。`, '重试失败', { type: 'warning' });
  retryingId.value = row.reachId;
  try {
    const batch = await reachRetry(row.reachId);
    ElMessage.success(`重试完成：累计成功 ${batch.successCount}，仍失败 ${batch.failCount}`);
    await loadRecords();
  } finally {
    retryingId.value = '';
  }
};

const exportVisible = ref(false);
const exporting = ref(false);
const exportForm = reactive({ masked: true });

const openExport = (segment: ProfileSegment) => {
  activeSegment.value = segment;
  exportForm.masked = true;
  exportVisible.value = true;
};
const doExport = async () => {
  if (!activeSegment.value) return;
  exporting.value = true;
  try {
    const seg = activeSegment.value;
    await download(`/profile/segment/${seg.segmentId}/export`, { masked: String(exportForm.masked) }, `${seg.segmentName}-成员.csv`);
    exportVisible.value = false;
  } catch {
    // download 内部已提示
  } finally {
    exporting.value = false;
  }
};

onMounted(loadAll);
</script>

<style scoped>
.segment-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.segment-page :deep(.dcs-ops-header) {
  margin-bottom: 4px;
}
.segment-page :deep(.dcs-ops-header__actions) {
  flex-wrap: wrap;
}
@media (min-width: 993px) {
  .source-select {
    width: 150px;
  }
}
/* el-table 走 CSS 变量：表头无底带、行线走令牌、正文灰阶（不重写组件） */
.segment-page :deep(.el-table) {
  --el-table-border-color: var(--app-rule);
  --el-table-header-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--app-raised);
}
/* 状态药丸：统一浅底深字，形状/字号与 .ui-pill 同口径 */
.segment-page :deep(.el-tag) {
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
.segment-page :deep(.el-tag--success) {
  --el-tag-bg-color: var(--dcs-pill-pos-bg);
  --el-tag-text-color: var(--dcs-pill-pos-ink);
}
.segment-page :deep(.el-tag--warning) {
  --el-tag-bg-color: var(--dcs-pill-warn-bg);
  --el-tag-text-color: var(--dcs-pill-warn-ink);
}
.segment-page :deep(.el-tag--danger) {
  --el-tag-bg-color: var(--dcs-pill-neg-bg);
  --el-tag-text-color: var(--dcs-pill-neg-ink);
}
.segment-page :deep(.el-card__body),
.segment-page :deep(.el-drawer__body) {
  min-width: 0;
}
.segment-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 12px;
  min-height: 120px;
  min-width: 0;
}
.segment-card :deep(.el-card__body) {
  padding: 16px 18px;
}
.segment-top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: flex-start;
}
.segment-name {
  font-size: 16px;
  font-weight: 650;
}
.segment-code {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.segment-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.segment-desc {
  margin: 10px 0;
  min-height: 20px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.segment-metrics {
  display: flex;
  gap: 28px;
  padding: 10px 0;
  border-top: 1px dashed var(--el-border-color-lighter);
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.segment-metrics > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.segment-metrics span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.segment-metrics strong {
  font-size: 18px;
  font-variant-numeric: tabular-nums;
}
.metric-time {
  font-size: 13px !important;
  font-weight: 600;
}
/* 批4A: 最近 run 状态徽标(超限跳过/上次失败), 与时间同行不换行 */
.run-status-tag {
  margin-left: 6px;
}
.segment-rule {
  margin: 10px 0;
  display: flex;
  gap: 8px;
  font-size: 12px;
}
.rule-label {
  flex: none;
  color: var(--el-text-color-secondary);
}
.rule-text {
  color: var(--el-text-color-regular);
  line-height: 1.5;
  word-break: break-all;
}
.segment-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  align-items: center;
}
.run-trend-chart {
  height: 200px;
  width: 100%;
  margin-bottom: 12px;
}
.reach-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
.reach-stat {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--app-card-radius);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.reach-stat span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.reach-stat strong {
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}
.reach-alert {
  margin-bottom: 12px;
}
.reach-items-wrap {
  padding: 6px 18px;
}
.masked-tag {
  margin-left: 6px;
}
.rule-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.condition-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.preview-alert {
  margin-top: 10px;
}
.drawer-toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
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
.subject-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.35;
}
.subject-name {
  font-weight: 600;
  line-height: 1.4;
}
.subject-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.35;
}
/* U-08：断点收敛 992（原 900 自定档） */
@media (max-width: 992px) {
  .segment-grid {
    grid-template-columns: 1fr;
  }
}
</style>
