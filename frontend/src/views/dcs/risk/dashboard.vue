<template>
  <div class="dcs-ops-page risk-dashboard">
    <DcsOperationsPageHeader
      title="风控大盘"
      subtitle="内容机检与文件防线的运转情况；风险评分页签提供画像视角与历史事件统计。"
      eyebrow="风控与审计"
      :metrics="headerMetrics"
    >
      <template #actions>
        <el-button :icon="Refresh" @click="loadDashboard">刷新</el-button>
      </template>
    </DcsOperationsPageHeader>

    <!-- ═══ 风控大盘 2.0 双页签(拍板 Q1-Q6 全A, 2026-09-23): 内容风控(默认)/风险评分 分家,
         切割由页签承担; ?tab= 直达+localStorage 记忆; 历史区能力零丢失只换房 ═══ -->
    <el-tabs v-model="activeTab" class="risk-tabs" @tab-change="onTabChange">
      <el-tab-pane label="内容风控" name="content">
        <!-- 总开关未开: 页面顶部提示, 不藏数据(下方历史区照常) -->
        <el-alert
          v-if="defense && !defense.contentModerationEnabled"
          type="info"
          show-icon
          :closable="false"
          class="mb-4"
          title="内容机审未启用：当前不会检测新的任务提交内容（表单/在线批量/Excel 导入），下方仅统计历史事件。灰度开关由系统配置开启后生效。"
        />

        <!-- 行动横幅区: 灰度观察/拦截用文字药丸表达语义, 不再用彩色边条与阴影 -->
        <div class="banner-row mb-4">
          <button type="button" class="banner" @click="router.push('/risk/events')">
            <el-icon class="banner-icon"><CircleCloseFilled /></el-icon>
            <div class="banner-main">
              <div class="banner-title">
                <template v-if="blockEnabled"
                  >今日拦截提交 <b class="banner-num">{{ todayBlocked }}</b> 条</template
                >
                <template v-else
                  >今日阻断级命中 <b class="banner-num">{{ todayBlocked }}</b> 条</template
                >
                <span class="banner-num-sep">·</span>
                复核级命中 <b class="banner-num">{{ todayReview }}</b> 条
                <span class="ui-pill" :class="blockEnabled ? 'ui-pill--neg' : 'ui-pill--warn'">
                  {{ blockEnabled ? '拦截已启用' : '观察模式·只记录不拦截' }}
                </span>
              </div>
              <div class="banner-sub">任务内容机检：表单填写 / 在线批量 / Excel 导入命中词库即留痕</div>
            </div>
            <span class="banner-go"
              >查看事件流水 <el-icon><ArrowRight /></el-icon
            ></span>
          </button>
          <button type="button" class="banner" @click="router.push('/dcs/file-review')">
            <el-icon class="banner-icon"><CircleCheckFilled /></el-icon>
            <div class="banner-main">
              <div class="banner-title">
                文件防线运行中
                <span class="ui-pill ui-pill--pos">生效</span>
              </div>
              <div class="banner-sub">fileguard 结构检测拒险于传 + 文件审核先审后可见</div>
            </div>
            <span class="banner-go"
              >去文件审核 <el-icon><ArrowRight /></el-icon
            ></span>
          </button>
        </div>

        <!-- 主区 3fr + 右轨 2fr -->
        <el-row :gutter="12">
          <el-col :xs="24" :span="15">
            <!-- 内容风险来源分布: 纯 CSS 横条(不引入新图表) -->
            <el-card shadow="never" class="mb-4">
              <template #header>
                <div class="card-header">
                  <span>内容风险来源分布</span>
                  <span class="ui-pill ui-pill--muted">近 7 天</span>
                </div>
              </template>
              <div v-if="sourceStats.length" class="source-list">
                <div v-for="item in sourceStats" :key="item.source" class="source-row">
                  <span class="source-name">{{ EVENT_SOURCE_LABELS[item.source] || item.source }}</span>
                  <div class="source-track">
                    <div class="source-bar" :style="{ width: sourcePercent(item.cnt) + '%' }"></div>
                  </div>
                  <span class="source-cnt ui-num">{{ item.cnt }} 次</span>
                </div>
              </div>
              <el-empty v-else description="近 7 天暂无内容风险事件（机检未命中或未启用）" :image-size="80" />
            </el-card>

            <!-- 近期内容风险事件: 只看 TASK_CONTENT_RISK, 词面打码 -->
            <el-card shadow="never">
              <template #header>
                <div class="card-header">
                  <span>近期内容风险事件</span>
                  <div class="card-actions">
                    <span class="ui-pill ui-pill--muted">{{ contentRiskEvents.length }} 条</span>
                    <DcsTableColumnSettings v-model="hiddenEventColumns" :columns="eventColumnOptions" storage-key="risk-dashboard-events" />
                    <el-button link @click="router.push('/risk/events')">全部</el-button>
                  </div>
                </div>
              </template>
              <div class="dcs-ops-table-wrap">
                <el-table :data="contentRiskEvents" class="ui-table risk-table">
                  <el-table-column v-if="isEventColumnVisible('time')" label="时间" width="96">
                    <template #default="{ row }">{{ formatTime(row.createdTime) }}</template>
                  </el-table-column>
                  <el-table-column v-if="isEventColumnVisible('source')" label="入口" width="96">
                    <template #default="{ row }">
                      <span class="ui-pill ui-pill--muted">{{ EVENT_SOURCE_LABELS[row.source] || row.source }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column v-if="isEventColumnVisible('action')" label="动作" width="82">
                    <template #default="{ row }">
                      <span class="ui-pill" :class="row.detail?.startsWith('内容机检:命中BLOCK') ? 'ui-pill--neg' : 'ui-pill--warn'">
                        {{ row.detail?.startsWith('内容机检:命中BLOCK') ? '阻断级' : '复核级' }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column v-if="isEventColumnVisible('detail')" label="说明" min-width="180" show-overflow-tooltip>
                    <template #default="{ row }">{{ maskSensitiveDetail(row.detail || '-') }}</template>
                  </el-table-column>
                  <el-table-column v-if="isEventColumnVisible('task')" label="任务" width="76">
                    <template #default="{ row }">
                      <el-link v-if="row.refId" :underline="false" @click="gotoMission(row.refId)">查看</el-link>
                      <span v-else>-</span>
                    </template>
                  </el-table-column>
                  <template #empty>
                    <div class="ui-empty">
                      <p class="ui-empty__title">暂无内容风险事件</p>
                      <p class="ui-empty__hint">机检未命中或内容机检未启用时，这里不会有记录</p>
                    </div>
                  </template>
                </el-table>
              </div>
            </el-card>
          </el-col>

          <el-col :xs="24" :span="9">
            <!-- TOP 命中词: 词面打码(防肩窥); U03 误报率=同窗该词被判误报次数/命中次数 -->
            <el-card shadow="never" class="mb-4">
              <template #header>
                <div class="card-header">
                  <span>TOP 命中词</span>
                  <span class="ui-pill ui-pill--muted">近 7 天</span>
                </div>
              </template>
              <div v-if="topHitWords.length" class="word-rank">
                <div v-for="(item, idx) in topHitWords" :key="item.word" class="word-rank-row">
                  <span class="rank-no">{{ idx + 1 }}</span>
                  <span class="word-text">{{ maskToken(item.word) }}</span>
                  <span v-if="item.fpCnt" class="ui-pill ui-pill--warn" :title="`命中 ${item.cnt} 次，其中被判误报 ${item.fpCnt} 次`"
                    >误报 {{ item.fpCnt }}/{{ item.cnt }}</span
                  >
                  <span class="word-cnt ui-num">{{ item.cnt }} 次</span>
                </div>
              </div>
              <el-empty v-else description="近 7 天暂无命中词" :image-size="80" />
            </el-card>

            <!-- 防线状态: 开关直读, 三态语义色 -->
            <el-card shadow="never">
              <template #header>
                <span>防线状态</span>
              </template>
              <div class="defense-list">
                <div class="defense-row">
                  <div class="defense-name">
                    表单/导入内容机检
                    <div class="defense-sub">表单填写·在线批量·Excel 导入</div>
                  </div>
                  <span v-if="!defense?.contentModerationEnabled" class="ui-pill ui-pill--muted">未启用</span>
                  <span v-else-if="blockEnabled" class="ui-pill ui-pill--neg">已启用·拦截</span>
                  <span v-else class="ui-pill ui-pill--warn">灰度观察</span>
                </div>
                <div class="defense-row">
                  <div class="defense-name">
                    fileguard 结构检测
                    <div class="defense-sub">扩展名/魔数/深扫，拒险于传</div>
                  </div>
                  <span class="ui-pill ui-pill--pos">生效</span>
                </div>
                <div class="defense-row">
                  <div class="defense-name">
                    文件审核（先审后可见）
                    <div class="defense-sub">审核通过前他人不可见</div>
                  </div>
                  <span class="ui-pill ui-pill--pos">生效</span>
                </div>
                <div class="defense-row">
                  <div class="defense-name">
                    聊天机审
                    <div class="defense-sub">聊天前端已下线，历史事件保留可查</div>
                  </div>
                  <span class="ui-pill ui-pill--muted">已暂停</span>
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </el-tab-pane>

      <el-tab-pane label="风险评分" name="score">
        <!-- ═══ 历史区: 风险评分与历史事件(旧能力保留, 替换红线·不倒退) ═══ -->
        <!-- 行动条: 低分复核入口在批3 接入新规则等级后恢复, 当前先移除死链 404 -->
        <div class="action-row mb-4">
          <button v-hasPermi="['dcs:risk:action:list']" type="button" class="action-bar" @click="router.push('/risk/actions')">
            <el-icon class="action-icon"><Lock /></el-icon>
            <div class="action-main">
              <div class="action-title">
                安全处置生效 <b class="action-num">{{ activeActions }}</b> 例
                <span class="ui-pill" :class="activeActions > 0 ? 'ui-pill--warn' : 'ui-pill--pos'">
                  {{ activeActions > 0 ? '处置中' : '无处置' }}
                </span>
              </div>
              <div class="action-sub">L3/L4 触发的增强验证或临时冻结</div>
            </div>
            <span class="action-go"
              >去处置 <el-icon><ArrowRight /></el-icon
            ></span>
          </button>
        </div>

        <el-row :gutter="12">
          <!-- 左:图表 -->
          <el-col :xs="24" :span="14">
            <el-card shadow="never" class="mb-4">
              <template #header>
                <div class="card-header">
                  <span>事件类型分布({{ timeRangeLabel }})</span>
                  <div class="card-actions">
                    <el-radio-group v-model="timeRange" @change="loadDashboard">
                      <el-radio-button label="24h">24小时</el-radio-button>
                      <el-radio-button label="7d">7天</el-radio-button>
                      <el-radio-button label="30d">30天</el-radio-button>
                      <el-radio-button label="all">全部</el-radio-button>
                    </el-radio-group>
                    <el-button link :icon="Refresh" @click="loadDashboard">刷新</el-button>
                  </div>
                </div>
              </template>
              <!-- 空态不再渲染灰环(易误读为 100% 占比), 统一为 el-empty -->
              <div v-show="hasTypeDist" ref="typeChartRef" class="chart-box" style="height: 280px"></div>
              <el-empty v-if="!hasTypeDist" description="该时间窗内暂无事件" :image-size="80" class="chart-empty" />
            </el-card>

            <el-card shadow="never">
              <template #header>
                <span>风险等级分布({{ timeRangeLabel }} 用户数)</span>
              </template>
              <div class="level-grid">
                <div v-for="lvl in ['L0', 'L1', 'L2', 'L3', 'L4']" :key="lvl" class="level-cell">
                  <span class="ui-pill" :class="levelPillClass(lvl)">{{ lvl }}</span>
                  <span class="cell-cnt">{{ levelCount(lvl) }}</span>
                  <span class="cell-label">{{ levelLabel(lvl) }}</span>
                </div>
              </div>
            </el-card>

            <!-- B4: 策略命中排行 TOP10（按事件类型聚合，业务调优参考） -->
            <el-card shadow="never" class="mt-3">
              <template #header>
                <div class="card-header">
                  <span>策略命中排行 TOP10</span>
                  <span class="ui-pill ui-pill--muted">{{ timeRangeLabel }}</span>
                </div>
              </template>
              <div v-if="topRules.length" class="rank-list">
                <div v-for="(item, idx) in topRules" :key="item.eventType" class="rank-item">
                  <span class="rank-no">{{ idx + 1 }}</span>
                  <span class="rank-name">{{ EVENT_TYPE_LABELS[item.eventType] || item.eventType }}</span>
                  <el-progress
                    :percentage="rankPercentage(item.cnt)"
                    :show-text="false"
                    :stroke-width="8"
                    :color="rankColor(idx)"
                    class="rank-progress"
                  />
                  <span class="rank-cnt ui-num">{{ item.cnt }} 次</span>
                </div>
              </div>
              <el-empty v-else description="该时间窗内暂无事件" :image-size="80" />
            </el-card>
          </el-col>

          <!-- 右:近期事件流(数据仅在刷新/切窗时更新, 非"实时"推送) -->
          <el-col :xs="24" :span="10">
            <el-card shadow="never" class="event-stream-card">
              <template #header>
                <div class="card-header">
                  <span>近期风险事件</span>
                  <!-- 0 条是正常态, 用中性药丸, 不用 danger 红制造紧张感 -->
                  <span class="ui-pill ui-pill--muted">{{ recentEvents.length }} 条</span>
                </div>
              </template>
              <div class="event-stream">
                <div v-for="ev in recentEvents" :key="ev.eventId" class="event-row">
                  <span class="ui-pill ui-pill--muted">
                    {{ EVENT_TYPE_LABELS[ev.eventType] || ev.eventType }}
                  </span>
                  <div class="event-body">
                    <div class="event-detail">{{ ev.detail || '-' }}</div>
                    <div class="event-meta">
                      <span>{{ ev.nickName || ev.userName || '用户 ' + (ev.userId || '?') }}</span>
                      <span v-if="ev.ip"> · IP {{ ev.ip }}</span>
                      <span class="event-score"> · +{{ ev.riskScore }}分</span>
                    </div>
                  </div>
                  <div class="event-time">{{ formatTime(ev.createdTime) }}</div>
                </div>
                <el-empty v-if="!recentEvents.length" description="暂无风险事件" :image-size="80" />
              </div>
            </el-card>
          </el-col>
        </el-row>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import * as echarts from 'echarts';
import { Lock, ArrowRight, Refresh, CircleCloseFilled, CircleCheckFilled } from '@element-plus/icons-vue';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';
import type { DcsOperationsMetric } from '@/components/DcsOperationsPageHeader/index.vue';
import DcsTableColumnSettings from '@/components/DcsTableColumnSettings/index.vue';
import { readHiddenColumns, type TableColumnOption } from '@/components/DcsTableColumnSettings/table-columns';
import { getDashboard, type DashboardVO, type RiskEventVO, EVENT_TYPE_LABELS, EVENT_SOURCE_LABELS } from '@/api/dcs/risk';
import { safeLoad } from '../utils/safeLoad';
import { formatRiskRelativeTime } from './riskDashboardPresentation';
import { maskSensitiveDetail, maskToken } from './sensitiveMask';
import { useChartTokens, alpha } from '@/utils/chart-theme';
import { useRoute, useRouter } from 'vue-router';

defineOptions({ name: 'DcsRiskDashboard' });

const dashboard = ref<DashboardVO>({} as DashboardVO);
const router = useRouter();

// 交互三件 ③：近期内容风险事件列显隐（按页面存 localStorage）
const EVENT_COLUMN_STORAGE_KEY = 'risk-dashboard-events';
const eventColumnOptions: TableColumnOption[] = [
  { key: 'time', label: '时间' },
  { key: 'source', label: '入口' },
  { key: 'action', label: '动作' },
  { key: 'detail', label: '说明' },
  { key: 'task', label: '任务' }
];
const hiddenEventColumns = ref<string[]>(readHiddenColumns(EVENT_COLUMN_STORAGE_KEY));
const isEventColumnVisible = (key: string) => !hiddenEventColumns.value.includes(key);

/** 机检事件深链(收尾批 Q8, 2026-09-23): refId=missionId 跳任务详情, 与事件流水弹窗同款 */
const gotoMission = (missionId: number | string) => {
  router.push({ name: 'DcsMissionDetail', params: { id: String(missionId) } });
};
const route = useRoute();

// ── 双页签(拍板 Q1/Q3 A, 2026-09-23): ?tab= 直达 > localStorage 记忆 > 默认内容风控 ──
type RiskTab = 'content' | 'score';
const TAB_STORAGE_KEY = 'risk-dashboard-tab';
const activeTab = ref<RiskTab>('content');
const initTab = () => {
  const q = route.query.tab;
  if (q === 'content' || q === 'score') {
    activeTab.value = q;
    return;
  }
  const saved = localStorage.getItem(TAB_STORAGE_KEY);
  if (saved === 'content' || saved === 'score') activeTab.value = saved;
};
const onTabChange = (name: string | number) => {
  const tab = String(name);
  localStorage.setItem(TAB_STORAGE_KEY, tab);
  router.replace({ query: { ...route.query, tab } });
  // 图表容器在隐藏页签里 offsetWidth=0 被 renderTypeChart 的守卫跳过, 切到本页签后补渲
  if (tab === 'score') nextTick(() => renderTypeChart());
};
const recentEvents = ref<RiskEventVO[]>([]);
const typeChartRef = ref<HTMLDivElement | null>(null);
let typeChart: echarts.ECharts | null = null;
// Req 7.1: 时间范围筛选(历史区图表沿用)
const timeRange = ref<'24h' | '7d' | '30d' | 'all'>('24h');
const TIME_RANGE_LABELS: Record<string, string> = { '24h': '24小时', '7d': '7天', '30d': '30天', all: '全部' };
const timeRangeLabel = computed(() => TIME_RANGE_LABELS[timeRange.value] || '24小时');

// ── 风控2.0 方案一: 内容风控主轴数据(2026-09-22) ──
const todayBlocked = computed(() => dashboard.value.contentTodayStats?.blocked || 0);
const todayReview = computed(() => dashboard.value.contentTodayStats?.review || 0);
const fileRejected7d = computed(() => dashboard.value.fileRejected7d || 0);
/** 误报反馈(批2): 审核人在任务明细判误报的累计行数(machine_check='FP' 聚合, 非事件口径) */
const falseFeedbackCount = computed(() => dashboard.value.falseFeedbackCount || 0);
const defense = computed(() => dashboard.value.defenseStatus);
// 阻断拦截开关: true=命中阻断词的提交被拒绝保存; false=观察模式只记录
const blockEnabled = computed(() => !!defense.value?.contentBlockEnabled);
// 近期事件流里过滤出内容机检事件(主区"近期内容风险事件"数据源)
const contentRiskEvents = computed(() => recentEvents.value.filter((ev) => ev.eventType === 'TASK_CONTENT_RISK'));
// 来源分布横条: 按 cnt 降序, 宽度相对最大值(最小 4% 保证可见)
const sourceStats = computed(() => [...(dashboard.value.sourceStats7d || [])].sort((a, b) => b.cnt - a.cnt));
const sourcePercent = (cnt: number) => {
  const max = sourceStats.value.reduce((m, s) => Math.max(m, s.cnt), 0);
  return max > 0 ? Math.max(Math.round((cnt / max) * 100), 4) : 0;
};
// TOP 命中词(后端已按次数降序)
const topHitWords = computed(() => dashboard.value.topHitWords7d || []);

const totalRiskUsers = computed(() => {
  return (dashboard.value.levelStats || []).filter((l) => l.lvl !== 'L0').reduce((s, l) => s + l.cnt, 0);
});
const highRiskUsers = computed(() => {
  return (dashboard.value.levelStats || []).filter((l) => l.lvl === 'L3' || l.lvl === 'L4').reduce((s, l) => s + l.cnt, 0);
});

// 行动条: 生效处置数(低分复核入口待批3 接入新规则等级后恢复)
const activeActions = computed(() => dashboard.value.activeActions || 0);

const levelCount = (lvl: string) => (dashboard.value.levelStats || []).find((l) => l.lvl === lvl)?.cnt || 0;

/**
 * B4: 策略命中排行 TOP10（从 dashboard.topRules 读取，后端按事件类型聚合）。
 * 业务用途：业务人员据此判断哪些事件类型最频繁，对应规则是否需要调权重/阈值。
 */
const topRules = computed(() => dashboard.value.topRules || []);
const maxRankCount = computed(() => topRules.value.reduce((max, r) => Math.max(max, r.cnt), 0));
const rankPercentage = (cnt: number) => (maxRankCount.value > 0 ? Math.round((cnt / maxRankCount.value) * 100) : 0);
// echarts/el-progress 无法消费 CSS 变量，统一从 chart-theme 取（主题切换自动重算）
const ct = useChartTokens();
const rankColor = (idx: number) => {
  if (idx === 0) return ct.value.accent;
  if (idx === 1) return alpha(ct.value.accent, 0.6);
  if (idx === 2) return alpha(ct.value.accent, 0.4);
  // 第 4 名往后用中性灰：--app-rule 是分隔线色，压在卡片底上几乎看不见
  return ct.value.muted;
};
const levelLabel = (lvl: string) => {
  const m: Record<string, string> = { L0: '正常', L1: '低风险', L2: '中风险', L3: '高风险', L4: '极高危' };
  return m[lvl] || '';
};
/** 风险等级药丸：只有四种形态，L1 中性、L2 警告、L3/L4 危险、L0 成功 */
const levelPillClass = (lvl: string) => {
  if (lvl === 'L0') return 'ui-pill--pos';
  if (lvl === 'L2') return 'ui-pill--warn';
  if (lvl === 'L3' || lvl === 'L4') return 'ui-pill--neg';
  return 'ui-pill--muted';
};

/** 页头指标带：内容风控页签看今日机检，风险评分页签看历史事件与等级分布 */
const headerMetrics = computed<DcsOperationsMetric[]>(() => {
  if (activeTab.value === 'score') {
    return [
      { label: '总事件数', value: dashboard.value.totalEvents || 0, hint: '历史累计，不随时间窗过滤' },
      { label: 'L1+ 用户数', value: totalRiskUsers.value, hint: `${timeRangeLabel.value} · 低风险及以上的用户` },
      { label: 'L3+ 高危用户', value: highRiskUsers.value, hint: `${timeRangeLabel.value} · 高风险及以上，需重点关注` }
    ];
  }
  const blockHint = blockEnabled.value
    ? '命中阻断词的提交已被拒绝保存'
    : todayBlocked.value > 0
      ? `命中阻断词的提交·观察模式未拦截；开启拦截后此类 ${todayBlocked.value} 条将被拒绝`
      : '命中阻断词的提交·观察模式未拦截';
  return [
    { label: `${blockEnabled.value ? '拦截提交' : '阻断级命中'}（今日）`, value: todayBlocked.value, hint: blockHint },
    { label: '复核级命中（今日）', value: todayReview.value, hint: '命中复核词的提交·仅留痕不拦截' },
    { label: '文件驳回（近 7 天）', value: fileRejected7d.value, hint: '文件审核驳回事件·接入前为 0' },
    { label: '误报反馈（累计）', value: falseFeedbackCount.value, hint: '审核人在任务明细判误报·用于校准词库' }
  ];
});

const formatTime = (t?: string) => formatRiskRelativeTime(t);

const hasTypeDist = computed(() => (dashboard.value.eventTypeStats || []).some((t) => (t.cnt || 0) > 0));

// 快速切窗竞态守卫(2026-08-24 挂账清零): 序号不匹配的过期响应直接丢弃, 防止界面停留在A窗标签显示B窗数据
let loadSeq = 0;
const loadDashboard = async () => {
  const seq = ++loadSeq;
  const r: any = await safeLoad(() => getDashboard(timeRange.value), { errorMsg: '风控大盘加载失败' });
  if (seq !== loadSeq) return;
  if (r && r.code === 200 && r.data) {
    dashboard.value = r.data;
    recentEvents.value = r.data.recentEvents || [];
    renderTypeChart();
  }
};

const renderTypeChart = async () => {
  // K3复审D1修正: dashboard 赋值后 v-show 需等一次 nextTick 才把图表容器显示出来,
  // 同步执行时 offsetWidth=0 会提前 return 导致有数据也不渲染; 空窗→有数据切换同样依赖此修复
  await nextTick();
  if (!hasTypeDist.value) {
    typeChart?.clear();
    return;
  }
  if (!typeChartRef.value || typeChartRef.value.offsetWidth === 0) return;
  if (!typeChart) typeChart = echarts.init(typeChartRef.value);
  const data = (dashboard.value.eventTypeStats || []).map((t) => ({
    name: EVENT_TYPE_LABELS[t.eventType] || t.eventType,
    value: t.cnt
  }));
  // 风险等级本身是有语义的：红=高危、黄=中危、墨色=常规，不套用单一数据色
  const COLORS: Record<string, string> = {
    SENSITIVE_WORD: ct.value.warning,
    REPORTED: ct.value.danger,
    LOGIN_FAIL: ct.value.warning,
    HIGH_FREQ_API: ct.value.accent,
    UPLOAD_ABUSE: ct.value.muted,
    TASK_CONTENT_RISK: ct.value.warning,
    FILE_REJECTED: ct.value.danger
  };
  const colors = (dashboard.value.eventTypeStats || []).map((t) => COLORS[t.eventType] || ct.value.accent);
  typeChart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} 次 ({d}%)' },
    legend: { bottom: 0, type: 'scroll', textStyle: { color: ct.value.muted } },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['50%', '45%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 6, borderColor: ct.value.surface, borderWidth: 2 },
        label: { show: true, formatter: '{b}\n{c}次', color: ct.value.muted },
        data,
        color: colors
      }
    ]
  });
};

