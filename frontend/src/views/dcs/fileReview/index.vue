<template>
  <div class="dcs-ops-page file-review-page">
    <DcsOperationsPageHeader
      title="文件审核"
      subtitle="非管理员上传的文件先审后可见：待审期间仅本人与审核员可见，驳回将通知上传人重新上传。"
      eyebrow="风控与审计"
      :metrics="headerMetrics"
    >
      <template #actions>
        <el-button v-hasPermi="['dcs:fileReview:config']" type="primary" icon="Setting" @click="openConfigDialog">审核设置</el-button>
      </template>
    </DcsOperationsPageHeader>

    <el-alert
      v-if="canConfig && configLoaded && configForm.enabledRaw === '0'"
      class="file-review-off-alert"
      type="info"
      show-icon
      :closable="false"
      title="文件审核已关闭：新上传的文件不再进入审核队列；已入队的待审文件仍可在本页处理（关闭期间这些待审文件对所有人立即可见）。"
    />

    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <el-card v-show="showSearch" shadow="never" class="dcs-ops-filter-card">
        <el-form ref="queryFormRef" :model="filters" :inline="true" label-position="right">
          <el-form-item label="文件类别" prop="category">
            <el-select v-model="filters.category" clearable placeholder="全部类别" class="filter-w-150">
              <el-option v-for="opt in categoryOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="来源" prop="bizSource">
            <el-select v-model="filters.bizSource" clearable placeholder="全部来源" class="filter-w-150">
              <el-option v-for="opt in sourceOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="上传人" prop="uploaderName">
            <el-input v-model.trim="filters.uploaderName" clearable placeholder="姓名，模糊匹配" class="filter-w-160" @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="上传时间">
            <el-date-picker
              v-model="dateRange"
              type="datetimerange"
              value-format="YYYY-MM-DD HH:mm:ss"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              class="filter-w-360"
            />
          </el-form-item>
          <el-form-item>
            <el-button icon="Search" @click="handleQuery">查询</el-button>
            <el-button icon="Refresh" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </transition>

    <el-card shadow="never" class="dcs-ops-table-card">
      <el-tabs v-model="activeTab" class="file-review-tabs">
        <el-tab-pane name="pending">
          <template #label>
            <span class="file-review-tab-label">
              待审核
              <el-badge v-if="pendingCount > 0" :value="pendingCount > 99 ? '99+' : pendingCount" type="danger" class="file-review-tab-badge" />
            </span>
          </template>
        </el-tab-pane>
        <el-tab-pane name="reviewed" label="已审" />
        <el-tab-pane name="guardLogs" label="检测记录" />
      </el-tabs>

      <!-- ============ 待审页签 ============ -->
      <div v-show="activeTab === 'pending'">
        <div class="dcs-ops-table-toolbar">
          <span class="dcs-ops-table-toolbar__title">待审列表</span>
          <div class="file-review-toolbar-actions">
            <el-button
              v-hasPermi="['dcs:fileReview:approve']"
              type="success"
              plain
              icon="CircleCheck"
              :disabled="selection.length === 0"
              @click="handleBatchApprove"
            >
              批量通过{{ selection.length > 0 ? `（${selection.length} 项）` : '' }}
            </el-button>
            <right-toolbar v-model:show-search="showSearch" @query-table="getList" />
          </div>
        </div>
        <div class="dcs-ops-table-wrap">
          <el-table v-loading="loading" :data="pendingList" class="ui-table file-review-table" @selection-change="onSelectionChange">
            <el-table-column type="selection" width="46" align="center" />
            <el-table-column label="文件" width="76" align="center">
              <template #default="scope">
                <el-image
                  v-if="scope.row.fileCategory === 'IMAGE'"
                  class="file-review-thumb"
                  :src="previewUrl(scope.row.ossId)"
                  fit="cover"
                  lazy
                  :preview-src-list="[previewUrl(scope.row.ossId)]"
                  preview-teleported
                >
                  <template #error>
                    <div class="file-review-icon">
                      <el-icon><Picture /></el-icon>
                    </div>
                  </template>
                </el-image>
                <div v-else class="file-review-icon">
                  <el-icon><component :is="categoryIcon(scope.row.fileCategory)" /></el-icon>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="文件名" min-width="220">
              <template #default="scope">
                <span class="file-review-name-text">{{ scope.row.fileName || '—' }}</span>
                <span v-if="scope.row.guardReason" class="ui-pill ui-pill--neg file-review-guard-tag" :title="scope.row.guardReason">{{
                  guardTagText(scope.row.guardReason)
                }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类别" width="96" align="center">
              <template #default="scope">
                <span class="ui-pill ui-pill--muted">{{ categoryLabel(scope.row.fileCategory) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="100" align="right">
              <template #default="scope"
                ><span class="ui-num">{{ formatFileSize(scope.row.fileSize) }}</span></template
              >
            </el-table-column>
            <el-table-column label="上传人" min-width="120" show-overflow-tooltip>
              <template #default="scope">{{ scope.row.uploaderName || '—' }}</template>
            </el-table-column>
            <el-table-column label="来源" width="100" align="center">
              <template #default="scope">
                <span class="ui-pill ui-pill--muted">{{ sourceLabel(scope.row.bizSource) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="上传时间" width="180">
              <template #default="scope">{{ proxy?.parseTime(scope.row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="scope">
                <el-button link @click="handlePreview(scope.row)">预览</el-button>
                <el-button v-hasPermi="['dcs:fileReview:approve']" link @click="handleApproveOne(scope.row)">通过</el-button>
                <el-button v-hasPermi="['dcs:fileReview:reject']" link type="danger" @click="openRejectDialog(scope.row)">驳回</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">没有待审核的文件</p>
                <p class="ui-empty__hint">调整筛选条件，或等成员上传新文件后再来</p>
                <div class="ui-empty__actions">
                  <el-button @click="resetQuery">清空筛选条件</el-button>
                </div>
              </div>
            </template>
          </el-table>
        </div>
        <div class="dcs-ops-pagination">
          <pagination
            v-show="pendingTotal > 0"
            v-model:page="pendingPage.pageNum"
            v-model:limit="pendingPage.pageSize"
            :total="pendingTotal"
            @pagination="getList"
          />
        </div>
      </div>

      <!-- ============ 已审页签 ============ -->
      <div v-show="activeTab === 'reviewed'">
        <div class="dcs-ops-table-toolbar">
          <span class="dcs-ops-table-toolbar__title">已审列表</span>
          <div class="file-review-toolbar-actions">
            <el-radio-group v-model="reviewedStatus" @change="handleQuery">
              <el-radio-button value="APPROVED">已通过</el-radio-button>
              <el-radio-button value="REJECTED">已驳回</el-radio-button>
            </el-radio-group>
            <right-toolbar v-model:show-search="showSearch" @query-table="getList" />
          </div>
        </div>
        <div class="dcs-ops-table-wrap">
          <el-table v-loading="reviewedLoading" :data="reviewedList" class="ui-table file-review-table">
            <el-table-column label="文件" width="76" align="center">
              <template #default="scope">
                <div class="file-review-icon">
                  <el-icon><component :is="categoryIcon(scope.row.fileCategory)" /></el-icon>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="文件名" min-width="220" show-overflow-tooltip>
              <template #default="scope">
                <el-tooltip v-if="scope.row.status === 'REJECTED' && scope.row.rejectReason" placement="top" :show-after="300">
                  <template #content>
                    <div class="file-review-reason-tip">驳回理由：{{ scope.row.rejectReason }}</div>
                  </template>
                  <span class="file-review-name-rejected">{{ scope.row.fileName || '—' }}</span>
                </el-tooltip>
                <span v-else>{{ scope.row.fileName || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="结论" width="96" align="center">
              <template #default="scope">
                <span class="ui-pill" :class="statusPillClass(scope.row.status)">{{ statusMeta(scope.row.status).label }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类别" width="96" align="center">
              <template #default="scope">
                <span class="ui-pill ui-pill--muted">{{ categoryLabel(scope.row.fileCategory) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="100" align="right">
              <template #default="scope"
                ><span class="ui-num">{{ formatFileSize(scope.row.fileSize) }}</span></template
              >
            </el-table-column>
            <el-table-column label="上传人" min-width="120" show-overflow-tooltip>
              <template #default="scope">{{ scope.row.uploaderName || '—' }}</template>
            </el-table-column>
            <el-table-column label="审核人" min-width="120" show-overflow-tooltip>
              <template #default="scope">{{ scope.row.reviewerName || '—' }}</template>
            </el-table-column>
            <el-table-column label="审核时间" width="180">
              <template #default="scope">{{ proxy?.parseTime(scope.row.reviewTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="scope">
                <el-button link icon="View" @click="handlePreview(scope.row)">预览</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">{{ reviewedStatus === 'APPROVED' ? '没有已通过的审核记录' : '没有已驳回的审核记录' }}</p>
                <p class="ui-empty__hint">审核结论产生后会归档在这里</p>
              </div>
            </template>
          </el-table>
        </div>
        <div class="dcs-ops-pagination">
          <pagination
            v-show="reviewedTotal > 0"
            v-model:page="reviewedPage.pageNum"
            v-model:limit="reviewedPage.pageSize"
            :total="reviewedTotal"
            @pagination="getList"
          />
        </div>
      </div>

      <!-- ============ 检测记录页签（Q4=A） ============ -->
      <div v-show="activeTab === 'guardLogs'">
        <div class="dcs-ops-table-toolbar">
          <span class="dcs-ops-table-toolbar__title">安全检测命中记录</span>
          <div class="file-review-toolbar-actions">
            <el-button icon="Refresh" @click="getGuardLogList">刷新</el-button>
            <right-toolbar v-model:show-search="showSearch" @query-table="getGuardLogList" />
          </div>
        </div>
        <div class="dcs-ops-table-wrap">
          <el-table v-loading="guardLogLoading" :data="guardLogList" class="ui-table file-review-table">
            <el-table-column label="时间" width="180">
              <template #default="scope">{{ proxy?.parseTime(scope.row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="上传人" min-width="120" show-overflow-tooltip>
              <template #default="scope">{{ scope.row.userName || '—' }}</template>
            </el-table-column>
            <el-table-column label="文件名" min-width="220" show-overflow-tooltip>
              <template #default="scope">{{ scope.row.fileName || '—' }}</template>
            </el-table-column>
            <el-table-column label="命中规则" width="110" align="center">
              <template #default="scope">{{ guardRuleLabel(scope.row.ruleCode) }}</template>
            </el-table-column>
            <el-table-column label="详情" min-width="240" show-overflow-tooltip>
              <template #default="scope">{{ scope.row.detail || '—' }}</template>
            </el-table-column>
            <el-table-column label="当时模式" width="96" align="center">
              <template #default="scope">{{ scope.row.mode === 'ENFORCE' ? '拦截' : scope.row.mode === 'OFF' ? '关闭' : '观察' }}</template>
            </el-table-column>
            <el-table-column label="处置" width="104" align="center">
              <template #default="scope">
                <span class="ui-pill" :class="dispositionPillClass(scope.row.disposition)">
                  {{ guardDispositionMeta(scope.row.disposition).label }}
                </span>
              </template>
            </el-table-column>
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">近段无安全检测命中</p>
                <p class="ui-empty__hint">上传文件通过结构检测后不会产生命中记录</p>
              </div>
            </template>
          </el-table>
        </div>
        <div class="dcs-ops-pagination">
          <pagination
            v-show="guardLogTotal > 0"
            v-model:page="guardLogPage.pageNum"
            v-model:limit="guardLogPage.pageSize"
            :total="guardLogTotal"
            @pagination="getGuardLogList"
          />
        </div>
      </div>
    </el-card>

    <!-- ============ 驳回弹窗 ============ -->
    <el-dialog v-model="rejectVisible" title="驳回文件" width="min(520px, calc(100vw - 32px))" append-to-body>
      <div v-if="rejectTarget" class="file-review-reject-file">
        <div class="file-review-icon">
          <el-icon><component :is="categoryIcon(rejectTarget.fileCategory)" /></el-icon>
        </div>
        <div class="file-review-reject-file__meta">
          <div class="file-review-reject-file__name">{{ rejectTarget.fileName }}</div>
          <div class="file-review-reject-file__sub">上传人：{{ rejectTarget.uploaderName }}</div>
        </div>
      </div>
      <div class="file-review-reason-presets">
        <span class="file-review-reason-presets__label">常用理由</span>
        <button
          v-for="reason in presetReasons"
          :key="reason"
          type="button"
          class="ui-pill ui-pill--muted file-review-reason-tag"
          @click="rejectForm.reason = reason"
        >
          {{ reason }}
        </button>
      </div>
      <el-input v-model="rejectForm.reason" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="填写驳回理由（2-500 字）" />
      <div class="file-review-reject-hint">
        <el-icon><InfoFilled /></el-icon>
        驳回后将站内通知上传人重新上传，文件对其他人不可见。
      </div>
      <!-- 头像单生效口径(拍板Q5A/U08定信, 2026-09-23): 待审期间用户头像保持旧图是 12b 有意设计
           (SysProfileController "先审后生效"), 审核人侧补一句说明防误以为头像切换丢了 -->
      <div v-if="rejectTarget?.bizSource === 'AVATAR'" class="file-review-reject-hint">
        <el-icon><InfoFilled /></el-icon>
        该文件是用户头像：通过后头像自动切换为新图；驳回则用户保留旧头像，会收到重传通知。
      </div>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejectSubmitting" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- ============ 设置弹窗（改版：两页签分家，Q5=A） ============ -->
    <el-dialog v-model="configVisible" title="文件审核设置" width="min(600px, calc(100vw - 32px))" append-to-body>
      <div v-loading="configLoading">
        <el-tabs v-model="configTab" class="file-review-config-tabs">
          <!-- ===== 页签一：内容审核（人审尺度） ===== -->
          <el-tab-pane label="内容审核" name="review">
            <el-form label-width="90px">
              <el-form-item label="审核开关">
                <el-switch v-model="configForm.enabled" active-text="开启" inactive-text="关闭" />
              </el-form-item>
              <el-form-item label="审核类型">
                <el-checkbox-group v-model="configForm.categories" class="file-review-config-cats">
                  <el-checkbox v-for="opt in categoryOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</el-checkbox>
                </el-checkbox-group>
              </el-form-item>
            </el-form>
            <el-alert
              v-if="configMeta.reviewerCount === 0"
              class="file-review-config-alert"
              type="warning"
              show-icon
              :closable="false"
              title="当前没有文件审核员（file_reviewer 角色），开启后上传的文件将无人处理，请先为用户分配该角色。"
            />
            <el-alert
              v-if="configMeta.pendingCount > 0"
              class="file-review-config-alert"
              type="info"
              show-icon
              :closable="false"
              :title="`当前有 ${configMeta.pendingCount} 条待审文件：关闭开关后新上传不再进入审核，已入队的待审文件仍可继续处理（关闭期间这些文件对所有人立即可见）。`"
            />
            <div class="file-review-config-note">未勾选的类型上传后直接通过，不进入审核队列；管理员与租户管理员的上传始终免审。</div>
          </el-tab-pane>

          <!-- ===== 页签二：安全检测（机器把关） ===== -->
          <el-tab-pane label="安全检测" name="guard">
            <!-- 近 7 天命中概览条（Q4=A：观察期运营抓手） -->
            <div class="file-review-guard-statbar">
              <!-- 三态（2026-10-27 批3）：取不到检测统计时说取不到，不要写成「命中 0 次」 -->
              <template v-if="guardStatsError">
                <span class="file-review-guard-statbar__main">近 7 天检测统计暂时取不到</span>
                <el-button link type="primary" class="file-review-guard-statbar__link" @click="refreshGuardStats">重试</el-button>
              </template>
              <template v-else>
                <span class="file-review-guard-statbar__main"
                  >近 7 天命中 <b>{{ guardStats.total }}</b> 次</span
                >
                <span class="file-review-guard-statbar__sub"
                  >观察放行 {{ guardStats.observed }} · 强制进审 {{ guardStats.reviewed }} · 拦截 {{ guardStats.denied }}</span
                >
                <el-button link type="primary" class="file-review-guard-statbar__link" @click="gotoGuardLogs">查看检测记录</el-button>
              </template>
            </div>

            <el-form label-width="90px">
              <!-- 检测模式三卡片（Q3=A） -->
              <el-form-item>
                <template #label>
                  <span>检测模式</span>
                  <el-tooltip placement="top" :show-after="300">
                    <template #content>
                      <div class="file-review-guard-tip">
                        机器自动把关上传文件：识别伪装文件、文档里的宏和隐藏脚本。<br />与「内容审核」（人工审内容）互不影响。
                      </div>
                    </template>
                    <el-icon class="file-review-guard-info"><InfoFilled /></el-icon>
                  </el-tooltip>
                </template>
                <div class="file-review-mode-cards">
                  <div
                    v-for="card in guardModeCards"
                    :key="card.value"
                    class="file-review-mode-card"
                    :class="{ 'is-active': configForm.guardMode === card.value }"
                    @click="configForm.guardMode = card.value"
                  >
                    <el-icon class="file-review-mode-card__icon"><component :is="card.icon" /></el-icon>
                    <div class="file-review-mode-card__label">{{ card.label }}</div>
                    <div class="file-review-mode-card__desc">{{ card.desc }}</div>
                  </div>
                </div>
              </el-form-item>
              <div class="file-review-config-note">{{ guardModeNote }}</div>

              <!-- 允许上传六大类（Q1=A：界面上不出现"扩展名"） -->
              <el-form-item>
                <template #label>
                  <span>允许上传</span>
                  <el-tooltip placement="top" :show-after="300">
                    <template #content>
                      <div class="file-review-guard-tip">勾选的大类可以上传；没勾的（如压缩包）上传时直接提示不支持。<br />默认全开。</div>
                    </template>
                    <el-icon class="file-review-guard-info"><InfoFilled /></el-icon>
                  </el-tooltip>
                </template>
                <div class="file-review-cat-grid">
                  <el-checkbox
                    v-for="opt in guardCatOptions"
                    :key="opt.value"
                    :model-value="guardCats.includes(opt.value)"
                    @change="toggleGuardCat(opt.value)"
                    class="file-review-cat-item"
                  >
                    <span class="file-review-cat-item__name">{{ opt.label }}</span>
                    <span class="file-review-cat-item__fmt">{{ categoryExtHint(opt.value) }}</span>
                  </el-checkbox>
                </div>
              </el-form-item>
              <el-alert
                v-if="guardCustomWhitelist"
                class="file-review-config-alert"
                type="info"
                show-icon
                :closable="false"
                title="当前白名单为此前手工配置的扩展名组合，与六大类勾选不完全对应；调整勾选或点恢复默认后回到大类口径。"
              >
                <el-button link type="primary" @click="resetGuardCats">恢复默认（全开）</el-button>
              </el-alert>

              <!-- 高级设置折叠（Q2=A：性能参数不出现在默认视线） -->
              <el-collapse class="file-review-adv">
                <el-collapse-item name="adv">
                  <template #title>
                    <span class="file-review-adv__title">高级设置（一般不用动）</span>
                  </template>
                  <el-form-item label="深扫上限">
                    <el-input-number
                      v-model="configForm.guardDeepScanMaxMb"
                      :min="1"
                      :max="500"
                      :step="10"
                      controls-position="right"
                      class="file-review-deepscan"
                    />
                    <span class="file-review-guard-unit">MB —— 更大的文件只核对类型不逐页翻查，防卡顿；一般不用改</span>
                  </el-form-item>
                </el-collapse-item>
              </el-collapse>
            </el-form>
          </el-tab-pane>
        </el-tabs>
      </div>
      <template #footer>
        <el-button @click="configVisible = false">取消</el-button>
        <el-button type="primary" :loading="configSubmitting" @click="submitConfig">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="DcsFileReview" lang="ts">
import {
  approveFileReview,
  getGuardStats,
  getFileReviewConfig,
  getFileReviewPendingCount,
  listFileReview,
  listGuardLogs,
  rejectFileReview,
  saveFileReviewConfig
} from '@/api/dcs/fileReview';
import type { FileReviewRow, GuardLogRow, GuardStats } from '@/api/dcs/fileReview/types';
import { previewUrl } from '@/api/system/oss';
import auth from '@/plugins/auth';
import type { DcsOperationsMetric } from '@/components/DcsOperationsPageHeader/index.vue';
import { Box, CircleClose, Document, Files, Headset, Lock, Picture, Tickets, VideoPlay, View } from '@element-plus/icons-vue';
import {
  FILE_REVIEW_CATEGORY_EXTS,
  FILE_REVIEW_CATEGORY_META,
  FILE_REVIEW_CATEGORY_OPTIONS,
  FILE_REVIEW_PRESET_REASONS,
  FILE_REVIEW_SOURCE_LABEL,
  FILE_REVIEW_STATUS_META,
  GUARD_DISPOSITION_META,
  GUARD_RULE_LABEL,
  categoryExtHint,
  formatFileSize
} from './fileReviewPresentation';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const queryFormRef = ref<ElFormInstance>();

const activeTab = ref<'pending' | 'reviewed' | 'guardLogs'>('pending');
const reviewedStatus = ref<'APPROVED' | 'REJECTED'>('APPROVED');
const showSearch = ref(true);
const dateRange = ref<[string, string]>(['', '']);
const filters = reactive({ category: '', bizSource: '', uploaderName: '' });

const loading = ref(false);
const reviewedLoading = ref(false);
const pendingList = ref<FileReviewRow[]>([]);
const reviewedList = ref<FileReviewRow[]>([]);
const pendingTotal = ref(0);
const reviewedTotal = ref(0);
const pendingCount = ref(0);
const pendingError = ref(false);
const guardStatsError = ref(false);
const pendingPage = reactive({ pageNum: 1, pageSize: 20 });
const reviewedPage = reactive({ pageNum: 1, pageSize: 20 });

/** 待审表勾选集（仅当前页，批量通过后清空） */
const selection = ref<FileReviewRow[]>([]);

const rejectVisible = ref(false);
const rejectSubmitting = ref(false);
const rejectTarget = ref<FileReviewRow>();
const rejectForm = reactive({ reason: '' });

const canConfig = auth.hasPermi('dcs:fileReview:config');
const configVisible = ref(false);
const configLoading = ref(false);
const configSubmitting = ref(false);
const configLoaded = ref(false);
const configTab = ref<'review' | 'guard'>('review');
const configForm = reactive({
  enabled: false,
  enabledRaw: '0',
  categories: [] as string[],
  // file-guard 安全检测三件（批三，Q5=B 界面化）
  guardMode: 'OBSERVE',
  guardWhitelist: '',
  guardDeepScanMaxMb: 50
});

/** 允许上传大类勾选（Q1=A：与 guardWhitelist 串互转，界面上不出现"扩展名"） */
const guardCats = ref<string[]>(Object.keys(FILE_REVIEW_CATEGORY_EXTS));
/** 行勾选（el-checkbox 单挂数组 model 不在组内属未文档用法——显式维护，行为等价） */
function toggleGuardCat(v: string) {
  const i = guardCats.value.indexOf(v);
  if (i >= 0) guardCats.value.splice(i, 1);
  else guardCats.value.push(v);
  onGuardCatsChange(guardCats.value);
}
/** 存量白名单串对不上任何大类组合时为 true（显示"自定义配置"提示+恢复默认） */
const guardCustomWhitelist = ref(false);
/** 近 7 天命中概览（Q4=A：观察期运营抓手） */
const guardStats = ref<GuardStats>({ total: 0, denied: 0, reviewed: 0, observed: 0 });

/** 检测模式三卡片的展示元数据（Q3=A） */
const guardModeCards = [
  { value: 'OFF', label: '关闭', icon: CircleClose, desc: '不检查\n全靠人审' },
  { value: 'OBSERVE', label: '观察', icon: View, desc: '只记录不拦截\n上线先开这个' },
  { value: 'ENFORCE', label: '拦截', icon: Lock, desc: '危险文件拒传\n可疑送人工' }
] as const;

/** 大类勾选 → 白名单串（全勾=空串=默认；Q1=A 合同） */
const categoriesToWhitelist = (cats: string[]): string => {
  const all = Object.keys(FILE_REVIEW_CATEGORY_EXTS);
  if (all.every((c) => cats.includes(c))) {
    return '';
  }
  return cats.flatMap((c) => FILE_REVIEW_CATEGORY_EXTS[c] || []).join(',');
};

/** 白名单串 → 大类勾选；串不等于任何大类组合时置 custom 标记（存量手打串兜底） */
const whitelistToCategories = (whitelist: string): string[] => {
  const set = new Set(
    (whitelist || '')
      .split(',')
      .map((s) => s.trim().toLowerCase())
      .filter(Boolean)
  );
  const all = Object.keys(FILE_REVIEW_CATEGORY_EXTS);
  if (set.size === 0) {
    guardCustomWhitelist.value = false;
    return [...all];
  }
  const contained = all.filter((c) => (FILE_REVIEW_CATEGORY_EXTS[c] || []).every((e) => set.has(e)));
  const union = new Set(contained.flatMap((c) => FILE_REVIEW_CATEGORY_EXTS[c] || []));
  guardCustomWhitelist.value = [...union].length !== set.size;
  return contained;
};

/** 勾选变化即写回白名单串（并清掉"自定义"标记——用户接管后回到大类口径） */
const onGuardCatsChange = (cats: string[]) => {
  guardCustomWhitelist.value = false;
  configForm.guardWhitelist = categoriesToWhitelist(cats);
};

/** 恢复默认=六大类全勾 */
const resetGuardCats = () => {
  guardCats.value = Object.keys(FILE_REVIEW_CATEGORY_EXTS);
  onGuardCatsChange(guardCats.value);
};

/** 检测模式说明（大白话，随模式卡片联动；Q4 落库后口径改"检测记录"） */
const guardModeNote = computed(() => {
  switch (configForm.guardMode) {
    case 'OFF':
      return '检测完全关闭：任何文件都不查、不拦、不进审。';
    case 'ENFORCE':
      return '拦截模式：白名单外的文件、伪装扩展名的文件直接拒绝上传；带宏或藏脚本的文档强制进入审核队列（待审列表中标风险标签）。';
    default:
      return '观察模式：只记录不拦截——命中会记入「检测记录」页签，建议观察一周确认无误报后再切拦截。';
  }
});

/** 检测记录页签状态（Q4=A） */
const guardLogLoading = ref(false);
const guardLogList = ref<GuardLogRow[]>([]);
const guardLogPage = reactive({ pageNum: 1, pageSize: 10 });
const guardLogTotal = ref(0);

const guardRuleLabel = (code?: string) => GUARD_RULE_LABEL[code || ''] || code || '—';
const guardDispositionMeta = (code?: string) => GUARD_DISPOSITION_META[code || ''] || { label: code || '—', tagType: 'info' as const };

const getGuardLogList = async () => {
  guardLogLoading.value = true;
  try {
    const res = (await listGuardLogs({ ...guardLogPage } as any)) as any;
    guardLogList.value = res.rows || [];
    guardLogTotal.value = res.total || 0;
  } finally {
    guardLogLoading.value = false;
  }
};

const loadGuardStats = async () => {
  try {
    const res = await getGuardStats();
    const d = res.data || { total: 0, denied: 0, reviewed: 0, observed: 0 };
    guardStats.value = {
      total: Number(d.total) || 0,
      denied: Number(d.denied) || 0,
      reviewed: Number(d.reviewed) || 0,
      observed: Number(d.observed) || 0
    };
    guardStatsError.value = false;
  } catch {
    guardStats.value = { total: 0, denied: 0, reviewed: 0, observed: 0 };
    guardStatsError.value = true;
  }
};

/** 概览条上的「重试」：只重拉检测统计，不动整页 */
const refreshGuardStats = () => loadGuardStats();

/** 概览条「查看检测记录」：关设置弹窗并切到检测记录页签 */
const gotoGuardLogs = () => {
  configVisible.value = false;
  activeTab.value = 'guardLogs';
};

/** 风险 tag 短文案（完整说明挂 title 悬停） */
const guardTagText = (reason: string) => {
  if (!reason) {
    return '';
  }
  return reason.length > 18 ? `${reason.slice(0, 18)}…` : reason;
};
const configMeta = reactive({ pendingCount: 0, reviewerCount: 0 });

const categoryOptions = FILE_REVIEW_CATEGORY_OPTIONS;
/** 允许上传大类（Q1=A）：六大类，OTHER 无固定扩展名不参与 */
const guardCatOptions = FILE_REVIEW_CATEGORY_OPTIONS.filter((o) => FILE_REVIEW_CATEGORY_EXTS[o.value]);
const sourceOptions = Object.entries(FILE_REVIEW_SOURCE_LABEL).map(([value, label]) => ({ value, label }));
const presetReasons = FILE_REVIEW_PRESET_REASONS;

/** 类别图标名 → 组件（模板动态 component 用） */
const categoryIcons: Record<string, Component> = {
  Picture,
  VideoPlay,
  Headset,
  Document,
  Tickets,
  Files,
  Box
};

const headerMetrics = computed<DcsOperationsMetric[]>(() => [
  {
    label: '待审核',
    // 三态：取不到时写破折号，0 是「真的没有待审」
    value: pendingError.value ? '—' : pendingCount.value,
    hint: pendingError.value ? '待审数量暂时取不到' : '待审期间仅本人与审核员可见',
    tone: !pendingError.value && pendingCount.value > 0 ? 'warning' : 'primary'
  },
  {
    label: '本页结果',
    value: activeTab.value === 'pending' ? pendingTotal.value : activeTab.value === 'reviewed' ? reviewedTotal.value : guardLogTotal.value,
    hint: '按筛选条件统计',
    tone: 'primary'
  },
  { label: '审核口径', value: '先审后可见', hint: '驳回通知上传人重传', tone: 'success' }
]);

const categoryLabel = (code: string) => FILE_REVIEW_CATEGORY_META[code]?.label || code || '—';
const categoryIcon = (code: string) => categoryIcons[FILE_REVIEW_CATEGORY_META[code]?.icon || 'Box'];
const sourceLabel = (code: string) => FILE_REVIEW_SOURCE_LABEL[code] || code || '—';
const statusMeta = (status: string) => FILE_REVIEW_STATUS_META[status] || { label: status, tagType: 'info' as const };
/** 审核结论药丸：通过=成功、驳回=危险、待审=警告 */
const statusPillClass = (status: string) => {
  if (status === 'APPROVED') return 'ui-pill--pos';
  if (status === 'REJECTED') return 'ui-pill--neg';
  return 'ui-pill--warn';
};
/** 检测处置药丸：拒传=危险、强制进审=警告、观察放行=中性 */
const dispositionPillClass = (code?: string) => {
  if (code === 'DENIED') return 'ui-pill--neg';
  if (code === 'REVIEWED') return 'ui-pill--warn';
  return 'ui-pill--muted';
};

const buildQuery = (status: string, page: { pageNum: number; pageSize: number }) => {
  const query: Record<string, unknown> = {
    ...page,
    status,
    category: filters.category || undefined,
    bizSource: filters.bizSource || undefined,
    uploaderName: filters.uploaderName || undefined
  };
  return proxy?.addDateRange(query, dateRange.value) || query;
};

const getList = async () => {
  if (activeTab.value === 'pending') {
    loading.value = true;
    try {
      const res = (await listFileReview(buildQuery('PENDING', pendingPage) as any)) as any;
      pendingList.value = res.rows || [];
      pendingTotal.value = res.total || 0;
    } finally {
      loading.value = false;
    }
  } else {
    reviewedLoading.value = true;
    try {
      const res = (await listFileReview(buildQuery(reviewedStatus.value, reviewedPage) as any)) as any;
      reviewedList.value = res.rows || [];
      reviewedTotal.value = res.total || 0;
    } finally {
      reviewedLoading.value = false;
    }
  }
};

const refreshPendingCount = async () => {
  try {
    const res = await getFileReviewPendingCount();
    pendingCount.value = Number(res.data) || 0;
    pendingError.value = false;
  } catch {
    pendingCount.value = 0;
    pendingError.value = true;
  }
};

const handleQuery = () => {
  pendingPage.pageNum = 1;
  reviewedPage.pageNum = 1;
  getList();
};

const resetQuery = () => {
  filters.category = '';
  filters.bizSource = '';
  filters.uploaderName = '';
  dateRange.value = ['', ''];
  handleQuery();
};

watch(activeTab, () => {
  if (activeTab.value === 'guardLogs') {
    getGuardLogList();
  } else {
    getList();
  }
});
watch(reviewedStatus, () => {
  reviewedPage.pageNum = 1;
});

const onSelectionChange = (rows: FileReviewRow[]) => {
  selection.value = rows;
};

const handlePreview = (row: FileReviewRow) => {
  window.open(previewUrl(row.ossId), '_blank');
};

/** 通过后汇总提示（含批量），conflict=已被他人处理 */
const toastApproveResult = (successCount: number, conflictCount: number) => {
  if (conflictCount === 0) {
    ElMessage.success(`已通过 ${successCount} 个文件`);
  } else {
    ElMessage.warning(`成功 ${successCount} 个，${conflictCount} 个已被处理自动跳过`);
  }
};

const approve = async (reviewIds: (string | number)[]) => {
  const res = await approveFileReview(reviewIds);
  toastApproveResult(res.data.success?.length || 0, res.data.conflict?.length || 0);
  await Promise.all([getList(), refreshPendingCount()]);
};

const handleApproveOne = async (row: FileReviewRow) => {
  await approve([row.reviewId]);
};

const handleBatchApprove = async () => {
  const count = selection.value.length;
  if (count === 0) return;
  try {
    await ElMessageBox.confirm(`确认通过选中的 ${count} 个文件？通过后所有人可见。`, '批量通过', {
      type: 'warning',
      confirmButtonText: '通过',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  await approve(selection.value.map((row) => row.reviewId));
};

// destroy-on-close 只销毁弹窗 DOM 不重置 setup 状态：表单在 visible 变化时手动重置
watch(rejectVisible, (visible) => {
  if (visible) {
    rejectForm.reason = '';
  } else {
    rejectTarget.value = undefined;
  }
});

const openRejectDialog = (row: FileReviewRow) => {
  rejectTarget.value = row;
  rejectForm.reason = '';
  rejectVisible.value = true;
};

const submitReject = async () => {
  if (!rejectTarget.value) return;
  const reason = rejectForm.reason.trim();
  if (reason.length < 2 || reason.length > 500) {
    ElMessage.warning('驳回理由长度须在 2 到 500 个字符之间');
    return;
  }
  rejectSubmitting.value = true;
  try {
    await rejectFileReview(rejectTarget.value.reviewId, reason);
    ElMessage.success('已驳回并通知上传人');
    rejectVisible.value = false;
    await Promise.all([getList(), refreshPendingCount()]);
  } finally {
    rejectSubmitting.value = false;
  }
};

const loadConfig = async () => {
  const res = await getFileReviewConfig();
  const data = res.data;
  configForm.enabledRaw = data?.enabled || '0';
  configForm.enabled = data?.enabled === '1';
  configForm.categories = data?.categories ? data.categories.split(',').filter(Boolean) : [];
  configForm.guardMode = data?.guardMode || 'OBSERVE';
  configForm.guardWhitelist = data?.guardWhitelist || '';
  configForm.guardDeepScanMaxMb = data?.guardDeepScanMaxMb ?? 50;
  guardCats.value = whitelistToCategories(configForm.guardWhitelist);
  configMeta.pendingCount = Number(data?.pendingCount) || 0;
  configMeta.reviewerCount = Number(data?.reviewerCount) || 0;
  configLoaded.value = true;
};

const openConfigDialog = async () => {
  configVisible.value = true;
  configTab.value = 'review';
  configLoading.value = true;
  try {
    await Promise.all([loadConfig(), loadGuardStats()]);
  } finally {
    configLoading.value = false;
  }
};

const submitConfig = async () => {
  if (configForm.enabled && configForm.categories.length === 0) {
    ElMessage.warning('开启审核至少勾选一种文件类型');
    return;
  }
  if (!guardCustomWhitelist.value && guardCats.value.length === 0) {
    ElMessage.warning('安全检测至少允许一种文件类型');
    return;
  }
  configSubmitting.value = true;
  try {
    await saveFileReviewConfig({
      enabled: configForm.enabled,
      categories: configForm.categories,
      guardMode: configForm.guardMode,
      guardWhitelist: configForm.guardWhitelist,
      guardDeepScanMaxMb: configForm.guardDeepScanMaxMb
    });
    ElMessage.success('文件审核设置已保存');
    configVisible.value = false;
    configForm.enabledRaw = configForm.enabled ? '1' : '0';
    await refreshPendingCount();
  } finally {
    configSubmitting.value = false;
  }
};

onMounted(() => {
  getList();
  refreshPendingCount();
  // 有配置权限时静默拉取一次配置：顶部"审核已关闭"横幅依赖 enabledRaw，
  // 不先拉 configLoaded 恒 false 首屏永不显示（审查修复）
  if (canConfig) {
    loadConfig().catch(() => {});
  }
});
</script>

<style scoped lang="scss">
/* ===== 弹窗内容样式（设置弹窗 / 驳回弹窗）=====
   两个弹窗均 append-to-body：DOM 渲染在 body 直下而非 .file-review-page 容器内，
   规则嵌套在 .file-review-page 下会因祖先选择器匹配不上而整组失效
   （2026-09-18 检测模式卡片竖排事故根因）。弹窗内元素同样携带本组件 data-v
   属性，顶层 scoped 规则即可命中。 */

.file-review-table {
  --el-table-border-color: var(--app-rule);
  --el-table-header-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-row-hover-bg-color: var(--app-raised);
}

.file-review-reject-file {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  margin-bottom: 14px;
  background: var(--app-raised);
  border-radius: var(--app-control-radius);

  &__meta {
    min-width: 0;
  }

  &__name {
    overflow: hidden;
    color: var(--el-text-color-primary);
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__sub {
    margin-top: 2px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }
}

.file-review-reason-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;

  &__label {
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }

  .file-review-reason-tag {
    border: 0;
    font-family: inherit;
    cursor: pointer;

    &:hover {
      color: var(--app-text);
    }
  }
}

.file-review-reject-hint {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 13px;

  .el-icon {
    color: var(--el-color-info);
  }
}

.file-review-config-alert {
  margin-bottom: 10px;
}

/* 七类目 4+3 网格排布，避免弹性流式 5+2 右侧大片留白（视觉审查修复） */
.file-review-config-cats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 4px 8px;
  width: 100%;
}

.file-review-config-note {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

/* file-guard 批三：设置弹窗安全检测块 */
.file-review-guard-divider {
  margin: 4px 0 16px;
}

.file-review-guard-unit {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

/* ===== 弹窗改版（全A拍板）：页签分家/概览条/三卡片/大类勾选/高级折叠/ⓘ ===== */
.file-review-config-tabs {
  :deep(.el-tabs__header) {
    margin-bottom: 12px;
  }
  :deep(.el-tabs__content) {
    overflow: visible; /* ⓘ 悬停气泡不被页签容器裁掉 */
  }
}

.file-review-guard-statbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 14px;
  padding: 10px 14px;
  margin-bottom: 14px;
  background: var(--app-raised);
  border: 1px solid var(--app-border);
  border-radius: var(--app-control-radius);
  font-size: 13px;
  color: var(--app-text);

  &__main b {
    margin: 0 2px;
    color: var(--app-text);
    font-size: 17px;
    font-variant-numeric: tabular-nums;
  }

  &__sub {
    color: var(--el-text-color-secondary);
  }

  &__link {
    margin-left: auto;
  }
}

.file-review-mode-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  width: 100%;
}

.file-review-mode-card {
  padding: 10px 6px 8px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-control-radius);
  background: var(--app-surface);
  text-align: center;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background-color 0.15s;

  &:hover {
    border-color: var(--app-control);
  }

  &.is-active {
    border-color: var(--app-text);
    background: var(--app-raised);

    .file-review-mode-card__icon {
      color: var(--app-text);
      background: var(--app-surface);
    }

    .file-review-mode-card__label {
      color: var(--app-text);
    }
  }

  &__icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    margin: 0 auto 6px;
    border-radius: 50%;
    background: var(--app-raised);
    color: var(--app-muted);
    font-size: 16px;
  }

  &__label {
    margin-bottom: 2px;
    color: var(--app-text);
    font-size: 14px;
    font-weight: 600;
  }

  &__desc {
    color: var(--app-muted);
    font-size: 12px;
    line-height: 1.5;
    white-space: pre-line;
  }
}

.file-review-cat-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  width: 100%;
}

.file-review-cat-item {
  display: flex;
  align-items: flex-start;
  height: auto;
  margin-right: 0;
  padding: 8px 10px;
  border: 1px solid var(--app-rule);
  border-radius: var(--app-control-radius);
  background: var(--app-surface);

  :deep(.el-checkbox__label) {
    display: flex;
    flex-direction: column;
    gap: 1px;
    white-space: normal;
    line-height: 1.45;
    min-width: 0;
  }

  &__name {
    font-size: 13px;
    color: var(--el-text-color-primary);
  }

  &__fmt {
    font-size: 11px;
    color: var(--el-text-color-secondary);
    word-break: break-all;
  }
}

.file-review-adv {
  border-top: 1px solid var(--app-rule);
  border-bottom: none;
  margin-top: 4px;

  &__title {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  :deep(.el-collapse-item__header) {
    background: transparent;
    border-bottom: none;
    height: 40px;
    line-height: 40px;
  }

  :deep(.el-collapse-item__wrap) {
    background: transparent;
    border-bottom: none;
  }

  :deep(.el-collapse-item__content) {
    padding-bottom: 4px;
  }
}

.file-review-guard-info {
  margin-left: 4px;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  vertical-align: -2px;
  cursor: help;
}

.file-review-guard-tip {
  max-width: 260px;
  line-height: 1.6;
}

.file-review-page {
  min-width: 0;

  :deep(.el-card__body) {
    min-width: 0;
  }

  .file-review-off-alert {
    margin-bottom: 12px;
  }

  .filter-w-150 {
    width: 150px;
  }
  .filter-w-160 {
    width: 160px;
  }
  .filter-w-360 {
    width: 360px;
  }

  .file-review-tabs {
    :deep(.el-tabs__header) {
      margin-bottom: 4px;
    }

    .file-review-tab-label {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }

    .file-review-tab-badge {
      :deep(.el-badge__content) {
        transform: none;
      }
    }
  }

  .file-review-toolbar-actions {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .file-review-thumb {
    width: 38px;
    height: 38px;
    border-radius: var(--app-control-radius);
  }

  .file-review-name-rejected {
    color: var(--el-color-danger);
    cursor: help;
    text-decoration: underline dotted;
  }

  .file-review-reason-tip {
    max-width: 320px;
    line-height: 1.6;
  }

  .file-review-name-text {
    vertical-align: middle;
  }

  .file-review-guard-tag {
    margin-left: 6px;
    vertical-align: middle;
  }
}

/* append-to-body 弹窗内的元素也必须用顶层规则（祖先 .file-review-page 匹配不到） */
.file-review-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: var(--app-control-radius);
  background: var(--app-raised);
  color: var(--app-muted);
  font-size: 18px;
}

.file-review-deepscan {
  width: 140px;
}
</style>
