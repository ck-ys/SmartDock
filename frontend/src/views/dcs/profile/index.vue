<template>
  <div class="app-container profile-page">
    <dcs-operations-page-header
      eyebrow="画像引擎"
      title="用户画像"
      subtitle="看清本单位的每一位同事：谁已上手、谁还没来、谁需要联系。画像只记真实业务操作——登录、发布、提交、审核，配置与运维操作不进。"
      :metrics="overviewMetrics"
    >
      <template #actions>
        <el-select v-model="identity" class="identity-select" @change="onIdentityChange">
          <el-option v-for="opt in IDENTITY_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <!-- 题1: 自监控开关搬到画像页明面(原藏三层菜单), 停用后登录/核心操作不再新增记录 -->
        <el-tooltip v-if="canEditConfig" content="记录本租户用户的登录与核心操作，整租户生效；翻页浏览不记录" placement="bottom">
          <div class="selfmon-toggle">
            <span class="selfmon-toggle__label">自动记录行为</span>
            <el-switch :model-value="selfMonitor" active-value="1" inactive-value="0" :loading="selfMonSaving" @change="onSelfMonitorChange" />
          </div>
        </el-tooltip>
        <el-button v-hasPermi="['profile:review:query']" @click="reviewVisible = true">低分复核</el-button>
        <el-button type="primary" @click="reloadAll()">刷新</el-button>
      </template>
    </dcs-operations-page-header>

    <!-- 三审三席 P1: 旧「记录被整体停用」空态指向已随来源管理页签退役的启用入口(死路)——整分支删除;
         零记录时接入指南与全员名册同屏: 名册本就该列「谁还没来」, 不再互相顶替 -->
    <el-card v-if="noRealSource" shadow="never" class="empty-guide">
      <div class="empty-guide__title">还没有任何使用记录</div>
      <p class="empty-guide__text">
        画像只分析真实业务操作——同事登录、发布 / 提交 / 审核任务后这里会自动出现记录；业务系统想接进来，照下面的三步做即可。
      </p>
      <AccessGuideCard class="empty-guide__card" variant="onboard" />
      <div class="empty-guide__foot">
        <el-button :loading="!sourcesReady" @click="reloadAll">重新检测</el-button>
        <span v-if="!canTestSend" class="empty-guide__hint">数据接入由管理员在「引擎与运维」里配置</span>
      </div>
    </el-card>

    <el-card v-if="sourcesReady" shadow="never" class="table-card">
      <template #header>
        <div class="list-head">
          <!-- 题5 灵魂口径: 只给名单不排座次——排序说清是"最近活跃", 不出现名次 -->
          <span class="list-head__caption muted"
            >{{ identityCaption }} · 按最近活跃排序 · 采用度 = 记录到的真实业务操作次数（登录、发布、提交、审核）</span
          >
          <!-- 题4: 接入指南常驻(有数据时的文案分叉: 怎么更准) -->
          <el-button link type="primary" @click="guideVisible = true">让画像更准 · 接入指南</el-button>
        </div>
      </template>
      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          :placeholder="identity === 'user' ? '姓名 / 账号 / ID' : 'ID / 名称'"
          clearable
          style="width: 190px"
          @keyup.enter="search"
        />
        <div class="filter-actions">
          <el-button @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
          <DcsTableColumnSettings v-model="hiddenColumns" :columns="columnOptions" storage-key="profile-mainview" />
        </div>
      </div>

      <el-table v-loading="loading" class="ui-table" :data="rows" row-key="subjectId">
        <template #empty>
          <div class="ui-empty">
            <p class="ui-empty__title">{{ identity === 'user' ? '本单位还没有用户' : '这个身份下还没有数据' }}</p>
            <p class="ui-empty__hint">{{ identity === 'user' ? '请先在系统管理里创建用户' : '接入对应身份的业务事件后，出现在这里' }}</p>
          </div>
        </template>
        <el-table-column label="用户" min-width="200">
          <template #default="{ row }">
            <div class="subject-cell">
              <span class="subject-name">
                {{ row.displayName || row.subjectId }}
                <span v-if="row.userStatus === '1'" class="ui-pill ui-pill--muted">已停用</span>
              </span>
              <span class="subject-meta">{{ row.userName || row.subjectId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="identity === 'user' && isColumnVisible('dept')" label="部门" width="130">
          <template #default="{ row }">{{ row.deptName || '—' }}</template>
        </el-table-column>
        <el-table-column v-if="isColumnVisible('lastEvent')" label="最近活跃" width="150" align="right">
          <template #default="{ row }">
            <span v-if="row.lastEventTime">{{ fmtTime(row.lastEventTime) }}</span>
            <span v-else class="muted">还没开始用</span>
          </template>
        </el-table-column>
        <el-table-column v-if="isColumnVisible('adoption')" label="采用度" width="110" align="right">
          <template #default="{ row }">
            <span class="count-number">{{ Number(row.eventCount ?? 0) }}</span>
            <span class="muted count-unit"> 次</span>
          </template>
        </el-table-column>
        <el-table-column v-if="canSeeScore && isColumnVisible('score')" label="评分" width="180">
          <template #default="{ row }">
            <div v-if="row.totalScore != null && !row.noDimensions" class="score-cell">
              <el-progress
                :percentage="clamp(Number(row.totalScore))"
                :stroke-width="8"
                :show-text="false"
                :color="scoreColor(Number(row.totalScore))"
              />
              <span class="score-number">{{ formatScore(row.totalScore) }}</span>
            </div>
            <!-- 夜审 P2 落地: 未配置打分口径的 50 分只是占位, 不冒充真分数 -->
            <span v-else-if="row.noDimensions" class="ui-pill ui-pill--muted">未配置打分规则</span>
            <span v-else class="muted">未算分</span>
          </template>
        </el-table-column>
        <el-table-column v-if="isColumnVisible('status')" label="状态" width="100">
          <template #default="{ row }">
            <span v-if="row.pendingReview" class="ui-pill ui-pill--warn">待联系</span>
            <span v-else-if="!Number(row.eventCount ?? 0)" class="ui-pill ui-pill--muted">还没用</span>
            <span v-else class="muted">已上手</span>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row)">看画像</el-button>
            <!-- 依据解释的是分数与维度——一条记录都没有的人没有可解释的, 不给入口 -->
            <el-button v-if="Number(row.eventCount ?? 0) > 0" link @click="openBasis(row)">依据</el-button>
            <el-button v-if="row.pendingReview" v-hasPermi="['profile:review:query']" link type="warning" @click="reviewVisible = true"
              >去复核</el-button
            >
            <el-button v-else-if="!Number(row.eventCount ?? 0)" link @click="openGuide(row)">引导</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="loadList" />
    </el-card>

    <!-- 查看依据（单主体画像摘要） -->
    <el-drawer v-model="basisVisible" title="画像依据" size="560px" destroy-on-close>
      <div v-loading="basisLoading" class="drawer-body">
        <template v-if="basisData">
          <template v-if="basisData.found">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="姓名">{{ basisData.displayName || basisData.subjectId }}</el-descriptions-item>
              <el-descriptions-item label="身份类型">{{ basisData.subjectType }}</el-descriptions-item>
              <el-descriptions-item label="操作记录">{{ basisData.eventCount ?? '—' }} 次</el-descriptions-item>
              <el-descriptions-item label="最近活跃">{{ basisData.lastEventTime || '—' }}</el-descriptions-item>
            </el-descriptions>
            <template v-if="basisData.scored">
              <div class="basis-score">
                <div>
                  <span class="muted">总分</span>
                  <strong :class="scoreClass(Number(basisData.totalScore))">{{ formatScore(basisData.totalScore) }}</strong>
                </div>
                <div>
                  <span class="muted">规则版本</span>
                  <strong class="mono">{{ basisData.ruleVersion || '—' }}</strong>
                </div>
                <div>
                  <span class="muted">计算时间</span>
                  <strong>{{ basisData.calcTime || '—' }}</strong>
                </div>
              </div>
              <h4 class="basis-title">维度贡献</h4>
              <el-table class="ui-table" :data="basisDimensions">
                <template #empty>
                  <div class="ui-empty">
                    <p class="ui-empty__title">暂无维度得分</p>
                  </div>
                </template>
                <el-table-column label="维度" min-width="130">
                  <template #default="{ row }"
                    >{{ row.dimName || row.code }} <span class="mono muted">{{ row.code }}</span></template
                  >
                </el-table-column>
                <el-table-column label="得分" width="80" align="right">
                  <template #default="{ row }">{{ formatScore(row.score) }}</template>
                </el-table-column>
                <el-table-column label="权重" width="70" align="right">
                  <template #default="{ row }">{{ row.weight ?? '—' }}</template>
                </el-table-column>
                <el-table-column label="加权分" width="90" align="right">
                  <template #default="{ row }">{{ formatScore(row.weightedScore) }}</template>
                </el-table-column>
              </el-table>
            </template>
            <el-empty v-else-if="basisData.unconfigured" :image-size="60" description="还没配置打分口径——接入维度后到引擎运维触发一次重算，这里会出现真实评分" />
            <el-empty v-else :image-size="60" description="这个人还没有评分快照（等待事件触发重算）" />
          </template>
          <el-empty v-else :image-size="60" description="没有找到该主体（可能已被清理）" />
        </template>
      </div>
    </el-drawer>
    <el-drawer v-model="reviewVisible" title="低分复核" size="72%" destroy-on-close class="review-drawer">
      <ReviewPanel source="dcs" />
    </el-drawer>
    <!-- 题4: 接入指南常驻入口(有数据时的文案分叉) -->
    <el-drawer v-model="guideVisible" title="接入指南" size="640px" destroy-on-close>
      <AccessGuideCard variant="sharpen" />
    </el-drawer>
    <!-- 题5 动作列: 没上手的同事 → 复制一段引导话术(诚实动作, 不假装已发送) -->
    <el-dialog v-model="guideMsgVisible" :title="`引导 ${guideTargetName} 上手`" width="min(520px, 94%)" append-to-body>
      <p class="muted guide-msg-hint">下面是一段可以直接发给对方的话术（复制后通过你习惯的方式发送）。对方产生真实操作后，这里的采用度会自动更新。</p>
      <el-input type="textarea" :rows="5" :model-value="guideMessage" readonly />
      <template #footer>
        <el-button @click="guideMsgVisible = false">关闭</el-button>
        <el-button type="primary" @click="copyGuideMessage">复制话术</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="DcsProfile" lang="ts">
import {
  getEngineConfig,
  getOverview,
  getRiskBands,
  getSubjectSummary,
  pageSubjectMainView,
  updateEngineConfig,
  type ProfileMainViewRow,
  type ProfileOverview,
  type ProfileSubjectSummary
} from '@/api/dcs/core/profile';
import { checkPermi } from '@/utils/permission';
import { formatScore } from '@/utils/score';
import { useSourceEmpty, invalidateSources, ensureSources } from '@/composables/useProfileSources';
import ReviewPanel from './review/ReviewPanel.vue';
import AccessGuideCard from './AccessGuideCard.vue';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';
import DcsTableColumnSettings from '@/components/DcsTableColumnSettings/index.vue';
import { readHiddenColumns, type TableColumnOption } from '@/components/DcsTableColumnSettings/table-columns';

const sourceEmpty = useSourceEmpty('subject');
const noRealSource = computed(() => sourceEmpty.value);
const sourcesReady = ref(false);
/** 页头「刷新」/空态卡「重新检测」: 失效源缓存重拉清单(零记录指引可能解除), 数据侧整轮重载 */
const reloadAll = async () => {
  invalidateSources();
  sourcesReady.value = false;
  await ensureSources();
  sourcesReady.value = true;
  loadAll();
};
const reviewVisible = ref(false);
const guideVisible = ref(false);
const canTestSend = checkPermi(['profile:engine:query']);

const router = useRouter();
const route = useRoute();
// 批11 列级权限: 无 profile:pii:score 权限者不渲染评分列(后端同步置空)
const canSeeScore = checkPermi(['profile:pii:score']);
const canEditConfig = checkPermi(['profile:review:edit']);

// ---------------------------------------------------------------- 身份切换(题0 尾: 主列表默认员工, 保留切换)
const IDENTITY_OPTIONS = [
  { label: '员工', value: 'user' },
  { label: '成员', value: 'member' }
];
const identity = ref('user');
const identityCaption = computed(() => (identity.value === 'user' ? '本单位用户全员（含还没产生操作的人）' : '成员类主体（由业务接入产生，如顾客 / 会员）'));
const onIdentityChange = () => {
  query.pageNum = 1;
  // 三审 dspro P2: 换身份带旧关键词会把上一身份的筛选漏到新身份
  query.keyword = '';
  loadAll();
};

// ---------------------------------------------------------------- 自动记录行为开关(题1: 搬到画像页明面)
const selfMonitor = ref('1');
const selfMonSaving = ref(false);
const loadSelfMonitor = async () => {
  // 三审三席 P2: 开关只对 canEditConfig 渲染, 读配置按同一权限门控——
  // 否则只读角色每次进页必吃一次 403 通知, 失败兜底还会把开关显示成默认「开」
  if (!canEditConfig) return;
  try {
    const config = await getEngineConfig('dcs');
    selfMonitor.value = config.selfMonitor ?? '1';
  } catch {
    // 接口不可用时保持默认显示, 开关仍可尝试操作
  }
};
const onSelfMonitorChange = async (value: string | number | boolean) => {
  selfMonSaving.value = true;
  try {
    await updateEngineConfig({ source: 'dcs', selfMonitor: String(value) });
    selfMonitor.value = String(value);
    ElMessage.success(value === '1' ? '已开启：记录登录与核心操作' : '已停用：不再新增行为记录（已有数据保留）');
  } catch {
    // 失败回弹由 el-switch 的 model-value 驱动
  } finally {
    selfMonSaving.value = false;
  }
};

const loading = ref(false);
const rows = ref<ProfileMainViewRow[]>([]);
const total = ref(0);
const overview = ref<Partial<ProfileOverview>>({});

// 列显隐（按页面存 localStorage；用户/动作列常显）
const COLUMN_STORAGE_KEY = 'profile-mainview';
const columnOptions = computed<TableColumnOption[]>(() => {
  const options: TableColumnOption[] = [];
  if (identity.value === 'user') options.push({ key: 'dept', label: '部门' });
  options.push({ key: 'lastEvent', label: '最近活跃' }, { key: 'adoption', label: '采用度' });
  if (canSeeScore) options.push({ key: 'score', label: '评分' });
  options.push({ key: 'status', label: '状态' });
  return options;
});
const hiddenColumns = ref<string[]>(readHiddenColumns(COLUMN_STORAGE_KEY));
const isColumnVisible = (key: string) => !hiddenColumns.value.includes(key);

const query = reactive({
  pageNum: 1,
  pageSize: 20,
  keyword: ''
});

/** 风险分档阈值（后端下发，前端不再硬编码） */
const riskBands = reactive({ mediumMin: 60, highBelow: 60, lowMin: 75 });
const loadRiskBands = async () => {
  try {
    const bands = await getRiskBands();
    riskBands.mediumMin = Number(bands.mediumMin);
    riskBands.highBelow = Number(bands.highBelow);
    riskBands.lowMin = Number(bands.lowMin);
  } catch {
    // 接口不可用时退回默认 60/75
  }
};

const clamp = (value: number) => Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0));
const scoreColor = (value: number) =>
  value >= riskBands.lowMin ? 'var(--el-color-success)' : value >= riskBands.highBelow ? 'var(--el-color-warning)' : 'var(--el-color-danger)';
