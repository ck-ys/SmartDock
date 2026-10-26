<template>
  <div class="app-container rfm-page">
    <dcs-operations-page-header
      eyebrow="画像引擎"
      title="RFM 分层与通用评分模型"
      subtitle="指标 → 分箱 → 加权 → 快照：同一套模型协议既能跑 RFM，也能跑订单、供应链、工程等任意主体。"
      :metrics="rfmMetrics"
    >
      <template #actions>
        <el-select v-model="source" class="source-select" @change="onSourceChange">
          <el-option v-for="s in sourceOpts" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-select v-model="modelId" placeholder="选择模型" class="model-select" @change="loadModelData">
          <el-option v-for="m in models" :key="m.modelId" :label="`${m.modelName} · ${m.modelVersion}`" :value="m.modelId">
            <span>{{ m.modelName }}</span>
            <span class="option-meta">{{ m.modelType }} / {{ m.modelVersion }}</span>
          </el-option>
        </el-select>
        <el-button v-hasPermi="['profile:model:edit']" :disabled="!currentModel" @click="openModelDialog('edit')">编辑模型</el-button>
        <el-button v-hasPermi="['profile:model:edit']" v-if="currentModel && currentModel.builtin !== '1'" type="danger" plain @click="removeCurrent">
          删除
        </el-button>
        <el-button v-hasPermi="['profile:model:edit']" type="primary" :disabled="!currentModel" @click="createNewModel">新建模型</el-button>
        <el-button
          v-hasPermi="['profile:model:edit']"
          :disabled="!currentModel || currentModel.status !== 'PUBLISHED'"
          :loading="running"
          @click="runCurrent"
        >
          运行模型
        </el-button>
        <el-button v-hasPermi="['profile:model:query']" :disabled="!currentModel" :loading="dryLoading" @click="runDryRun">试算预览</el-button>
        <el-button
          v-hasPermi="['profile:model:edit']"
          :disabled="!currentModel || currentModel.status === 'PUBLISHED'"
          :loading="publishing"
          @click="publishCurrent"
        >
          发布
        </el-button>
        <el-button v-hasPermi="['profile:model:query']" :disabled="!currentModel" @click="openCompare">版本对比</el-button>
        <el-button v-hasPermi="['profile:model:query']" :disabled="!currentModel" @click="openSnapshots">配置历史</el-button>
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
      </template>
    </dcs-operations-page-header>

    <div v-if="currentModel" class="model-meta">
      <el-tag :type="statusTagType(currentModel?.status)" effect="plain">{{ statusLabel(currentModel?.status) }}</el-tag>
      <el-tag v-if="currentModel?.builtin === '1'" type="info" effect="plain">内置</el-tag>
      <el-tag :type="currentModel?.enabled === '1' ? 'success' : 'info'" effect="plain">
        {{ currentModel?.enabled === '1' ? '启用中' : '已停用' }}
      </el-tag>
      <span class="mono">{{ currentModel?.modelCode }}</span>
      <span class="muted">{{ currentModel?.modelVersion }}</span>
      <span class="muted">{{ currentModel?.remark || '无说明' }}</span>
      <span class="muted">最近计算 {{ overview.lastCalcTime || '尚未运行' }}</span>
    </div>

    <el-empty v-if="!loading && models.length === 0" description="当前业务源还没有评分模型">
      <el-button v-hasPermi="['profile:model:edit']" @click="createNewModel">用 RFM 预设新建</el-button>
    </el-empty>

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
          <el-button @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </div>
        <el-table v-loading="tableLoading" class="ui-table" :data="rows" row-key="subjectId">
          <template #empty>
            <div class="ui-empty">
              <p class="ui-empty__title">当前筛选下没有评分主体</p>
              <p class="ui-empty__hint">调整筛选条件，或先运行一次模型</p>
            </div>
          </template>
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
          <el-table-column prop="eventCount" label="事件数" width="90" align="right" />
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
    <el-dialog v-model="modelDialogVisible" :title="modelForm.modelId ? '编辑评分模型' : '新建评分模型'" width="min(760px, 94%)" destroy-on-close>
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
              <el-input v-model="modelForm.modelVersion" :disabled="!!modelForm.modelId" maxlength="32" placeholder="v1" />
              <span v-if="modelForm.modelId" class="muted small">编辑态版本锁定，发布时自动递增</span>
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
    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="min(620px, 92vw)" destroy-on-close>
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
          <el-table :data="drawerDimensions" class="ui-table">
            <el-table-column label="维度" min-width="130">
              <template #default="{ row }"
                >{{ row.name }} <span class="mono muted">{{ row.code }}</span></template
              >
            </el-table-column>
            <el-table-column label="指标值" width="100" align="right">
              <template #default="{ row }">{{ formatScore(row.value) }}</template>
            </el-table-column>
            <el-table-column label="分箱" width="90" align="center">
              <template #default="{ row }">{{ row.score }} / {{ row.maxScore }}</template>
            </el-table-column>
            <el-table-column label="标准分" width="90" align="right">
              <template #default="{ row }">
                <span :class="scoreClass(Number(row.normalizedScore))">{{ formatScore(row.normalizedScore) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="权重" width="80" align="right">
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

    <!-- 试算预览（草稿影子计算，不落快照） -->
    <el-drawer v-model="dryVisible" title="试算预览（影子计算，不落快照）" size="min(720px, 92vw)" destroy-on-close>
      <div v-loading="dryLoading" class="drawer-body">
        <template v-if="dryResult">
          <el-alert :type="psiAlertType(dryResult.psi?.verdict)" :closable="false" show-icon class="psi-alert">
            <template #title>
              稳定性 PSI = {{ formatScore(dryResult.psi?.psi) }}
              <el-tag size="small" :type="psiTagType(dryResult.psi?.verdict)" effect="light" class="psi-tag">{{
                psiLabel(dryResult.psi?.verdict)
              }}</el-tag>
              <span class="muted small">（&lt;0.1 稳定 / 0.1~0.25 中等 / &gt;0.25 显著偏移）</span>
            </template>
          </el-alert>
          <div class="delta-chips">
            <span class="delta-chip up">升 {{ dryResult.deltaStat?.up ?? 0 }}</span>
            <span class="delta-chip down">降 {{ dryResult.deltaStat?.down ?? 0 }}</span>
            <span class="delta-chip flat">持平 {{ dryResult.deltaStat?.flat ?? 0 }}</span>
            <span class="delta-chip muted">仅旧版 {{ dryResult.deltaStat?.onlyInBase ?? 0 }}</span>
            <span class="delta-chip muted">仅新版 {{ dryResult.deltaStat?.onlyInCand ?? 0 }}</span>
          </div>
          <h4 class="section-title">分层分布对比</h4>
          <el-table :data="dryResult.distribution || []" class="ui-table">
            <el-table-column label="分层" min-width="110">
              <template #default="{ row }">
                <el-tag :type="levelTagType(row.level)" effect="light">{{ row.level }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="当前" width="100" align="right">
              <template #default="{ row }">{{ row.baseCount }}（{{ formatScore(row.baseRate) }}%）</template>
            </el-table-column>
            <el-table-column label="草稿试算" width="100" align="right">
              <template #default="{ row }">{{ row.candCount }}（{{ formatScore(row.candRate) }}%）</template>
            </el-table-column>
            <el-table-column label="人数变化" width="100" align="right">
              <template #default="{ row }">
                <span :class="Number(row.delta) > 0 ? 'delta-up' : Number(row.delta) < 0 ? 'delta-down' : 'muted'">
                  {{ Number(row.delta) > 0 ? '+' : '' }}{{ row.delta }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="样本量" width="110" align="right">
              <template #default="{ row }">
                <el-tag v-if="row.smallSample" type="warning" size="small" effect="plain">小样本</el-tag>
                <span v-else class="muted small">充足</span>
              </template>
            </el-table-column>
          </el-table>
          <h4 class="section-title">Top 变化主体</h4>
          <el-table :data="dryResult.topMovers || []" class="ui-table" max-height="320">
            <el-table-column label="主体" min-width="160">
              <template #default="{ row }">
                <div class="subject-cell">
                  <span class="subject-name">{{ row.displayName || row.subjectId }}</span>
                  <span class="subject-meta">{{ row.subjectType }} · {{ row.subjectId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="旧分" width="80" align="right">
              <template #default="{ row }">{{ formatScore(row.baseScore) }}</template>
            </el-table-column>
            <el-table-column label="新分" width="80" align="right">
              <template #default="{ row }">{{ formatScore(row.candScore) }}</template>
            </el-table-column>
            <el-table-column label="变化" width="90" align="right">
              <template #default="{ row }">
                <span :class="Number(row.delta) > 0 ? 'delta-up' : 'delta-down'">
                  {{ Number(row.delta) > 0 ? '+' : '' }}{{ formatScore(row.delta) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="分层迁移" min-width="150">
              <template #default="{ row }">
                <span v-if="row.baseLevel !== row.candLevel">{{ row.baseLevel || '—' }} → {{ row.candLevel || '—' }}</span>
                <span v-else class="muted">{{ row.candLevel || '—' }}</span>
              </template>
            </el-table-column>
          </el-table>
          <el-alert
            class="psi-alert"
            type="info"
            :closable="false"
            title="试算不落快照、不影响线上分数；确认分布合理后点「发布」才会真正生效并触发重算。"
          />
        </template>
      </div>
    </el-drawer>

    <!-- 版本对比（两个已完成 run） -->
    <el-drawer v-model="compareVisible" title="版本对比" size="min(720px, 92vw)" destroy-on-close>
      <div class="compare-picker">
        <el-select v-model="compareRunA" placeholder="旧版本 run" style="width: 300px" @change="loadCompare">
          <el-option
            v-for="run in doneRuns"
            :key="run.runId"
            :label="`${run.modelVersion} · ${run.finishedAt || run.startedAt || ''}`"
            :value="run.runId"
          />
        </el-select>
        <span class="muted">对比</span>
        <el-select v-model="compareRunB" placeholder="新版本 run" style="width: 300px" @change="loadCompare">
          <el-option
            v-for="run in doneRuns"
            :key="run.runId"
            :label="`${run.modelVersion} · ${run.finishedAt || run.startedAt || ''}`"
            :value="run.runId"
          />
        </el-select>
      </div>
      <div v-loading="compareLoading" class="drawer-body">
        <template v-if="compareResult">
          <div class="run-brief">
            <div class="run-brief-item">
              <span class="muted">版本 A</span>
              <strong>{{ compareResult.runA?.modelVersion }}</strong>
              <span class="muted small">{{ compareResult.runA?.finishedAt || compareResult.runA?.startedAt }}</span>
            </div>
            <div class="run-brief-item">
              <span class="muted">版本 B</span>
              <strong>{{ compareResult.runB?.modelVersion }}</strong>
              <span class="muted small">{{ compareResult.runB?.finishedAt || compareResult.runB?.startedAt }}</span>
            </div>
          </div>
          <el-alert :type="psiAlertType(compareResult.psi?.verdict)" :closable="false" show-icon class="psi-alert">
            <template #title>
              稳定性 PSI = {{ formatScore(compareResult.psi?.psi) }}
              <el-tag size="small" :type="psiTagType(compareResult.psi?.verdict)" effect="light" class="psi-tag">{{
                psiLabel(compareResult.psi?.verdict)
              }}</el-tag>
            </template>
          </el-alert>
          <div class="delta-chips">
            <span class="delta-chip up">升 {{ compareResult.deltaStat?.up ?? 0 }}</span>
            <span class="delta-chip down">降 {{ compareResult.deltaStat?.down ?? 0 }}</span>
            <span class="delta-chip flat">持平 {{ compareResult.deltaStat?.flat ?? 0 }}</span>
            <span class="delta-chip muted">仅 A {{ compareResult.deltaStat?.onlyInBase ?? 0 }}</span>
            <span class="delta-chip muted">仅 B {{ compareResult.deltaStat?.onlyInCand ?? 0 }}</span>
          </div>
          <h4 class="section-title">等级迁移</h4>
          <el-table v-if="(compareResult.migration || []).length" :data="compareResult.migration" class="ui-table">
            <el-table-column prop="path" label="迁移路径" min-width="200" />
            <el-table-column prop="count" label="人数" width="100" align="right" />
          </el-table>
          <el-empty v-else :image-size="60" description="两个版本分层完全一致，无迁移" />
          <h4 class="section-title">分层分布对比</h4>
          <el-table :data="compareResult.distribution || []" class="ui-table">
            <el-table-column label="分层" min-width="110">
              <template #default="{ row }">
                <el-tag :type="levelTagType(row.level)" effect="light">{{ row.level }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="版本 A" width="120" align="right">
              <template #default="{ row }">{{ row.baseCount }}（{{ formatScore(row.baseRate) }}%）</template>
            </el-table-column>
            <el-table-column label="版本 B" width="120" align="right">
              <template #default="{ row }">{{ row.candCount }}（{{ formatScore(row.candRate) }}%）</template>
            </el-table-column>
            <el-table-column label="人数变化" width="100" align="right">
              <template #default="{ row }">
                <span :class="Number(row.delta) > 0 ? 'delta-up' : Number(row.delta) < 0 ? 'delta-down' : 'muted'">
                  {{ Number(row.delta) > 0 ? '+' : '' }}{{ row.delta }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="样本量" width="110" align="right">
              <template #default="{ row }">
                <el-tag v-if="row.smallSample" type="warning" size="small" effect="plain">小样本</el-tag>
                <span v-else class="muted small">充足</span>
              </template>
            </el-table-column>
          </el-table>
          <h4 class="section-title">Top 变化主体</h4>
          <el-table :data="compareResult.topMovers || []" class="ui-table" max-height="320">
            <el-table-column label="主体" min-width="160">
              <template #default="{ row }">
                <div class="subject-cell">
                  <span class="subject-name">{{ row.displayName || row.subjectId }}</span>
                  <span class="subject-meta">{{ row.subjectType }} · {{ row.subjectId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="A 分" width="80" align="right">
              <template #default="{ row }">{{ formatScore(row.baseScore) }}</template>
            </el-table-column>
            <el-table-column label="B 分" width="80" align="right">
              <template #default="{ row }">{{ formatScore(row.candScore) }}</template>
            </el-table-column>
            <el-table-column label="变化" width="90" align="right">
              <template #default="{ row }">
                <span :class="Number(row.delta) > 0 ? 'delta-up' : 'delta-down'">
                  {{ Number(row.delta) > 0 ? '+' : '' }}{{ formatScore(row.delta) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="分层迁移" min-width="150">
              <template #default="{ row }">
                <span v-if="row.baseLevel !== row.candLevel">{{ row.baseLevel || '—' }} → {{ row.candLevel || '—' }}</span>
                <span v-else class="muted">{{ row.candLevel || '—' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else-if="!compareLoading" description="选择两个已完成的运行记录开始对比" />
      </div>
    </el-drawer>

    <!-- 配置历史（发布存档 + 回滚） -->
    <el-dialog v-model="snapshotsVisible" title="配置版本历史" width="min(680px, 94%)" destroy-on-close>
      <el-table v-loading="snapshotsLoading" class="ui-table" :data="snapshots">
        <template #empty>
          <div class="ui-empty">
            <p class="ui-empty__title">还没有配置版本</p>
            <p class="ui-empty__hint">发布模型后每次配置变更都会留一个版本</p>
          </div>
        </template>
        <el-table-column prop="modelVersion" label="版本" width="90" />
        <el-table-column prop="scoreMax" label="标准分上限" width="100" align="right" />
        <el-table-column label="发布时间" width="170">
          <template #default="{ row }">{{ row.createTime || '—' }}</template>
        </el-table-column>
        <el-table-column label="配置" min-width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewSnapshotConfig(row)">查看 JSON</el-button>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              v-hasPermi="['profile:model:edit']"
              link
              type="warning"
              :disabled="currentModel?.status !== 'DRAFT' && row.modelVersion === currentModel?.modelVersion"
              @click="rollbackTo(row)"
            >
              回滚到此版
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 存档配置查看 -->
    <el-dialog v-model="snapshotConfigVisible" :title="`配置 JSON · ${activeSnapshotVersion}`" width="min(680px, 94%)" destroy-on-close>
      <el-input :model-value="activeSnapshotJson" type="textarea" :rows="18" readonly class="mono config-area" spellcheck="false" />
    </el-dialog>
  </div>
</template>

<script setup name="DcsProfileRfm" lang="ts">
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import VChart from 'vue-echarts';
import { useChartTokens } from '@/utils/chart-theme';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  compareModelRuns,
  createModel,
  deleteModel,
  dryRunModel,
  getModelOverview,
  getModelPreset,
  getModelSubject,
  getModelTrend,
  listModelConfigSnapshots,
  listModelRuns,
  getRiskBands,
  listModels,
  pageModelSubjects,
  publishModel,
  rollbackModel,
  runModel,
  updateModel,
  type ProfileModelCompareResult,
  type ProfileModelConfigSnapshot,
  type ProfileModelOverview,
  type ProfileModelRun,
  type ProfileModelSnapshot,
  type ProfileModelSubjectDetail,
  type ProfileModelSubjectRow,
  type ProfileModelDimensionEntry,
  type ProfileModelTrendPoint,
  type ProfileScoreModel
} from '@/api/dcs/core/profile';
import { useSourceOptions } from '@/composables/useProfileSources';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';
const { list: sourceOpts, ensureSources } = useSourceOptions('subject');
ensureSources();

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent]);

const router = useRouter();

const source = ref('dcs');
const models = ref<ProfileScoreModel[]>([]);
const modelId = ref<string>('');
const overview = ref<Partial<ProfileModelOverview>>({});
const trend = ref<ProfileModelTrendPoint[]>([]);
/** 图表色随主题切换 */
const ct = useChartTokens();
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
const rfmMetrics = computed(() => [
  { label: '已评分主体', value: overview.value.scoredCount ?? '—' },
  { label: '平均标准分', value: formatScore(overview.value.avgNormalizedScore) },
  { label: '分层数', value: overview.value.levels?.length ?? '—' }
]);
/** R2-1: 档位阈值接 /profile/risk-bands 单源, 不再硬编码 75/60 */
const riskBands = reactive({ lowMin: 75, highBelow: 60 });
const scoreColor = (value: number) =>
  value >= riskBands.lowMin ? 'var(--el-color-success)' : value >= riskBands.highBelow ? 'var(--el-color-warning)' : 'var(--el-color-danger)';
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
  return type === 'success'
    ? 'var(--el-color-success)'
    : type === 'primary'
      ? 'var(--app-accent)'
      : type === 'warning'
        ? 'var(--el-color-warning)'
        : 'var(--el-color-danger)';
};

// ---------------------------------------------------------------- 发布纪律（批9）
const statusTagType = (status?: string) => {
  if (status === 'PUBLISHED') return 'success' as const;
  if (status === 'DRAFT') return 'warning' as const;
  if (status === 'ARCHIVED') return 'info' as const;
  return 'info' as const;
};
const statusLabel = (status?: string) => {
  if (status === 'PUBLISHED') return '已发布';
  if (status === 'DRAFT') return '草稿';
  if (status === 'ARCHIVED') return '已归档';
  return status || '—';
};
const psiAlertType = (verdict?: string) =>
  verdict === 'SHIFTED' ? ('error' as const) : verdict === 'MODERATE' ? ('warning' as const) : ('success' as const);
const psiTagType = (verdict?: string) =>
  verdict === 'SHIFTED' ? ('danger' as const) : verdict === 'MODERATE' ? ('warning' as const) : ('success' as const);
const psiLabel = (verdict?: string) => (verdict === 'SHIFTED' ? '显著偏移' : verdict === 'MODERATE' ? '中等偏移' : '稳定');

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
  color: ct.value.series,
  tooltip: { trigger: 'axis' },
  grid: { left: 46, right: 20, top: 20, bottom: 32 },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: trend.value.map((p) => p.day),
    axisLabel: { color: ct.value.faint },
    axisLine: { lineStyle: { color: ct.value.border } }
  },
  yAxis: { type: 'value', min: 0, max: 100, axisLabel: { color: ct.value.faint }, splitLine: { lineStyle: { color: ct.value.rule } } },
  series: [
    {
      type: 'line',
      smooth: true,
      symbolSize: 6,
      areaStyle: { opacity: 0.12, color: ct.value.accent },
      itemStyle: { color: ct.value.accent },
      lineStyle: { color: ct.value.accent },
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
      const wasPublished = currentModel.value?.status === 'PUBLISHED';
      await updateModel(payload);
      ElMessage.success(wasPublished ? '模型已保存为草稿（线上分数仍用旧版本，发布后生效）' : '模型已更新');
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
  router.push(`/insight/profile-detail/${source.value}/${row.subjectId}?subjectType=${encodeURIComponent(row.subjectType || 'user')}`);

// ---------------------------------------------------------------- 试算 / 发布 / 对比 / 配置历史（批9）
const dryVisible = ref(false);
const dryLoading = ref(false);
const dryResult = ref<ProfileModelCompareResult | null>(null);

const runDryRun = async () => {
  if (!modelId.value) return;
  dryVisible.value = true;
  dryLoading.value = true;
  dryResult.value = null;
  try {
    dryResult.value = await dryRunModel(modelId.value);
  } finally {
    dryLoading.value = false;
  }
};

const publishing = ref(false);
const publishCurrent = async () => {
  const m = currentModel.value;
  if (!m) return;
  const { value: note } = await ElMessageBox.prompt(
    `将「${m.modelName}」当前草稿配置发布生效：版本自动递增（${m.modelVersion} → 下一版），配置存档并可回滚；发布后立即触发全量重算。填写发布说明（可选）：`,
    '发布模型',
    { inputPlaceholder: '如：提高了活跃度权重', inputType: 'textarea', inputValue: '' }
  ).catch(() => ({ value: undefined }) as any);
  if (note === undefined) return;
  publishing.value = true;
  try {
    const out = await publishModel(m.modelId, { note: note || undefined });
    const run = out?.run;
    if (run?.status === 'DONE') {
      ElMessage.success(`已发布 ${out.version} 并完成重算（${run.doneSubjects} 个主体）`);
    } else {
      ElMessage.warning(`已发布 ${out.version}，重算${run?.status === 'RUNNING' ? '进行中' : '失败：' + (run?.errorMsg || run?.status)}`);
    }
    await loadAll();
  } finally {
    publishing.value = false;
  }
};

const compareVisible = ref(false);
const compareLoading = ref(false);
const compareRunA = ref('');
const compareRunB = ref('');
const compareResult = ref<ProfileModelCompareResult | null>(null);
const doneRuns = ref<ProfileModelRun[]>([]);

const openCompare = async () => {
  if (!modelId.value) return;
  compareVisible.value = true;
  compareResult.value = null;
  compareRunA.value = '';
  compareRunB.value = '';
  const all = await listModelRuns(modelId.value);
  doneRuns.value = all.filter((run) => run.status === 'DONE');
  if (doneRuns.value.length >= 2) {
    // 默认拿最近两次完成的 run
    compareRunA.value = doneRuns.value[1].runId;
    compareRunB.value = doneRuns.value[0].runId;
    await loadCompare();
  }
};
const loadCompare = async () => {
  if (!modelId.value || !compareRunA.value || !compareRunB.value || compareRunA.value === compareRunB.value) return;
  compareLoading.value = true;
  compareResult.value = null;
  try {
    compareResult.value = await compareModelRuns(modelId.value, compareRunA.value, compareRunB.value);
  } finally {
    compareLoading.value = false;
  }
};

const snapshotsVisible = ref(false);
const snapshotsLoading = ref(false);
const snapshots = ref<ProfileModelConfigSnapshot[]>([]);
const snapshotConfigVisible = ref(false);
const activeSnapshotVersion = ref('');
const activeSnapshotJson = ref('');

const openSnapshots = async () => {
  if (!modelId.value) return;
  snapshotsVisible.value = true;
  snapshotsLoading.value = true;
  try {
    snapshots.value = await listModelConfigSnapshots(modelId.value);
  } finally {
    snapshotsLoading.value = false;
  }
};
const viewSnapshotConfig = (row: ProfileModelConfigSnapshot) => {
  activeSnapshotVersion.value = row.modelVersion;
  activeSnapshotJson.value = prettyJson(row.config);
  snapshotConfigVisible.value = true;
};
const rollbackTo = async (row: ProfileModelConfigSnapshot) => {
  const m = currentModel.value;
  if (!m) return;
  await ElMessageBox.confirm(
    `把「${m.modelName}」回滚到版本 ${row.modelVersion}？该版本配置会立即写回并触发全量重算，线上分数以重算结果为准。`,
    '回滚配置',
    { type: 'warning' }
  );
  await rollbackModel(m.modelId, row.modelVersion);
  ElMessage.success(`已回滚到 ${row.modelVersion} 并触发重算`);
  snapshotsVisible.value = false;
  await loadAll();
};

onMounted(() => {
  loadAll();
  getRiskBands()
    .then((b) => {
      riskBands.lowMin = Number(b.lowMin);
      riskBands.highBelow = Number(b.highBelow);
    })
    .catch(() => undefined);
});
</script>

<style scoped>
.rfm-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.rfm-page :deep(.dcs-ops-header) {
  margin-bottom: 4px;
}
.rfm-page :deep(.dcs-ops-header__actions) {
  flex-wrap: wrap;
}
@media (min-width: 993px) {
  .source-select {
    width: 150px;
  }
  .model-select {
    width: 220px;
  }
}
/* el-table 走 CSS 变量：表头无底带、行线走令牌、正文灰阶（不重写组件） */
.rfm-page :deep(.el-table) {
  --el-table-border-color: var(--app-rule);
  --el-table-header-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--app-raised);
}
/* 模型分列两行（进度条 + 原始分）压掉多余行高，整行落回 44 */
.rfm-page .score-cell {
  line-height: 20px;
}
.rfm-page .score-cell + .muted.small {
  line-height: 16px;
}
/* 状态药丸：统一浅底深字，形状/字号与 .ui-pill 同口径 */
.rfm-page :deep(.el-tag) {
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
.rfm-page :deep(.el-tag--success) {
  --el-tag-bg-color: var(--dcs-pill-pos-bg);
  --el-tag-text-color: var(--dcs-pill-pos-ink);
}
.rfm-page :deep(.el-tag--warning) {
  --el-tag-bg-color: var(--dcs-pill-warn-bg);
  --el-tag-text-color: var(--dcs-pill-warn-ink);
}
.rfm-page :deep(.el-tag--danger) {
  --el-tag-bg-color: var(--dcs-pill-neg-bg);
  --el-tag-text-color: var(--dcs-pill-neg-ink);
}
.rfm-page :deep(.el-card__body),
.rfm-page :deep(.el-drawer__body) {
  min-width: 0;
}
.option-meta {
  float: right;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-left: 12px;
}
.model-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
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
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
  min-width: 0;
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
.psi-alert {
  margin-bottom: 12px;
}
.psi-tag {
  margin-left: 8px;
}
.delta-chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.delta-chip {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px;
  padding: 2px 12px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.delta-chip.up {
  color: var(--el-color-success);
  border-color: var(--el-color-success-light-7);
}
.delta-chip.down {
  color: var(--el-color-danger);
  border-color: var(--el-color-danger-light-7);
}
.delta-chip.flat {
  color: var(--el-text-color-regular);
}
.section-title {
  margin: 14px 0 8px;
  font-size: 14px;
}
.delta-up {
  color: var(--el-color-success);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.delta-down {
  color: var(--el-color-danger);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.compare-picker {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
}
.run-brief {
  display: flex;
  gap: 28px;
  margin-bottom: 12px;
}
.run-brief-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.run-brief-item strong {
  font-size: 16px;
}
</style>