// 切主题后重画饼图（canvas 读不到 CSS 变量，必须按新 token 重建 option）
watch(
  () => ct.value,
  () => renderTypeChart()
);

const resizeObserver = ref<ResizeObserver | null>(null);
onMounted(() => {
  initTab();
  loadDashboard();
  resizeObserver.value = new ResizeObserver(() => {
    if (typeChart) typeChart.resize();
  });
  if (typeChartRef.value) resizeObserver.value.observe(typeChartRef.value);
});
onUnmounted(() => {
  typeChart?.dispose();
  resizeObserver.value?.disconnect();
});
</script>

<style scoped lang="scss">
.risk-dashboard {
  :deep(.el-card__body) {
    padding: 16px;
  }

  :deep(.el-card__header) {
    padding: 14px 16px;
  }

  :deep(.el-tabs__content),
  :deep(.el-tab-pane) {
    min-width: 0;
  }
}

/* ── 双页签: 切割由页签承担; content 容器放开 overflow 防 tooltip 被裁 ── */
.risk-tabs {
  :deep(.el-tabs__header) {
    margin-bottom: 16px;
  }
  :deep(.el-tabs__content) {
    overflow: visible;
    min-width: 0;
  }
}

/* 表格只覆盖 Element Plus 变量与行线，不重写组件内部结构 */
.risk-table {
  --el-table-border-color: var(--app-rule);
  --el-table-header-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-row-hover-bg-color: var(--app-raised);
}

