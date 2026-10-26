<template>
  <div class="app-container studio-page">
    <dcs-operations-page-header eyebrow="画像引擎" title="画像工作室" subtitle="配置指标口径与权重版本：维度改口径，规则改权重，发布后再重算生效。">
      <template #actions>
        <el-select v-model="source" class="source-select" @change="onSourceChange">
          <el-option v-for="s in sourceOpts" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-button @click="goBack">返回画像</el-button>
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
      </template>
    </dcs-operations-page-header>
    <div class="hero-hints">
      <el-tag type="info" effect="plain">维度 = 指标口径</el-tag>
      <el-tag type="info" effect="plain">规则 = 权重版本</el-tag>
      <el-tag type="warning" effect="plain">修改后需重算才会进入新快照</el-tag>
    </div>

    <el-card shadow="never" class="main-card">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="维度配置" name="dimension">
          <div class="toolbar">
            <div class="toolbar-left">
              <el-button v-hasPermi="['profile:dimension:edit']" type="primary" @click="openCreateDimension">新建维度</el-button>
              <el-button :loading="coverageLoading" @click="loadDimensions">刷新覆盖率</el-button>
            </div>
            <div class="toolbar-right muted">共 {{ dimensions.length }} 个维度，启用 {{ enabledDimensions.length }} 个</div>
          </div>

          <el-table v-loading="dimensionLoading" class="ui-table" :data="dimensionRows" row-key="dimensionId">
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">该业务源还没有维度</p>
                <p class="ui-empty__hint">先新建一个 EVENT_AGG 维度</p>
              </div>
            </template>
            <el-table-column label="维度" min-width="200">
              <template #default="{ row }">
                <div class="dim-cell">
                  <div class="dim-title">
                    <span class="dim-name">{{ row.dimName }}</span>
                    <el-tag v-if="row.builtin === '1'" size="small" type="info" effect="plain">内置</el-tag>
                  </div>
                  <span class="dim-code mono">{{ row.dimCode }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="计算方式" width="130">
              <template #default="{ row }">
                <el-tag size="small" :type="row.calcMode === 'STRATEGY' ? 'warning' : 'primary'" effect="plain">
                  {{ row.calcMode }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="打分上限" width="100" prop="scoreMax" />
            <el-table-column label="覆盖率" min-width="190">
              <template #default="{ row }">
                <div class="coverage-cell">
                  <el-progress
                    :percentage="coveragePercent(row.dimCode)"
                    :stroke-width="8"
                    :show-text="false"
                    :color="coverageColor(coveragePercent(row.dimCode))"
                  />
                  <span class="coverage-text">
                    {{ coverageOf(row.dimCode)?.coveredSubjects ?? 0 }}/{{ coverageOf(row.dimCode)?.subjectCount ?? 0 }}
                    <span class="muted">（{{ formatRate(coverageOf(row.dimCode)?.coverageRate) }}）</span>
                  </span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.enabled === '1' ? 'success' : 'info'">
                  {{ row.enabled === '1' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="排序" width="80" prop="sortOrder" />
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button v-hasPermi="['profile:dimension:edit']" link type="primary" @click="openEditDimension(row)">编辑</el-button>
                <el-button link type="primary" @click="openPreview(row)">试算</el-button>
                <el-button
                  v-hasPermi="['profile:dimension:edit']"
                  v-if="row.enabled === '1'"
                  link
                  type="danger"
                  :disabled="row.builtin === '1'"
                  @click="disableDim(row)"
                >
                  停用
                </el-button>
                <el-button v-hasPermi="['profile:dimension:edit']" v-else link type="success" @click="openEditDimension(row)">启用</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-alert
            class="tip"
            type="info"
            :closable="false"
            show-icon
            title="EVENT_AGG 维度由事件聚合 + 线性/分档打分得到；STRATEGY 维度由业务 Bean 计算，只在管理面展示，不能试算。"
          />
        </el-tab-pane>

        <el-tab-pane label="权重规则" name="rule">
          <div class="rule-layout">
            <div class="rule-col">
              <div class="section-title">
                <span>当前生效版本</span>
                <el-tag v-if="currentRule" size="small" type="success" effect="dark">
                  {{ currentRule.ruleVersion }}
                </el-tag>
              </div>
              <div v-if="currentRule" class="rule-card">
                <div class="rule-head">
                  <div>
                    <div class="rule-name">{{ currentRule.ruleName || currentRule.ruleVersion }}</div>
                    <div class="muted small">发布 {{ currentRule.publishedAt || '—' }} · revision {{ currentRule.revision }}</div>
                  </div>
                  <div class="sum-chip ok">权重合计 {{ formatNumber(weightSumOf(currentRule), 4) }}</div>
                </div>
                <div v-if="currentRule.remark" class="rule-remark">{{ currentRule.remark }}</div>
                <div class="weight-list">
                  <div v-for="item in weightEntries(currentRule)" :key="item.code" class="weight-read">
                    <span class="weight-code mono">{{ dimensionName(item.code) }}</span>
                    <el-progress :percentage="Math.min(100, Math.round(item.value * 100))" :stroke-width="8" :show-text="false" />
                    <span class="weight-value">{{ formatNumber(item.value, 4) }}</span>
                  </div>
                </div>
              </div>
              <el-empty v-else description="该业务源还没有已发布规则，先创建草稿并发布" :image-size="70" />
            </div>

            <div class="rule-col">
              <div class="section-title">
                <span>草稿与发布</span>
                <el-tag v-if="draft" size="small" type="warning" effect="dark">DRAFT</el-tag>
                <el-tag v-else size="small" type="info" effect="plain">无草稿</el-tag>
              </div>

              <div v-if="!draft" class="draft-create">
                <el-form label-width="92px" class="compact-form">
                  <el-form-item label="规则名称">
                    <el-input v-model="createForm.ruleName" placeholder="留空则自动命名" maxlength="100" />
                  </el-form-item>
                  <el-form-item label="复制来源">
                    <el-select v-model="createForm.sourceRuleId" clearable placeholder="不复制，从均衡权重开始" style="width: 100%">
                      <el-option
                        v-for="rule in copyableRules"
                        :key="rule.ruleId"
                        :label="`${rule.ruleVersion} · ${rule.ruleName || '未命名'}`"
                        :value="String(rule.ruleId)"
                      />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="权重模板">
                    <el-select v-model="createForm.templateCode" clearable placeholder="可选，创建后套用" style="width: 100%">
                      <el-option v-for="tpl in ruleTemplates" :key="tpl.code" :label="`${tpl.name} · ${tpl.description}`" :value="tpl.code" />
                    </el-select>
                  </el-form-item>
                </el-form>
                <el-button v-hasPermi="['profile:rule:edit']" type="primary" :loading="ruleActionLoading" @click="createDraft">创建草稿</el-button>
                <p class="muted small">同一业务源同时只能有一个草稿；发布前权重必须覆盖全部启用维度且合计为 1。</p>
              </div>

              <div v-else class="draft-panel">
                <el-form label-width="76px" class="compact-form">
                  <el-form-item label="名称">
                    <el-input v-model="draftMeta.ruleName" maxlength="100" />
                  </el-form-item>
                  <el-form-item label="说明">
                    <el-input v-model="draftMeta.remark" maxlength="500" placeholder="本次调整的原因" />
                  </el-form-item>
                </el-form>

                <div class="weight-editor">
                  <div v-for="dim in enabledDimensions" :key="dim.dimCode" class="weight-row">
                    <div class="weight-label">
                      <span>{{ dim.dimName }}</span>
                      <span class="mono muted small">{{ dim.dimCode }}</span>
                    </div>
                    <el-slider v-model="draftWeights[dim.dimCode]" :min="0" :max="1" :step="0.01" :show-tooltip="false" class="weight-slider" />
                    <el-input-number
                      v-model="draftWeights[dim.dimCode]"
                      :min="0"
                      :max="1"
                      :step="0.01"
                      :precision="4"
                      controls-position="right"
                      class="weight-input"
                    />
                  </div>
                  <el-empty v-if="enabledDimensions.length === 0" description="没有启用的维度，无法配权重" :image-size="60" />
                </div>

                <div class="draft-actions">
                  <div :class="['sum-chip', sumOk ? 'ok' : 'warn']">
                    合计 {{ formatNumber(draftSum, 4) }}
                    <span v-if="!sumOk" class="sum-hint">需等于 1</span>
                  </div>
                  <el-button v-hasPermi="['profile:rule:edit']" size="small" @click="balanceWeights">平均分配</el-button>
                  <el-button v-hasPermi="['profile:rule:edit']" size="small" @click="normalizeWeights">归一化</el-button>
                  <el-select v-model="applyTemplateCode" clearable placeholder="套用模板" size="small" style="width: 150px">
                    <el-option v-for="tpl in ruleTemplates" :key="tpl.code" :label="tpl.name" :value="tpl.code" />
                  </el-select>
                  <el-button v-hasPermi="['profile:rule:edit']" size="small" @click="applyTemplate">套用</el-button>
                </div>

                <div class="draft-actions wrap">
                  <el-button v-hasPermi="['profile:rule:edit']" size="small" :loading="ruleActionLoading" @click="saveDraft()">保存草稿</el-button>
                  <el-button v-hasPermi="['profile:rule:edit']" size="small" @click="validateDraft">校验</el-button>
                  <el-button v-hasPermi="['profile:rule:edit']" size="small" @click="showDiff">对比当前</el-button>
                  <el-button v-hasPermi="['profile:rule:edit']" size="small" @click="showImpact">影响评估</el-button>
                  <el-button v-hasPermi="['profile:rule:edit']" size="small" @click="openPublish">发布</el-button>
                  <el-button v-hasPermi="['profile:rule:edit']" type="danger" plain size="small" @click="deleteDraft">删除草稿</el-button>
                </div>
              </div>
            </div>
          </div>

          <el-divider content-position="left">版本历史</el-divider>
          <el-table v-loading="ruleLoading" class="ui-table" :data="ruleHistory">
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">还没有权重版本</p>
                <p class="ui-empty__hint">创建草稿并发布后会在这里留下版本记录</p>
              </div>
            </template>
            <el-table-column label="版本" min-width="180">
              <template #default="{ row }">
                <div class="dim-cell">
                  <span class="dim-name">{{ row.ruleVersion }}</span>
                  <span class="muted small">{{ row.ruleName || '未命名' }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="ruleStatusType(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="权重" min-width="280">
              <template #default="{ row }">{{ weightSummary(row) }}</template>
            </el-table-column>
            <el-table-column label="revision" width="90" prop="revision" />
            <el-table-column label="发布时间" width="180">
              <template #default="{ row }">{{ row.publishedAt || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="190" fixed="right">
              <template #default="{ row }">
                <el-button v-hasPermi="['profile:rule:edit']" v-if="row.status === 'DRAFT'" link type="primary" @click="focusDraft"
                  >继续编辑</el-button
                >
                <el-button v-hasPermi="['profile:rule:edit']" v-if="row.status !== 'DRAFT'" link type="primary" @click="copyAsDraft(row)"
                  >复制为草稿</el-button
                >
                <el-button v-hasPermi="['profile:rule:edit']" v-if="row.status === 'PUBLISHED'" link type="warning" @click="archiveRule(row)"
                  >归档</el-button
                >
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 维度编辑 -->
    <el-dialog v-model="dimDialogVisible" :title="dimForm.dimensionId ? '编辑维度' : '新建维度'" width="min(780px, 94%)" top="4vh" destroy-on-close>
      <el-form label-width="108px" class="dim-form">
        <el-collapse v-model="formSections" class="dim-sections">
          <el-collapse-item name="src">
            <template #title><span class="section-num">①</span> 数据源 —— 这个维度看什么、叫什么</template>
            <div class="form-grid">
              <el-form-item label="维度编码">
                <el-input v-model="dimForm.dimCode" :disabled="Boolean(dimForm.dimensionId)" placeholder="字母开头，如 activity" maxlength="64" />
              </el-form-item>
              <el-form-item label="维度名称">
                <el-input v-model="dimForm.dimName" placeholder="如 活跃度" maxlength="100" />
              </el-form-item>
              <el-form-item label="计算方式">
                <el-select v-model="dimForm.calcMode" style="width: 100%">
                  <el-option label="EVENT_AGG · 事件聚合" value="EVENT_AGG" />
                  <el-option label="STRATEGY · 业务 Bean" value="STRATEGY" />
                </el-select>
              </el-form-item>
              <el-form-item label="打分上限">
                <el-input-number v-model="dimForm.scoreMax" :min="1" :max="1000" controls-position="right" style="width: 100%" />
              </el-form-item>
              <el-form-item label="排序">
                <el-input-number v-model="dimForm.sortOrder" :min="0" :max="9999" controls-position="right" style="width: 100%" />
              </el-form-item>
              <el-form-item label="状态">
                <el-switch v-model="dimForm.enabled" active-value="1" inactive-value="0" active-text="启用" inactive-text="停用" />
              </el-form-item>
            </div>
          </el-collapse-item>

          <el-collapse-item name="calc">
            <template #title><span class="section-num">②</span> 计算 —— 指标口径与打分映射</template>
            <template v-if="dimForm.calcMode === 'EVENT_AGG'">
              <div class="form-grid">
                <el-form-item label="算子">
                  <el-select v-model="dimForm.operator" style="width: 100%" @change="onOperatorChange">
                    <el-option label="COUNT · 次数" value="COUNT" />
                    <el-option label="COUNT_DISTINCT · 去重数" value="COUNT_DISTINCT" />
                    <el-option label="SUM · 求和" value="SUM" />
                    <el-option label="AVG · 平均" value="AVG" />
                    <el-option label="RATE · 比率" value="RATE" />
                    <el-option label="LAST_VALUE · 最近值" value="LAST_VALUE" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="dimForm.operator !== 'RATE'" label="目标事件">
                  <el-input v-model="dimForm.targetEvent" placeholder="如 data.submitted" />
                </el-form-item>
                <el-form-item v-if="dimForm.operator === 'RATE'" label="分子事件">
                  <el-input v-model="dimForm.numeratorEvent" placeholder="如 data.audit_passed" />
                </el-form-item>
                <el-form-item v-if="dimForm.operator === 'RATE'" label="分母事件">
                  <el-input v-model="dimForm.denominatorEvent" placeholder="如 data.submitted" />
                </el-form-item>
                <el-form-item v-if="['SUM', 'AVG', 'LAST_VALUE'].includes(dimForm.operator)" label="属性键">
                  <el-input v-model="dimForm.prop" placeholder="props 中的数值键，如 duration" />
                </el-form-item>
                <el-form-item v-if="dimForm.operator === 'COUNT_DISTINCT'" label="去重键">
                  <el-input v-model="dimForm.dedupKey" placeholder="props 中的去重键，如 taskId" />
                </el-form-item>
                <el-form-item v-if="dimForm.operator === 'COUNT'" label="过滤键">
                  <el-input v-model="dimForm.filterKey" placeholder="可选，如 result" />
                </el-form-item>
                <el-form-item v-if="dimForm.operator === 'COUNT'" label="过滤值">
                  <el-input v-model="dimForm.filterValue" placeholder="可选，如 pass" />
                </el-form-item>
                <el-form-item label="时间窗">
                  <el-select v-model="dimForm.windowType" style="width: 100%">
                    <el-option label="全量" value="all" />
                    <el-option label="滚动窗口" value="rolling" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="dimForm.windowType === 'rolling'" label="最近天数">
                  <el-input-number v-model="dimForm.windowDays" :min="1" :max="3650" controls-position="right" style="width: 100%" />
                </el-form-item>
                <el-form-item label="映射方式">
                  <el-select v-model="dimForm.scoreType" style="width: 100%">
                    <el-option label="linear · 线性" value="linear" />
                    <el-option label="bucket · 分档" value="bucket" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="dimForm.scoreType === 'linear'" label="指标下界">
                  <el-input-number v-model="dimForm.lo" :controls="false" style="width: 100%" />
                </el-form-item>
                <el-form-item v-if="dimForm.scoreType === 'linear'" label="指标上界">
                  <el-input-number v-model="dimForm.hi" :controls="false" style="width: 100%" />
                </el-form-item>
                <el-form-item v-if="dimForm.scoreType === 'linear'" label="方向">
                  <el-select v-model="dimForm.direction" style="width: 100%">
                    <el-option label="越高越好" value="higher" />
                    <el-option label="越低越好" value="lower" />
                  </el-select>
                </el-form-item>
                <el-form-item label="缺失策略">
                  <el-select v-model="dimForm.missing" style="width: 100%">
                    <el-option label="neutral · 中性分" value="neutral" />
                    <el-option label="zero · 0 分" value="zero" />
                    <el-option label="skip · 跳过本维度" value="skip" />
                  </el-select>
                </el-form-item>
              </div>

              <div v-if="dimForm.scoreType === 'bucket'" class="bucket-editor">
                <div class="bucket-head">
                  <span>分档（按顺序匹配，lte 留空 = 兜底档）</span>
                  <el-button v-hasPermi="['profile:dimension:edit']" link type="primary" @click="addBucket">+ 添加档位</el-button>
                </div>
                <div v-for="(bucket, index) in dimForm.buckets" :key="index" class="bucket-row">
                  <span class="bucket-index">#{{ index + 1 }}</span>
                  <el-input v-model="bucket.lte" placeholder="lte，留空=兜底" style="width: 170px" />
                  <span class="muted">→</span>
                  <el-input-number v-model="bucket.score" :min="0" :max="1000" controls-position="right" style="width: 130px" />
                  <el-button
                    v-hasPermi="['profile:dimension:edit']"
                    link
                    type="danger"
                    :disabled="dimForm.buckets.length <= 1"
                    @click="removeBucket(index)"
                  >
                    删除
                  </el-button>
                </div>
              </div>
            </template>

            <template v-else>
              <el-form-item label="Bean">
                <el-select
                  v-model="dimForm.strategyBean"
                  placeholder="选择已注册的 DimensionStrategy"
                  style="width: 100%"
                  @change="onStrategyBeanChange"
                >
                  <el-option
                    v-for="bean in capabilities.strategyBeans"
                    :key="bean.bean"
                    :label="`${bean.source} / ${bean.dimCode} · ${bean.bean}`"
                    :value="bean.bean"
                  />
                </el-select>
              </el-form-item>
              <el-alert
                type="warning"
                :closable="false"
                show-icon
                title="STRATEGY 维度由后端 Bean 直接产出分数，管理面只能改名称/上限/启停，不能试算。"
              />
            </template>
          </el-collapse-item>

          <el-collapse-item name="gov">
            <template #title><span class="section-num">③</span> 治理 —— 备注与高级配置</template>
            <el-form-item label="备注">
              <el-input v-model="dimForm.remark" type="textarea" :rows="2" maxlength="500" show-word-limit />
            </el-form-item>
            <el-form-item label="高级 JSON">
              <div class="advanced-block">
                <el-switch v-model="dimForm.advanced" active-text="直接编辑 calc_config" />
                <el-input
                  v-if="dimForm.advanced"
                  v-model="dimForm.advancedJson"
                  type="textarea"
                  :rows="9"
                  class="mono"
                  placeholder="calc_config JSON"
                />
                <p v-if="dimForm.advanced" class="muted small">开启后保存以这段 JSON 为准；关闭则按上面的结构化表单生成。</p>
              </div>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dimDialogVisible = false">取消</el-button>
          <el-button @click="openPreview()">试算</el-button>
          <el-button v-hasPermi="['profile:dimension:edit']" type="primary" :loading="dimSaving" @click="saveDimension">保存</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 试算 -->
    <el-dialog v-model="previewVisible" title="维度试算（不落快照）" width="min(620px, 94%)" destroy-on-close>
      <el-form label-width="92px">
        <el-form-item label="主体类型">
          <el-select v-model="previewForm.subjectType" style="width: 100%">
            <el-option label="用户 user" value="user" />
            <el-option label="订单 order" value="order" />
            <el-option label="访客 visitor" value="visitor" />
          </el-select>
        </el-form-item>
        <el-form-item label="主体 ID">
          <el-input v-model="previewForm.subjectId" placeholder="输入一个已有事件的主体 ID" @keyup.enter="runPreview" />
        </el-form-item>
        <el-form-item label="维度">
          <span class="mono">{{ dimForm.dimCode }} · {{ dimForm.dimName || '未命名' }}</span>
        </el-form-item>
      </el-form>
      <div v-if="previewResult" class="preview-result">
        <el-alert v-if="previewResult.skipped" type="warning" :closable="false" show-icon title="该主体没有命中证据，按缺失策略跳过本维度" />
        <template v-else>
          <div class="preview-grid">
            <div class="preview-stat">
              <span class="muted">维度分</span>
              <strong>{{ formatNumber(Number(previewResult.score), 2) }} / {{ previewResult.scoreMax }}</strong>
            </div>
            <div class="preview-stat">
              <span class="muted">归一化</span>
              <strong>{{ formatNumber(Number(previewResult.normalizedScore), 2) }}</strong>
            </div>
            <div class="preview-stat">
              <span class="muted">原始指标</span>
              <strong>{{ formatNumber(Number(previewResult.metric), 4) }}</strong>
            </div>
            <div class="preview-stat">
              <span class="muted">样本量</span>
              <strong>{{ previewResult.sampleCount ?? 0 }}</strong>
            </div>
          </div>
          <div v-if="previewResult.tags && Object.keys(previewResult.tags).length" class="preview-tags">
            <el-tag v-for="(value, key) in previewResult.tags" :key="key" size="small" type="info" effect="plain">
              {{ key }}: {{ formatTag(value) }}
            </el-tag>
          </div>
          <pre class="json-block">{{ prettyJson(previewResult) }}</pre>
        </template>
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
        <el-button type="primary" :loading="previewLoading" @click="runPreview">开始试算</el-button>
      </template>
    </el-dialog>

    <!-- 发布 -->
    <el-dialog v-model="publishVisible" title="发布权重版本" width="min(520px, 94%)" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="版本号">
          <el-input v-model="publishForm.version" maxlength="32" placeholder="如 v2026.10.19" />
        </el-form-item>
        <el-form-item label="版本说明">
          <el-input v-model="publishForm.note" type="textarea" :rows="3" maxlength="500" placeholder="本次权重调整的原因" />
        </el-form-item>
      </el-form>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="发布前会校验权重：必须覆盖全部启用维度、每项 0~1、合计为 1。发布后旧版本自动归档。"
      />
      <template #footer>
        <el-button @click="publishVisible = false">取消</el-button>
        <el-button v-hasPermi="['profile:rule:edit']" type="success" :loading="ruleActionLoading" @click="publishDraft">确认发布</el-button>
      </template>
    </el-dialog>

    <!-- 规则校验 / 对比 / 影响 -->
    <el-dialog v-model="resultVisible" :title="resultTitle" width="min(720px, 94%)" destroy-on-close>
      <template v-if="resultMode === 'validate'">
        <el-alert
          :type="validation?.valid ? 'success' : 'error'"
          :closable="false"
          show-icon
          :title="validation?.valid ? '校验通过，可以发布' : '校验未通过'"
        />
        <div class="result-block">
          <div v-for="(err, index) in validation?.errors || []" :key="`e${index}`" class="result-line error">
            {{ err }}
          </div>
          <div v-for="(warn, index) in validation?.warnings || []" :key="`w${index}`" class="result-line warn">
            {{ warn }}
          </div>
        </div>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="权重合计">{{ formatNumber(validation?.weightSum, 4) }}</el-descriptions-item>
          <el-descriptions-item label="启用维度">{{ validation?.dimensionCodes?.length ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="revision">{{ validation?.revision }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ validation?.status }}</el-descriptions-item>
        </el-descriptions>
      </template>

      <template v-else-if="resultMode === 'diff'">
        <div class="result-block">
          <el-tag v-for="code in diffResult?.added || []" :key="`a-${code}`" type="success" size="small" effect="plain"> 新增 {{ code }} </el-tag>
          <el-tag v-for="code in diffResult?.removed || []" :key="`r-${code}`" type="danger" size="small" effect="plain"> 移除 {{ code }} </el-tag>
        </div>
        <el-table :data="diffRows" class="ui-table">
          <el-table-column label="维度" prop="code" min-width="140">
            <template #default="{ row }"
              >{{ dimensionName(row.code) }} <span class="mono muted">{{ row.code }}</span></template
            >
          </el-table-column>
          <el-table-column label="当前" width="120">
            <template #default="{ row }">{{ row.before == null ? '—' : formatNumber(Number(row.before), 4) }}</template>
          </el-table-column>
          <el-table-column label="草稿" width="120">
            <template #default="{ row }">{{ row.after == null ? '—' : formatNumber(Number(row.after), 4) }}</template>
          </el-table-column>
          <el-table-column label="变化" width="120">
            <template #default="{ row }">
              <span :class="Number(row.delta) > 0 ? 'delta-up' : Number(row.delta) < 0 ? 'delta-down' : 'muted'">
                {{ row.delta == null ? '—' : (Number(row.delta) > 0 ? '+' : '') + formatNumber(Number(row.delta), 4) }}
              </span>
            </template>
          </el-table-column>
        </el-table>
        <p class="muted small">对比基线：{{ diffResult?.baseVersion || '无（首次发布）' }}</p>
      </template>

      <template v-else-if="resultMode === 'impact'">
        <div class="preview-grid">
          <div class="preview-stat">
            <span class="muted">主体数</span>
            <strong>{{ impactResult?.subjectCount ?? 0 }}</strong>
          </div>
          <div class="preview-stat">
            <span class="muted">已算分主体</span>
            <strong>{{ impactResult?.scoredSubjectCount ?? 0 }}</strong>
          </div>
          <div class="preview-stat">
            <span class="muted">预计重算</span>
            <strong>{{ impactResult?.estimatedRecalcSubjects ?? 0 }}</strong>
          </div>
          <div class="preview-stat">
            <span class="muted">是否需重算</span>
            <strong>{{ impactResult?.requiresRecalc ? '是' : '否' }}</strong>
          </div>
        </div>
        <div class="result-block">
          <span class="muted">变化维度：</span>
          <el-tag v-for="code in impactResult?.changedDimensions || []" :key="code" size="small" type="warning" effect="plain">
            {{ dimensionName(code) }}
          </el-tag>
          <span v-if="!impactResult?.changedDimensions?.length" class="muted">没有权重变化</span>
        </div>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="发布本身不重算历史快照；发布后可在下方触发重算，新权重将生效于用户画像列表与详情页（RFM 模型页分数需在模型页另行运行）。"
        />
        <!-- 批1 重算闭环: 发布结果弹窗内直接触发经典链重算, 终点=画像列表/详情页 -->
        <div v-if="recalcAvailable && canRecalc" class="recalc-panel">
          <el-button v-if="!recalcBatch" type="primary" :loading="recalcLoading" @click="runRecalc">触发重算</el-button>
          <template v-else-if="recalcBatch.status === 'DONE'">
            <el-alert
              type="success"
              :closable="false"
              show-icon
              :title="`重算完成：${recalcBatch.doneSubjects ?? 0} / ${recalcBatch.totalSubjects ?? 0} 个主体已按新权重出新快照`"
            />
          </template>
          <template v-else-if="recalcBatch.status === 'FAILED'">
            <el-alert
              type="error"
              :closable="false"
              show-icon
              :title="`重算失败：已完成 ${recalcBatch.doneSubjects ?? 0} / ${recalcBatch.totalSubjects ?? 0}`"
              :description="recalcBatch.errorMsg"
            />
            <el-button type="warning" plain :loading="recalcLoading" @click="resumeRecalc">断点续跑</el-button>
          </template>
          <el-alert
            v-else
            type="info"
            :closable="false"
            show-icon
            :title="`重算进行中：${recalcBatch.doneSubjects ?? 0} / ${recalcBatch.totalSubjects ?? 0}`"
          />
          <p v-if="recalcBatch" class="muted small">完成后到「用户画像」列表与详情页查看新分数与画像依据（RFM 模型页不受影响）。</p>
        </div>
        <el-alert
          v-else-if="recalcAvailable"
          type="warning"
          :closable="false"
          show-icon
          title="当前账号没有重算权限，如需按新权重重算历史主体，请联系管理员操作。"
        />
      </template>

      <template #footer>
        <el-button @click="resultVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="DcsProfileStudio" lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { checkPermi } from '@/utils/permission';
import {
  archiveRule as archiveRuleApi,
  createDimension,
  createRuleDraft,
  deleteRuleDraft,
  diffRule,
  disableDimension,
  getCurrentRule,
  getDimensionCapabilities,
  getDimensionCoverage,
  getRuleHistory,
  getRuleTemplates,
  impactRule,
  listDimensions,
  previewDimension,
  publishRule,
  resumeRecalcBatch,
  triggerRecalc,
  updateDimension,
  updateRuleDraft,
  validateRule,
  type ProfileDimension,
  type ProfileDimensionCapabilities,
  type ProfileDimensionCoverage,
  type ProfileDimensionPreview,
  type ProfileRecalcBatch,
  type ProfileRule,
  type ProfileRuleDiff,
  type ProfileRuleImpact,
  type ProfileRuleTemplate,
  type ProfileRuleValidation
} from '@/api/dcs/core/profile';
import { useSourceOptions } from '@/composables/useProfileSources';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';
const { list: sourceOpts, ensureSources } = useSourceOptions('subject');
ensureSources();

const router = useRouter();

const source = ref('dcs');
const activeTab = ref('dimension');
const loading = ref(false);
const dimensionLoading = ref(false);
const coverageLoading = ref(false);
const ruleLoading = ref(false);
const ruleActionLoading = ref(false);
const dimSaving = ref(false);
const previewLoading = ref(false);

const dimensions = ref<ProfileDimension[]>([]);
const coverage = ref<ProfileDimensionCoverage[]>([]);
const capabilities = ref<ProfileDimensionCapabilities>({
  calcModes: ['EVENT_AGG', 'STRATEGY'],
  operators: ['COUNT', 'COUNT_DISTINCT', 'SUM', 'AVG', 'RATE', 'LAST_VALUE'],
  scoreTypes: ['linear', 'bucket'],
  directions: ['higher', 'lower'],
  missingPolicies: ['neutral', 'zero', 'skip'],
  windowTypes: ['all', 'rolling'],
  propKeyPattern: '',
  scoreMaxRange: { min: 1, max: 1000 },
  strategyBeans: []
});

const currentRule = ref<ProfileRule | null>(null);
const ruleHistory = ref<ProfileRule[]>([]);
const ruleTemplates = ref<ProfileRuleTemplate[]>([]);
const draft = computed(() => ruleHistory.value.find((rule) => rule.status === 'DRAFT') || null);
const copyableRules = computed(() => ruleHistory.value.filter((rule) => rule.status !== 'DRAFT'));

const enabledDimensions = computed(() => dimensions.value.filter((dim) => dim.enabled === '1'));
const dimensionRows = computed(() => dimensions.value.map((dim) => ({ ...dim, coverage: coverageOf(dim.dimCode) })));

const coverageMap = computed(() => {
  const map: Record<string, ProfileDimensionCoverage> = {};
  coverage.value.forEach((item) => {
    map[item.dimCode] = item;
  });
  return map;
});
const coverageOf = (code: string) => coverageMap.value[code];
const coveragePercent = (code: string) => {
  const n = Number(coverageOf(code)?.coverageRate ?? 0);
  return Number.isFinite(n) ? Math.max(0, Math.min(100, n)) : 0;
};
const coverageColor = (value: number) =>
  value >= 80 ? 'var(--el-color-success)' : value >= 50 ? 'var(--el-color-warning)' : 'var(--el-color-danger)';

const formatNumber = (value: number | string | null | undefined, precision = 2) => {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  return Number.isFinite(n) ? n.toFixed(precision) : String(value);
};
const formatRate = (value: number | string | null | undefined) => {
  if (value === null || value === undefined || value === '') return '0%';
  const n = Number(value);
  return Number.isFinite(n) ? `${n.toFixed(1)}%` : '0%';
};
const formatTag = (value: unknown) => {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
};
const prettyJson = (value: unknown) => {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
};
const dimensionName = (code: string) => dimensions.value.find((dim) => dim.dimCode === code)?.dimName || code;

// ---------------------------------------------------------------- 维度

const defaultBuckets = () => [
  { lte: '1', score: 20 },
  { lte: '3', score: 50 },
  { lte: '5', score: 80 },
  { lte: '', score: 100 }
];

const dimForm = reactive({
  dimensionId: '' as string | number | undefined,
  dimCode: '',
  dimName: '',
  calcMode: 'EVENT_AGG',
  scoreMax: 100,
  enabled: '1',
  sortOrder: 0,
  remark: '',
  operator: 'COUNT',
  targetEvent: '',
  prop: '',
  dedupKey: '',
  numeratorEvent: '',
  denominatorEvent: '',
  filterKey: '',
  filterValue: '',
  windowType: 'all',
  windowDays: 30,
  scoreType: 'linear',
  lo: 0,
  hi: 10,
  direction: 'higher',
  buckets: defaultBuckets(),
  missing: 'neutral',
  strategyBean: '',
  advanced: false,
  advancedJson: ''
});

const dimDialogVisible = ref(false);

/** 维度编辑三区段（批9）：数据源 / 计算 / 治理，默认全开 */
const formSections = ref(['src', 'calc', 'gov']);
const previewVisible = ref(false);
const previewForm = reactive({ subjectType: 'user', subjectId: '' });
const previewResult = ref<ProfileDimensionPreview | null>(null);

const parseJson = (raw: unknown): Record<string, any> => {
  if (!raw) return {};
  if (typeof raw === 'object') return raw as Record<string, any>;
  try {
    const parsed = JSON.parse(String(raw));
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
};

const fillFormFromDimension = (dim: ProfileDimension) => {
  const cfg = parseJson(dim.calcConfig);
  const metric = cfg.metric || {};
  const window = cfg.window || {};
  const score = cfg.score || {};
  const filterEntries = Object.entries(metric.target?.propFilter || {});
  dimForm.dimensionId = dim.dimensionId;
  dimForm.dimCode = dim.dimCode;
  dimForm.dimName = dim.dimName;
  dimForm.calcMode = dim.calcMode;
  dimForm.scoreMax = Number(dim.scoreMax);
  dimForm.enabled = dim.enabled;
  dimForm.sortOrder = dim.sortOrder ?? 0;
  dimForm.remark = dim.remark || '';
  dimForm.operator = metric.operator || 'COUNT';
  dimForm.targetEvent = metric.target?.eventType || '';
  dimForm.prop = metric.prop || '';
  dimForm.dedupKey = metric.dedupKey || '';
  dimForm.numeratorEvent = metric.numerator?.eventType || '';
  dimForm.denominatorEvent = metric.denominator?.eventType || '';
  dimForm.filterKey = (filterEntries[0]?.[0] as string) || '';
  dimForm.filterValue = (filterEntries[0]?.[1] as string) ?? '';
  dimForm.windowType = window.type === 'rolling' ? 'rolling' : 'all';
  dimForm.windowDays = Number(window.days) || 30;
  dimForm.scoreType = score.type === 'bucket' ? 'bucket' : 'linear';
  dimForm.lo = Number(score.lo ?? 0);
  dimForm.hi = Number(score.hi ?? 1);
  dimForm.direction = score.direction === 'lower' ? 'lower' : 'higher';
  dimForm.buckets =
    Array.isArray(score.buckets) && score.buckets.length
      ? score.buckets.map((bucket: any) => ({
          lte: bucket?.lte === null || bucket?.lte === undefined ? '' : String(bucket.lte),
          score: Number(bucket?.score ?? 0)
        }))
      : defaultBuckets();
  dimForm.missing = cfg.missing || 'neutral';
  dimForm.strategyBean = cfg.strategyBean || '';
  dimForm.advanced = false;
  dimForm.advancedJson = JSON.stringify(cfg, null, 2);
};

const resetDimensionForm = () => {
  Object.assign(dimForm, {
    dimensionId: undefined,
    dimCode: '',
    dimName: '',
    calcMode: 'EVENT_AGG',
    scoreMax: 100,
    enabled: '1',
    sortOrder: dimensions.value.length,
    remark: '',
    operator: 'COUNT',
    targetEvent: '',
    prop: '',
    dedupKey: '',
    numeratorEvent: '',
    denominatorEvent: '',
    filterKey: '',
    filterValue: '',
    windowType: 'all',
    windowDays: 30,
    scoreType: 'linear',
    lo: 0,
    hi: 10,
    direction: 'higher',
    buckets: defaultBuckets(),
    missing: 'neutral',
    strategyBean: '',
    advanced: false,
    advancedJson: ''
  });
};

const openCreateDimension = () => {
  resetDimensionForm();
  dimDialogVisible.value = true;
};
const openEditDimension = (dim: ProfileDimension) => {
  fillFormFromDimension(dim);
  dimDialogVisible.value = true;
};

const onOperatorChange = () => {
  if (dimForm.operator === 'RATE' && !dimForm.numeratorEvent) {
    dimForm.numeratorEvent = dimForm.targetEvent;
  }
  if (dimForm.operator === 'COUNT') {
    dimForm.scoreType = 'linear';
    dimForm.lo = 0;
    dimForm.hi = 10;
  }
};
const onStrategyBeanChange = (beanName: string) => {
  const bean = capabilities.value.strategyBeans.find((item) => item.bean === beanName);
  if (bean && !dimForm.dimCode) {
    dimForm.dimCode = bean.dimCode;
  }
};
const addBucket = () => dimForm.buckets.push({ lte: '', score: 100 });
const removeBucket = (index: number) => {
  if (dimForm.buckets.length > 1) dimForm.buckets.splice(index, 1);
};

const buildCalcConfig = (): Record<string, any> => {
  if (dimForm.advanced) {
    const parsed = parseJson(dimForm.advancedJson);
    if (!Object.keys(parsed).length) {
      throw new Error('高级 JSON 不是合法对象');
    }
    return parsed;
  }
  if (dimForm.calcMode === 'STRATEGY') {
    return dimForm.strategyBean ? { strategyBean: dimForm.strategyBean } : {};
  }
  const target = { eventType: dimForm.targetEvent.trim() };
  if (dimForm.operator === 'COUNT' && dimForm.filterKey.trim()) {
    (target as any).propFilter = { [dimForm.filterKey.trim()]: dimForm.filterValue };
  }
  const metric: Record<string, any> = { operator: dimForm.operator };
  if (dimForm.operator === 'RATE') {
    metric.numerator = { eventType: dimForm.numeratorEvent.trim() };
    metric.denominator = { eventType: dimForm.denominatorEvent.trim() };
  } else {
    metric.target = target;
  }
  if (dimForm.operator === 'COUNT_DISTINCT') metric.dedupKey = dimForm.dedupKey.trim();
  if (['SUM', 'AVG', 'LAST_VALUE'].includes(dimForm.operator)) metric.prop = dimForm.prop.trim();

  const score: Record<string, any> = { type: dimForm.scoreType };
  if (dimForm.scoreType === 'linear') {
    score.lo = Number(dimForm.lo);
    score.hi = Number(dimForm.hi);
    score.direction = dimForm.direction;
  } else {
    score.buckets = dimForm.buckets.map((bucket) => {
      const item: Record<string, any> = { score: Number(bucket.score) };
      if (String(bucket.lte).trim() !== '') item.lte = Number(bucket.lte);
      return item;
    });
  }
  const window: Record<string, any> = { type: dimForm.windowType };
  if (dimForm.windowType === 'rolling') window.days = Number(dimForm.windowDays);
  return { metric, window, score, missing: dimForm.missing };
};

const saveDimension = async () => {
  if (!dimForm.dimCode.trim() || !dimForm.dimName.trim()) {
    ElMessage.warning('维度编码和名称不能为空');
    return;
  }
  let calcConfig: Record<string, any>;
  try {
    calcConfig = buildCalcConfig();
  } catch (e: any) {
    ElMessage.error(e?.message || '维度配置不合法');
    return;
  }
  dimSaving.value = true;
  try {
    const payload = {
      dimensionId: dimForm.dimensionId || undefined,
      source: source.value,
      dimCode: dimForm.dimCode.trim(),
      dimName: dimForm.dimName.trim(),
      calcMode: dimForm.calcMode,
      calcConfig,
      scoreMax: dimForm.scoreMax,
      enabled: dimForm.enabled,
      sortOrder: dimForm.sortOrder,
      remark: dimForm.remark
    };
    if (dimForm.dimensionId) {
      await updateDimension(payload);
      ElMessage.success('维度已更新，重算后进入新快照');
    } else {
      await createDimension(payload);
      ElMessage.success('维度已创建');
    }
    dimDialogVisible.value = false;
    await loadDimensions();
    await loadRules();
  } finally {
    dimSaving.value = false;
  }
};

const disableDim = async (dim: ProfileDimension) => {
  await ElMessageBox.confirm(`停用「${dim.dimName}」后，新快照不再包含该维度；历史快照保留。确定停用？`, '停用维度', { type: 'warning' });
  await disableDimension(String(dim.dimensionId));
  ElMessage.success('已停用');
  await loadDimensions();
  await loadRules();
};

const openPreview = (dim?: ProfileDimension) => {
  if (dim) fillFormFromDimension(dim);
  if (dimForm.calcMode === 'STRATEGY') {
    ElMessage.info('STRATEGY 维度由业务 Bean 计算，管理面不支持试算');
    return;
  }
  previewResult.value = null;
  previewForm.subjectId = '';
  previewVisible.value = true;
};

const runPreview = async () => {
  if (!previewForm.subjectId.trim()) {
    ElMessage.warning('请先输入主体 ID');
    return;
  }
  let calcConfig: Record<string, any>;
  try {
    calcConfig = buildCalcConfig();
  } catch (e: any) {
    ElMessage.error(e?.message || '维度配置不合法');
    return;
  }
  previewLoading.value = true;
  try {
    previewResult.value = await previewDimension({
      source: source.value,
      subjectType: previewForm.subjectType,
      subjectId: previewForm.subjectId.trim(),
      dimCode: dimForm.dimCode,
      dimName: dimForm.dimName,
      calcMode: dimForm.calcMode,
      calcConfig,
      scoreMax: dimForm.scoreMax
    });
  } finally {
    previewLoading.value = false;
  }
};

// ---------------------------------------------------------------- 规则

const createForm = reactive({ ruleName: '', sourceRuleId: '', templateCode: '' });
const draftWeights = reactive<Record<string, number>>({});
const draftMeta = reactive({ ruleName: '', remark: '' });
const applyTemplateCode = ref('');
const draftSum = computed(() => Object.values(draftWeights).reduce((sum, value) => sum + (Number(value) || 0), 0));
const sumOk = computed(() => Math.abs(draftSum.value - 1) <= 0.001);

const parseWeights = (raw: unknown): Record<string, number> => {
  if (!raw) return {};
  if (typeof raw === 'object') return raw as Record<string, number>;
  try {
    const parsed = JSON.parse(String(raw));
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
};
const weightEntries = (rule: ProfileRule | null) =>
  Object.entries(parseWeights(rule?.weights))
    .map(([code, value]) => ({ code, value: Number(value) }))
    .sort((a, b) => b.value - a.value);
const weightSumOf = (rule: ProfileRule | null) => Object.values(parseWeights(rule?.weights)).reduce((sum, value) => sum + (Number(value) || 0), 0);
const weightSummary = (rule: ProfileRule) => {
  const entries = weightEntries(rule);
  if (!entries.length) return '—';
  return entries.map((item) => `${item.code} ${formatNumber(item.value, 2)}`).join(' · ');
};
const ruleStatusType = (status: string) => (status === 'PUBLISHED' ? 'success' : status === 'DRAFT' ? 'warning' : 'info');

const syncDraftMeta = () => {
  if (!draft.value) {
    draftMeta.ruleName = '';
    draftMeta.remark = '';
    return;
  }
  draftMeta.ruleName = draft.value.ruleName || '';
  draftMeta.remark = draft.value.remark || '';
  // 维度还没加载完时先不动编辑区, 避免把已有权重刷成 0
  if (!enabledDimensions.value.length) return;
  const parsed = parseWeights(draft.value.weights);
  Object.keys(draftWeights).forEach((key) => delete draftWeights[key]);
  enabledDimensions.value.forEach((dim) => {
    draftWeights[dim.dimCode] = Number(parsed[dim.dimCode] ?? 0);
  });
};

const loadDimensions = async () => {
  dimensionLoading.value = true;
  coverageLoading.value = true;
  try {
    const [dims, cover] = await Promise.all([listDimensions(source.value), getDimensionCoverage(source.value)]);
    dimensions.value = dims;
    coverage.value = cover;
  } finally {
    dimensionLoading.value = false;
    coverageLoading.value = false;
  }
};

const loadRules = async () => {
  ruleLoading.value = true;
  try {
    const [cur, history, templates] = await Promise.all([getCurrentRule(source.value), getRuleHistory(source.value), getRuleTemplates(source.value)]);
    currentRule.value = cur;
    ruleHistory.value = history;
    ruleTemplates.value = templates;
    syncDraftMeta();
  } finally {
    ruleLoading.value = false;
  }
};

const loadAll = async () => {
  loading.value = true;
  try {
    await loadDimensions();
    await loadRules();
  } finally {
    loading.value = false;
  }
};

const onSourceChange = async () => {
  await loadAll();
};

const createDraft = async () => {
  ruleActionLoading.value = true;
  try {
    const created = await createRuleDraft({
      source: source.value,
      ruleName: createForm.ruleName || undefined,
      sourceRuleId: createForm.sourceRuleId || undefined
    });
    await loadRules();
    if (createForm.templateCode) {
      const template = ruleTemplates.value.find((item) => item.code === createForm.templateCode);
      if (template) {
        Object.keys(draftWeights).forEach((key) => delete draftWeights[key]);
        enabledDimensions.value.forEach((dim) => {
          draftWeights[dim.dimCode] = Number(template.weights[dim.dimCode] ?? 0);
        });
        await saveDraft(true);
      }
    }
    ElMessage.success(created.status === 'DRAFT' ? '草稿已就绪' : '已创建草稿');
    createForm.ruleName = '';
    createForm.sourceRuleId = '';
    createForm.templateCode = '';
  } finally {
    ruleActionLoading.value = false;
  }
};

const collectWeights = () => {
  const weights: Record<string, number> = {};
  enabledDimensions.value.forEach((dim) => {
    weights[dim.dimCode] = Math.max(0, Math.min(1, Number(draftWeights[dim.dimCode]) || 0));
  });
  return weights;
};

const saveDraft = async (silent = false) => {
  if (!draft.value) return;
  if (!draftMeta.ruleName.trim()) {
    ElMessage.warning('规则名称不能为空');
    return;
  }
  ruleActionLoading.value = true;
  try {
    const updated = await updateRuleDraft({
      ruleId: String(draft.value.ruleId),
      revision: draft.value.revision,
      ruleName: draftMeta.ruleName.trim(),
      remark: draftMeta.remark,
      weights: collectWeights()
    });
    await loadRules();
    if (!silent) ElMessage.success('草稿已保存');
  } finally {
    ruleActionLoading.value = false;
  }
};

const balanceWeights = () => {
  const dims = enabledDimensions.value;
  if (!dims.length) return;
  const each = Number((1 / dims.length).toFixed(4));
  let used = 0;
  dims.forEach((dim, index) => {
    const value = index === dims.length - 1 ? Number((1 - used).toFixed(4)) : each;
    draftWeights[dim.dimCode] = value;
    used += value;
  });
};

const normalizeWeights = () => {
  const total = draftSum.value;
  if (total <= 0) {
    ElMessage.warning('权重合计为 0，无法归一化');
    return;
  }
  const dims = enabledDimensions.value;
  let used = 0;
  dims.forEach((dim, index) => {
    const value = index === dims.length - 1 ? Number((1 - used).toFixed(4)) : Number(((Number(draftWeights[dim.dimCode]) || 0) / total).toFixed(4));
    draftWeights[dim.dimCode] = value;
    used += value;
  });
};

const applyTemplate = () => {
  if (!applyTemplateCode.value) {
    ElMessage.warning('先选择一个模板');
    return;
  }
  const template = ruleTemplates.value.find((item) => item.code === applyTemplateCode.value);
  if (!template) return;
  enabledDimensions.value.forEach((dim) => {
    draftWeights[dim.dimCode] = Number(template.weights[dim.dimCode] ?? 0);
  });
  ElMessage.info(`已套用「${template.name}」，记得保存草稿`);
};

const validateDraft = async () => {
  if (!draft.value) return;
  await saveDraft(true);
  validation.value = await validateRule(String(draft.value.ruleId));
  resultMode.value = 'validate';
  resultVisible.value = true;
};

const showDiff = async () => {
  if (!draft.value) return;
  await saveDraft(true);
  diffResult.value = await diffRule(String(draft.value.ruleId));
  resultMode.value = 'diff';
  resultVisible.value = true;
};

const showImpact = async () => {
  if (!draft.value) return;
  await saveDraft(true);
  impactResult.value = await impactRule(String(draft.value.ruleId));
  resultMode.value = 'impact';
  recalcAvailable.value = false;
  resultVisible.value = true;
};

const openPublish = async () => {
  if (!draft.value) return;
  await saveDraft(true);
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  publishForm.version = currentRule.value?.ruleVersion
    ? `${currentRule.value.ruleVersion}-r${(draft.value.revision ?? 0) + 1}`
    : `v${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}`;
  publishForm.note = draftMeta.remark;
  publishVisible.value = true;
};

const publishDraft = async () => {
  if (!draft.value) return;
  if (!publishForm.version.trim()) {
    ElMessage.warning('版本号不能为空');
    return;
  }
  ruleActionLoading.value = true;
  try {
    // 发布前抓影响面(发布后草稿即失效, impact 无从对比), 发布成功后回显在结果弹窗
    const impact = await impactRule(String(draft.value.ruleId));
    await publishRule(String(draft.value.ruleId), {
      version: publishForm.version.trim(),
      note: publishForm.note || undefined,
      revision: draft.value.revision
    });
    publishVisible.value = false;
    ElMessage.success('已发布，可在弹窗内触发重算让新权重生效');
    await loadRules();
    impactResult.value = impact;
    resultMode.value = 'impact';
    recalcAvailable.value = true;
    recalcBatch.value = null;
    resultVisible.value = true;
  } finally {
    ruleActionLoading.value = false;
  }
};

// ---------------------------------------------------------------- 批1: 发布→重算闭环(经典链)

const canRecalc = checkPermi(['profile:recalc:run']);
const recalcAvailable = ref(false);
const recalcLoading = ref(false);
const recalcBatch = ref<ProfileRecalcBatch | null>(null);

const runRecalc = async () => {
  await ElMessageBox.confirm('按新权重重算该源全部历史主体？量级大时需稍候。', '触发重算', { type: 'warning' });
  recalcLoading.value = true;
  try {
    recalcBatch.value = await triggerRecalc(source.value);
  } finally {
    recalcLoading.value = false;
  }
};

const resumeRecalc = async () => {
  if (!recalcBatch.value) return;
  recalcLoading.value = true;
  try {
    recalcBatch.value = await resumeRecalcBatch(String(recalcBatch.value.batchId));
  } finally {
    recalcLoading.value = false;
  }
};

const deleteDraft = async () => {
  if (!draft.value) return;
  await ElMessageBox.confirm('删除这份草稿？未发布的权重将丢失。', '删除草稿', { type: 'warning' });
  await deleteRuleDraft(String(draft.value.ruleId));
  ElMessage.success('草稿已删除');
  await loadRules();
};

const copyAsDraft = async (rule: ProfileRule) => {
  if (draft.value) {
    ElMessage.warning('已有开放草稿，请先发布或删除');
    return;
  }
  await createRuleDraft({
    source: source.value,
    sourceRuleId: String(rule.ruleId),
    ruleName: `${rule.ruleName || rule.ruleVersion} 草稿`
  });
  ElMessage.success('已从该版本复制草稿');
  await loadRules();
};

const archiveRule = async (rule: ProfileRule) => {
  await ElMessageBox.confirm(`归档「${rule.ruleVersion}」后该版本不再生效。确定归档？`, '归档规则', { type: 'warning' });
  await archiveRuleRequest(rule);
};

const archiveRuleRequest = async (rule: ProfileRule) => {
  await archiveRuleApi(String(rule.ruleId));
  ElMessage.success('已归档');
  await loadRules();
};

const focusDraft = () => {
  activeTab.value = 'rule';
  nextTick(() => {
    document.querySelector('.draft-panel')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  });
};

// ---------------------------------------------------------------- 结果对话框

const validation = ref<ProfileRuleValidation | null>(null);
const diffResult = ref<ProfileRuleDiff | null>(null);
const impactResult = ref<ProfileRuleImpact | null>(null);
const resultVisible = ref(false);
const resultMode = ref<'validate' | 'diff' | 'impact'>('validate');
const resultTitle = computed(() =>
  resultMode.value === 'validate' ? '草稿校验' : resultMode.value === 'diff' ? '权重对比' : recalcAvailable.value ? '发布结果' : '影响评估'
);
const diffRows = computed(() => Object.entries(diffResult.value?.changes || {}).map(([code, change]) => ({ code, ...change })));
const publishVisible = ref(false);
const publishForm = reactive({ version: '', note: '' });

const goBack = () => router.push('/insight/profile');

onMounted(async () => {
  try {
    capabilities.value = await getDimensionCapabilities();
  } catch {
    // 能力清单失败不阻塞页面
  }
  await loadAll();
});
</script>

<style scoped>
.studio-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.studio-page :deep(.dcs-ops-header) {
  margin-bottom: 4px;
}
.studio-page :deep(.dcs-ops-header__actions) {
  flex-wrap: wrap;
}
@media (min-width: 993px) {
  .source-select {
    width: 150px;
  }
}
/* el-table 走 CSS 变量：表头无底带、行线走令牌、正文灰阶（不重写组件） */
.studio-page :deep(.el-table) {
  --el-table-border-color: var(--app-rule);
  --el-table-header-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--app-raised);
}
/* 状态药丸：统一浅底深字，形状/字号与 .ui-pill 同口径 */
.studio-page :deep(.el-tag) {
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
.studio-page :deep(.el-tag--success) {
  --el-tag-bg-color: var(--dcs-pill-pos-bg);
  --el-tag-text-color: var(--dcs-pill-pos-ink);
}
.studio-page :deep(.el-tag--warning) {
  --el-tag-bg-color: var(--dcs-pill-warn-bg);
  --el-tag-text-color: var(--dcs-pill-warn-ink);
}
.studio-page :deep(.el-tag--danger) {
  --el-tag-bg-color: var(--dcs-pill-neg-bg);
  --el-tag-text-color: var(--dcs-pill-neg-ink);
}
.studio-page :deep(.el-card__body),
.studio-page :deep(.el-dialog__body) {
  min-width: 0;
}
.hero-hints {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.main-card :deep(.el-card__body) {
  padding-top: 8px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.toolbar-left {
  display: flex;
  gap: 10px;
}
.toolbar-right {
  font-size: 12px;
}
.tip {
  margin-top: 14px;
}
.dim-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.35;
}
.dim-title {
  display: flex;
  align-items: center;
  gap: 6px;
}
.dim-name {
  font-weight: 600;
  line-height: 1.4;
}
.dim-code {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.35;
}
.coverage-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.coverage-text {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.mono {
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
}
.muted {
  color: var(--el-text-color-secondary);
}
.small {
  font-size: 12px;
}
.rule-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.25fr);
  gap: 18px;
}
.rule-col {
  min-width: 0;
}
.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 650;
  margin-bottom: 10px;
}
.rule-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 14px;
  background: var(--el-fill-color-blank);
}
.rule-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.rule-name {
  font-weight: 650;
}
.rule-remark {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-size: 12px;
}
.weight-list {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.weight-read {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr) 58px;
  align-items: center;
  gap: 8px;
}
.weight-code {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.weight-value {
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}
.sum-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  border: 1px solid transparent;
  white-space: nowrap;
}
.sum-chip.ok {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
  border-color: var(--el-color-success-light-7);
}
.sum-chip.warn {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-color: var(--el-color-warning-light-7);
}
.sum-hint {
  opacity: 0.8;
}
.draft-create,
.draft-panel {
  border: 1px dashed var(--el-border-color);
  border-radius: 10px;
  padding: 14px;
}
.compact-form :deep(.el-form-item) {
  margin-bottom: 12px;
}
.weight-editor {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 320px;
  overflow-y: auto;
  padding-right: 4px;
}
.weight-row {
  display: grid;
  grid-template-columns: 150px minmax(0, 1fr) 120px;
  align-items: center;
  gap: 10px;
}
.weight-label {
  display: flex;
  flex-direction: column;
  gap: 0;
  font-size: 13px;
}
.weight-slider {
  margin: 0 4px;
}
.weight-input {
  width: 120px;
}
.draft-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}
.draft-actions.wrap {
  flex-wrap: wrap;
  padding-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.dim-sections {
  border-top: none;
  border-bottom: none;
}
.dim-sections :deep(.el-collapse-item__header) {
  font-weight: 600;
}
.dim-sections :deep(.el-collapse-item__content) {
  padding-bottom: 14px;
}
.section-num {
  margin-right: 8px;
  color: var(--el-color-primary);
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.dim-form :deep(.el-form-item) {
  margin-bottom: 14px;
}
.bucket-editor {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
}
.bucket-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  margin-bottom: 6px;
}
.bucket-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.bucket-index {
  width: 30px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.advanced-block {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.preview-result {
  margin-top: 8px;
}
.preview-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}
.preview-stat {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.preview-stat strong {
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}
.preview-tags {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.json-block {
  margin-top: 10px;
  padding: 10px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  max-height: 220px;
  overflow: auto;
}
.result-block {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 12px 0;
}
/* 批1: 发布结果弹窗内的重算操作区 */
.recalc-panel {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.recalc-panel .el-alert {
  flex: 1 1 100%;
}
.result-line {
  width: 100%;
  font-size: 13px;
  padding: 6px 10px;
  border-radius: 6px;
}
.result-line.error {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}
.result-line.warn {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
}
.delta-up {
  color: var(--el-color-success);
}
.delta-down {
  color: var(--el-color-danger);
}
@media (max-width: 1200px) {
  .rule-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .preview-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .weight-row {
    grid-template-columns: 120px minmax(0, 1fr) 100px;
  }
}
</style>