const scoreClass = (value: number) => (value >= riskBands.lowMin ? 'dim-good' : value >= riskBands.highBelow ? 'dim-mid' : 'dim-low');

const fmtTime = (v?: string | null) => (v ? String(v).replace('T', ' ').slice(0, 16) : '—');

// KPI 带: 「有记录的人」口径=画像主体数(与列表全员数不同, 避免同一事实两处数字打架)
const riskTone = (v: number | null | undefined, tone: 'warning' | 'danger') => Number(v ?? 0) > 0 ? tone : undefined;
const overviewMetrics = computed(() => [
  { label: '有记录的人', value: overview.value.subjectCount ?? '—' },
  { label: '平均分', value: formatScore(overview.value.avgScore) },
  { label: '关注', value: overview.value.mediumRisk ?? '—', tone: riskTone(overview.value.mediumRisk, 'warning') },
  { label: '高风险', value: overview.value.highRisk ?? '—', tone: riskTone(overview.value.highRisk, 'danger') }
]);

const loadOverview = async () => {
  overview.value = await getOverview('dcs', identity.value);
};
let listSeq = 0;
const loadList = async () => {
  // 三审 dspro P2: 请求栅栏——身份快速来回切时, 后到的旧身份响应不得覆盖新身份的列表
  const seq = ++listSeq;
  loading.value = true;
  try {
    const res = await pageSubjectMainView({
      subjectType: identity.value,
      keyword: query.keyword || undefined,
      pageNum: query.pageNum,
      pageSize: query.pageSize
    });
    if (seq !== listSeq) return;
    rows.value = res.rows;
    total.value = res.total;
  } finally {
    if (seq === listSeq) loading.value = false;
  }
};
const loadAll = async () => {
  await Promise.all([loadOverview(), loadList(), loadRiskBands(), loadSelfMonitor()]);
  // 三审 dspro P2: 载完才置已载——首载失败时 keep-alive 回页自愈还能再触发
  loadedOnce = true;
};
const search = () => {
  query.pageNum = 1;
  loadList();
};
const resetQuery = () => {
  query.keyword = '';
  query.pageNum = 1;
  loadList();
};
const goDetail = (row: ProfileMainViewRow) =>
  router.push(`/insight/profile-detail/dcs/${row.subjectId}?subjectType=${encodeURIComponent(row.subjectType || 'user')}`);