/* ── 行动横幅: 中性细边容器, 语义靠文字药丸表达 ── */
.banner-row {
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: 12px;
}
.banner {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-card-radius);
  background: var(--app-surface);
  text-align: left;
  cursor: pointer;
  transition: border-color 160ms ease;

  &:hover {
    border-color: var(--app-control);
  }
}
.banner-icon {
  color: var(--app-faint);
  font-size: 22px;
}
.banner-main {
  flex: 1;
  min-width: 0;
}
.banner-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  color: var(--app-text);
  font-size: 14px;
  font-weight: 600;
}
.banner-num {
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}
.banner-num-sep {
  color: var(--app-faint);
  font-weight: 400;
}
.banner-sub {
  margin-top: 2px;
  color: var(--app-muted);
  font-size: 12px;
}
.banner-go {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--app-muted);
  font-size: 13px;
  white-space: nowrap;
}
.banner:hover .banner-go {
  color: var(--app-text);
}

/* ── 来源分布 CSS 横条 ── */
.source-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.source-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.source-name {
  flex-shrink: 0;
  width: 76px;
  font-size: 13px;
  color: var(--app-text);
  text-align: right;
}
.source-track {
  flex: 1;
  min-width: 0;
  height: 8px;
  border-radius: var(--dcs-radius-sm);
  background: var(--app-raised);
  overflow: hidden;
}
.source-bar {
  height: 100%;
  border-radius: var(--dcs-radius-sm);
  background: var(--app-accent);
  transition: width 0.3s ease;
}
.source-cnt {
  flex-shrink: 0;
  width: 56px;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text);
  font-variant-numeric: tabular-nums;
}

/* ── TOP 命中词排行 ── */
.word-rank {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.word-rank-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 0;
}
.word-text {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--app-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.word-cnt {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text);
  font-variant-numeric: tabular-nums;
}

/* ── 防线状态卡 ── */
.defense-list {
  display: flex;
  flex-direction: column;
}
.defense-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--app-rule);

  &:last-child {
    border-bottom: none;
  }
}
.defense-name {
  font-size: 13px;
  color: var(--app-text);
  font-weight: 500;
}
.defense-sub {
  margin-top: 2px;
  font-size: 12px;
  font-weight: 400;
  color: var(--app-muted);
}

/* ── 历史区行动条 ── */
.action-row {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}
.action-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-card-radius);
  background: var(--app-surface);
  text-align: left;
  cursor: pointer;
  transition: border-color 160ms ease;

  &:hover {
    border-color: var(--app-control);
  }
}
.action-icon {
  color: var(--app-faint);
  font-size: 22px;
}
.action-main {
  flex: 1;
  min-width: 0;
}
.action-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  color: var(--app-text);
  font-size: 14px;
  font-weight: 600;
}
.action-num {
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}
.action-sub {
  margin-top: 2px;
  color: var(--app-muted);
  font-size: 12px;
}
.action-go {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--app-muted);
  font-size: 13px;
  white-space: nowrap;
}
.action-bar:hover .action-go {
  color: var(--app-text);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.card-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.chart-box {
  width: 100%;
}
.chart-empty {
  height: 280px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 等级分布: 连通容器 + 1px 分隔, 等级用文字药丸, 数字回主文字色 */
.level-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--app-border);
  border-radius: var(--app-card-radius);
}
.level-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  min-width: 0;
  padding: 14px 8px;
  border-left: 1px solid var(--app-rule);
  text-align: center;

  &:first-child {
    border-left: 0;
  }

  .cell-cnt {
    color: var(--app-text);
    font-size: var(--dcs-kpi-size);
    font-weight: var(--dcs-kpi-weight);
    line-height: 1.15;
    font-variant-numeric: tabular-nums;
  }

  .cell-label {
    color: var(--app-faint);
    font-size: 12px;
  }
}