// ---------------------------------------------------------------- 查看依据
const basisVisible = ref(false);
const basisLoading = ref(false);
const basisData = ref<ProfileSubjectSummary | null>(null);
const basisDimensions = computed(() => {
  const detail = basisData.value?.dimensionDetail;
  if (!detail) return [];
  return Object.entries(detail).map(([code, entry]) => ({
    code,
    dimName: entry.dimName,
    score: entry.normalizedScore ?? entry.score,
    weight: entry.weight,
    weightedScore: entry.weightedScore,
    skipped: entry.skipped
  }));
});
const openBasis = async (row: ProfileMainViewRow) => {
  basisVisible.value = true;
  basisLoading.value = true;
  basisData.value = null;
  try {
    basisData.value = await getSubjectSummary(row.subjectId, row.subjectType || 'user');
  } finally {
    basisLoading.value = false;
  }
};

// ---------------------------------------------------------------- 引导话术(动作列: 还没用的同事)
const guideMsgVisible = ref(false);
const guideTargetName = ref('');
const guideMessage = computed(() =>
  `${guideTargetName.value}，你好！你的账号已经开通。登录系统后，首页就能看到分配给你的任务；发布、提交、审核这些日常操作会自动沉淀为使用记录。有任何问题随时找我。`
);
const openGuide = (row: ProfileMainViewRow) => {
  guideTargetName.value = String(row.displayName || row.userName || row.subjectId);
  guideMsgVisible.value = true;
};
const copyGuideMessage = async () => {
  try {
    await navigator.clipboard.writeText(guideMessage.value);
    ElMessage.success('话术已复制');
  } catch {
    ElMessage.warning('复制失败——请手动选择文本复制');
  }
};