.event-stream-card {
  height: 100%;
}
.event-stream {
  max-height: 540px;
  overflow-y: auto;
}
.event-row {
  display: flex;
  gap: 8px;
  padding: 10px 4px;
  border-bottom: 1px solid var(--app-rule);
  &:hover {
    background: var(--app-raised);
  }
}
.event-body {
  flex: 1;
  min-width: 0;
}
.event-detail {
  font-size: 13px;
  margin-bottom: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.event-meta {
  font-size: 12px;
  color: var(--app-muted);
}
.event-score {
  color: var(--app-accent);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.event-time {
  font-size: 12px;
  color: var(--app-faint);
  white-space: nowrap;
  align-self: center;
}

/* B4: 策略命中排行 */
.rank-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.rank-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 4px;
  border-radius: var(--app-control-radius);
  transition: background-color 0.2s;

  &:hover {
    background-color: var(--app-raised);
  }
}
.rank-no {
  flex-shrink: 0;
  width: 20px;
  color: var(--app-faint);
  font-size: 12px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  text-align: right;
}
.rank-name {
  flex-shrink: 0;
  width: 100px;
  font-size: 13px;
  color: var(--app-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rank-progress {
  flex: 1;
  min-width: 0;
}
.rank-cnt {
  flex-shrink: 0;
  width: 60px;
  text-align: right;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text);
  font-variant-numeric: tabular-nums;
}

/* U-08：断点收敛 992（原 1200 自定档；640 为手机档内细分保留） */
@media (max-width: 992px) {
  .banner-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .level-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .level-cell {
    border-top: 1px solid var(--app-rule);

    &:nth-child(-n + 2) {
      border-top: 0;
    }

    &:nth-child(odd) {
      border-left: 0;
    }

    &:last-child:nth-child(odd) {
      grid-column: 1 / -1;
    }
  }
}
</style>