onMounted(async () => {
  // 清单回来后才亮内容: 先知道有没有记录, 再决定零记录指引显不显
  await ensureSources();
  sourcesReady.value = true;
  // 三审 dspro/dsflash P1: 主列表是 sys_user 全员名册, 零记录也要加载——「谁还没来」正是这页的名单
  loadAll();
  // 旧「低分复核」菜单/总览页跳转的深链兜底: ?review=1 自动开抽屉
  if (route.query.review === '1' && checkPermi(['profile:review:query'])) reviewVisible.value = true;
});

// keep-alive 回页自愈: 测试发送产生新数据后零记录指引解除, 补一轮加载
let loadedOnce = false;
onActivated(async () => {
  await ensureSources();
  sourcesReady.value = true;
  if (!loadedOnce) loadAll();
});

// keep-alive 下二次导航同路由只变 query, onMounted/watch 都不会再跑
watch(
  () => route.query.review,
  (v) => {
    if (v === '1' && checkPermi(['profile:review:query'])) reviewVisible.value = true;
  }
);
</script>

<style scoped>
.profile-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.profile-page :deep(.dcs-ops-header) {
  margin-bottom: 4px;
}
/* 语义色只在本页生效：关注/高风险是唯一「要人动手」的两个数。
   写成全局规则会连带染上所有复用页头的页面（累计调用、待支付这类中性量） */
.profile-page :deep(.dcs-ops-metric--warning .dcs-ops-metric__value) {
  color: var(--el-color-warning);
}
.profile-page :deep(.dcs-ops-metric--danger .dcs-ops-metric__value) {
  color: var(--el-color-danger);
}
.profile-page :deep(.dcs-ops-header__actions) {
  flex-wrap: wrap;
}
.identity-select {
  width: 96px;
}
/* 自动记录开关: 页头明面的一行式开关(题1), 安静样式不抢主按钮 */
.selfmon-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  height: 32px;
  border: 1px solid var(--app-rule);
  border-radius: var(--app-control-radius, 6px);
}
.selfmon-toggle__label {
  font-size: 13px;
  color: var(--app-muted);
  white-space: nowrap;
}
.table-card :deep(.el-card__body) {
  padding-top: 6px;
  min-width: 0;
}
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  min-width: 0;
}
.list-head__caption {
  font-size: 12px;
}
/* el-table 走 CSS 变量：表头无底带、行线走令牌、正文灰阶（不重写组件） */
.profile-page :deep(.el-table) {
  --el-table-border-color: var(--app-rule);
  --el-table-header-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--app-raised);
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
  min-width: 0;
}
/* 查询/重置/列设置作为一个整体换行：窄屏时要么整组在行尾，要么整组掉下一行 */
.filter-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
@media (max-width: 992px) {
  .filter-actions {
    margin-left: 0;
  }
}
.subject-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.35;
}
.subject-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
  line-height: 1.4;
  display: flex;
  align-items: center;
  gap: 6px;
}
.subject-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.35;
}
/* 采用度: 数字等宽右对齐, 单位是更小的灰后缀 */
.count-number {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.count-unit {
  font-size: 12px;
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
.muted {
  color: var(--el-text-color-placeholder);
}
.mono {
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
}
.drawer-body {
  min-height: 160px;
}
.basis-score {
  display: flex;
  gap: 28px;
  margin: 14px 0;
}
.basis-score > div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.basis-score strong {
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}
.basis-title {
  margin: 12px 0 8px;
  font-size: 14px;
}
.dim-good {
  color: var(--el-color-success);
  font-weight: 600;
}
.dim-mid {
  color: var(--el-color-warning);
  font-weight: 600;
}
.dim-low {
  color: var(--el-color-danger);
  font-weight: 600;
}
.guide-msg-hint {
  font-size: 13px;
  line-height: 1.7;
  margin: 0 0 10px;
}

.empty-guide {
  text-align: center;
  padding: 18px 0 6px;
}
/* 空态卡里嵌接入示例卡(左对齐块), 脚注行恢复左对齐 */
.empty-guide :deep(.access-guide) {
  text-align: left;
  border: 0;
  box-shadow: none;
}
.empty-guide__foot {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  flex-wrap: wrap;
}
.empty-guide__title {
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 6px;
}
.empty-guide__hint {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.empty-guide__text {
  max-width: 560px;
  margin: 0 auto 14px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
}
</style>
