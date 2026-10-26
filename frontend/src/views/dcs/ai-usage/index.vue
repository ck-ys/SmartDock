<template>
  <div class="dcs-ops-page">
    <DcsOperationsPageHeader
      title="AI 用量明细"
      eyebrow="AI 配置与用量"
      subtitle="追踪每次大模型调用的 Token、时延、基础成本与服务费，帮助租户核对消耗并定位失败请求。"
      :metrics="headerMetrics"
    >
      <template #actions>
        <el-button :icon="Refresh" @click="loadData">刷新数据</el-button>
      </template>
    </DcsOperationsPageHeader>

    <!-- AI 缺口批 Q5（2026-10-14）：明细 / 用量分析 双页签 -->
    <el-tabs v-model="activeTab" class="usage-tabs" @tab-change="onTabChange">
      <el-tab-pane label="明细" name="detail">
        <div class="mb-4">
          <!-- 各调用方分布 -->
          <el-card v-if="summary && summary.costByCaller" shadow="never" class="caller-overview-card">
            <template #header><span>调用方成本分布</span></template>
            <div class="ui-kpi-strip caller-strip">
              <div v-for="(cost, caller) in summary.costByCaller" :key="caller" class="ui-kpi">
                <span class="ui-kpi__label">{{ callerLabel(String(caller)) }}</span>
                <strong class="ui-kpi__value">¥{{ Number(cost).toFixed(4) }}</strong>
                <span class="ui-kpi__hint">{{ summary.byCaller[caller] || 0 }} 次调用</span>
              </div>
            </div>
          </el-card>
        </div>

        <el-card shadow="never" class="dcs-ops-table-card">
          <template #header>
            <div class="filter-row">
              <el-select v-model="queryParams.caller" clearable placeholder="调用方" style="width: 160px" @change="handleQuery">
                <el-option v-for="[value, label] in callerOptions" :key="value" :label="label" :value="value" />
              </el-select>
              <el-button type="primary" :icon="Search" @click="handleQuery">查询</el-button>
              <el-button :icon="RefreshLeft" @click="resetQuery">重置</el-button>
              <!-- AI 缺口批 Q7：按当前筛选导出明细 Excel -->
              <el-button :icon="Download" :loading="exporting" @click="exportUsage">导出 Excel</el-button>
            </div>
          </template>
          <div class="usage-table-wrap">
            <el-table v-loading="loading" :data="usageList" class="ui-table">
              <el-table-column label="时间" prop="createTime" width="170" />
              <el-table-column label="调用方" width="120">
                <template #default="{ row }">
                  <span class="ui-pill ui-pill--muted">{{ callerLabel(row.caller) }}</span>
                </template>
              </el-table-column>
              <el-table-column width="150" show-overflow-tooltip>
                <template #header>
                  场景
                  <el-tooltip placement="top" content="这次调用在业务里的位置：第几轮会话、自动起标题、追问建议等；保留原文的是未收录的新场景">
                    <span class="col-info">ⓘ</span>
                  </el-tooltip>
                </template>
                <template #default="{ row }">{{ sceneLabel(row.scene) }}</template>
              </el-table-column>
              <el-table-column label="模型" prop="modelName" min-width="150" show-overflow-tooltip />
              <el-table-column label="计费模式" width="120" align="center">
                <template #default="{ row }">
                  <span class="ui-pill ui-pill--muted">{{ billingModeLabel(row.billingMode) }}</span>
                </template>
              </el-table-column>
              <el-table-column min-width="190" show-overflow-tooltip>
                <template #header>
                  Token
                  <el-tooltip
                    placement="top"
                    content="输入=发送内容消耗的 Token；输出=AI 回答生成的 Token；缓存=命中缓存的输入部分（更便宜，为 0 时不显示）"
                  >
                    <span class="col-info">ⓘ</span>
                  </el-tooltip>
                </template>
                <template #default="{ row }">{{ tokenText(row) }}</template>
              </el-table-column>
              <el-table-column label="耗时(ms)" prop="latencyMs" width="100" align="right" class-name="ui-num" />
              <el-table-column label="基础成本" width="105" align="right" class-name="ui-num">
                <template #default="{ row }">{{ money(row.baseCostAmount ?? row.unitPrice, row.currency) }}</template>
              </el-table-column>
              <el-table-column label="服务费" width="105" align="right" class-name="ui-num">
                <template #default="{ row }">
                  <span>{{ money(row.serviceFeeAmount, row.currency) }}</span>
                  <span v-if="row.serviceFeeRate" class="fee-rate">({{ Number(row.serviceFeeRate).toFixed(2) }}%)</span>
                </template>
              </el-table-column>
              <el-table-column label="最终消耗" width="110" align="right" class-name="ui-num">
                <template #default="{ row }">
                  <span :class="{ 'cost-zero': Number(row.costAmount || 0) === 0 }">{{ money(row.costAmount, row.currency) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90" align="center">
                <template #default="{ row }">
                  <span class="ui-pill" :class="row.success === '1' ? 'ui-pill--pos' : 'ui-pill--neg'">{{
                    row.success === '1' ? '成功' : '失败'
                  }}</span>
                </template>
              </el-table-column>
              <!-- 业务反馈批1015c Q9(拍板A)：错误列不再裸出库内英文原文（管理员满屏英文吓人）——
                   全站公共映射 humanizeAiError 归类成中文短语（与聊天错误卡同表），悬停看原文。
                   title 直接挂原文（detail 就是原文），单行只调一次映射（三审 P2：原写法 3 次/行）；
                   nowrap+ellipsis 防长原文换行撑高行 -->
              <el-table-column label="错误" prop="errorMsg" min-width="200">
                <template #default="{ row }">
                  <span v-if="row.errorMsg" :title="row.errorMsg" class="error-ellipsis">
                    {{ humanizeAiError(row.errorMsg).title }}
                  </span>
                  <template v-else>--</template>
                </template>
              </el-table-column>
              <template #empty>
                <div class="ui-empty">
                  <p class="ui-empty__title">当前筛选下没有调用明细</p>
                  <p class="ui-empty__hint">换个调用方或清空筛选后再看；有调用发生后这里会逐条列出 Token 与成本</p>
                </div>
              </template>
            </el-table>
          </div>
          <pagination
            v-show="total > 0"
            :total="total"
            v-model:page="queryParams.pageNum"
            v-model:limit="queryParams.pageSize"
            @pagination="loadList"
          />
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="用量分析" name="analysis" lazy>
        <el-card shadow="never" class="analysis-card">
          <template #header>
            <div class="filter-row analysis-filter">
              <el-date-picker
                v-model="analysisRange"
                type="daterange"
                value-format="YYYY-MM-DD"
                range-separator="~"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                style="width: 260px"
                :clearable="false"
              />
              <el-button type="primary" :icon="Search" @click="loadAnalysis">查询</el-button>
              <el-button :icon="Download" :loading="exporting" @click="exportAnalysis">导出分析报表</el-button>
              <div v-if="analysis?.summary" class="analysis-summary">
                <span
                  >窗口内 <b>{{ fmtNum(analysis.summary.calls) }}</b> 次调用</span
                >
                <span
                  >成功率 <b>{{ Number(analysis.summary.successRate || 0).toFixed(1) }}%</b></span
                >
                <span
                  >平均耗时 <b>{{ fmtNum(analysis.summary.avgLatencyMs) }}</b> ms</span
                >
                <span
                  >消耗 <b>¥{{ Number(analysis.summary.totalCost || 0).toFixed(4) }}</b></span
                >
              </div>
            </div>
          </template>
          <el-row :gutter="14">
            <el-col :span="24">
              <div class="chart-title">每日消耗趋势（金额柱 + 次数线）</div>
              <div ref="trendRef" class="chart chart-lg" />
            </el-col>
            <el-col :xs="24" :sm="12">
              <div class="chart-title">模型占比（按消耗）</div>
              <div ref="modelRef" class="chart" />
            </el-col>
            <el-col :xs="24" :sm="12">
              <div class="chart-title">Top 10 用户（按消耗，含部门）</div>
              <div ref="userRef" class="chart" />
            </el-col>
            <el-col :span="24">
              <div class="chart-title">部门消耗分布（观测口径：只看不管，拦截不在本期）</div>
              <div ref="deptRef" class="chart" />
            </el-col>
          </el-row>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import * as echarts from 'echarts';
import { Search, Refresh, RefreshLeft, Download } from '@element-plus/icons-vue';
import { humanizeAiError } from '@/utils/aiErrorText';
import { useChartTokens } from '@/utils/chart-theme';
import { listDcsAiUsage, getDcsAiUsageSummary, getDcsAiUsageAnalysis } from '@/api/dcs/ai';
import { DcsAiUsageVO } from '@/api/dcs/ai/types';

defineOptions({ name: 'DcsAiUsage' });

const loading = ref(false);
const usageList = ref<DcsAiUsageVO[]>([]);
const total = ref(0);
const summary = ref<any>(null);
const headerMetrics = computed(() => [
  { label: '累计调用', value: summary.value?.totalCalls || 0, hint: '全部模型调用次数', tone: 'primary' as const },
  { label: '累计消耗', value: `¥${Number(summary.value?.totalCost || 0).toFixed(4)}`, hint: '基础成本与服务费合计', tone: 'warning' as const },
  {
    label: '成功率',
    value: `${Number(summary.value?.successRate || 0).toFixed(1)}%`,
    hint: `${summary.value?.successCalls || 0} 次成功`,
    tone: 'success' as const
  },
  { label: '当前结果', value: total.value, hint: '符合筛选条件的调用明细', tone: 'neutral' as const }
]);
const queryParams = reactive({
  pageNum: 1,
  pageSize: 20,
  caller: '',
  scene: '',
  configId: undefined as number | string | undefined
});

/** 业务批3-②(#14)：调用方中文名全集（库内 7 值 + 预留 3 值）——表格、分布卡、筛选下拉共用一源 */
const CALLER_LABELS: Record<string, string> = {
  ASSISTANT_CHAT: '助手会话',
  PROFILE_DIAGNOSE: '画像诊断',
  PROFILE_EMBEDDING: '画像向量化',
  FIELD_GENERATE: '字段生成',
  SCORE_ENHANCE: '评分增强',
  SCORE_RULE_RECOMMEND: '评分规则推荐',
  TEST_CONNECTION: '连接测试',
  SPEED_PROBE: '测速',
  PRICE_DETECT: '价格探测',
  AI_CALL: '通用调用'
};
const callerOptions = Object.entries(CALLER_LABELS);

const callerLabel = (caller: string) => {
  return CALLER_LABELS[caller] || (caller ? `未知(${caller})` : '--');
};

const billingModeLabel = (mode?: string) => {
  const map: Record<string, string> = {
    OFFICIAL_TOKEN: '官方Token',
    FIXED_PER_CALL: '固定单价',
    // 业务批4 Q4-A：第三收法——官方 token 价＋每次固定服务费（回修 dspro P2-7 补标签防英文枚举
    // 直出；m3 P1-3：与配置页下拉统一「官方价＋每次服务费」全角＋）。注：固定单价此处不带
    // 「回退」后缀与配置页有意差异（配置页强调模式语义，流水表按历史事实简写）
    OFFICIAL_PLUS_FEE: '官方价＋每次服务费'
  };
  return map[mode || ''] || mode || '--';
};

const money = (value?: number, currency?: string) => {
  const symbol = currency && currency !== 'CNY' ? currency : '¥';
  // 视觉席回修（业务批5）：六位小数扫读吃力——≥1 分两位，更小保留 4 位（微计费精度不丢）
  const v = Number(value || 0);
  const digits = v === 0 || v >= 0.01 ? 2 : 4;
  return `${symbol}${v.toFixed(digits)}`;
};

/** 业务批5 Q6-A：Token 中文化+千分位——治 "in 1000000 / cached 0 / out 189" 英文缩写裸奔
 *  （业务反馈看不懂）；缓存为 0 时隐藏缓存项（无信息量不占宽），非 0 才显示（缓存命中更便宜） */
const fmtNum = (n?: number): string => Number(n || 0).toLocaleString('en-US');

const tokenText = (row: DcsAiUsageVO) => {
  const prompt = row.promptTokens ?? 0;
  const cached = row.cachedPromptTokens ?? 0;
  const completion = row.completionTokens ?? 0;
  const total = row.totalTokens ?? prompt + completion;
  const parts = [`输入 ${fmtNum(prompt)}`];
  if (cached > 0) parts.push(`缓存 ${fmtNum(cached)}`);
  parts.push(`输出 ${fmtNum(completion)}`, `合计 ${fmtNum(total)}`);
  return parts.join(' · ');
};

/** 业务批5 Q6-A：场景列人话映射——库内存的是内部调试串（assist:chat:seq1|sidecar:title），
 *  裸出完全读不懂；分段翻译，未识别的段保留原文（不瞎翻译），null 显示 -- */
const sceneLabel = (scene?: string): string => {
  if (!scene) return '--';
  return scene
    .split('|')
    .map((seg) => {
      // 尾缀 :th+8位=会话留痕（提示词批 Q9 scene 拼接），语义仍归「会话第 N 轮」
      const m = seg.match(/^assist:chat:seq(\d+)(?::th[0-9a-f]{8})?$/);
      if (m) return `会话第 ${m[1]} 轮`;
      if (seg === 'sidecar:title') return '标题生成';
      if (seg === 'sidecar:followup') return '追问建议';
      if (seg === 'est') return '费用预估';
      return seg;
    })
    .join(' · ');
};

const loadList = async () => {
  loading.value = true;
  try {
    const res: any = await listDcsAiUsage(queryParams);
    usageList.value = res.rows || res.data || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
};

const loadSummary = async () => {
  try {
    const res: any = await getDcsAiUsageSummary();
    summary.value = res.data || null;
  } catch {
    summary.value = null;
  }
};

const handleQuery = () => {
  queryParams.pageNum = 1;
  loadList();
};

const resetQuery = () => {
  queryParams.caller = '';
  queryParams.scene = '';
  queryParams.configId = undefined;
  queryParams.pageNum = 1;
  loadList();
};

const loadData = () => {
  loadList();
  loadSummary();
};

// ============ AI 缺口批 Q5/Q6/Q7（2026-10-14）：用量分析页签 + 导出 ============
const { proxy } = getCurrentInstance() as any;
const exporting = ref(false);
const activeTab = ref<'detail' | 'analysis'>('detail');

const defaultRange = (): [string, string] => {
  const end = new Date();
  const begin = new Date(end.getTime() - 29 * 24 * 3600 * 1000);
  const f = (d: Date) => d.toISOString().substring(0, 10);
  return [f(begin), f(end)];
};
const analysisRange = ref<[string, string]>(defaultRange());
const analysis = ref<any>(null);
/** 图表色随主题切换（A 墨青 / D 青柠） */
const ct = useChartTokens();

const trendRef = ref<HTMLDivElement>();
const modelRef = ref<HTMLDivElement>();
const userRef = ref<HTMLDivElement>();
const deptRef = ref<HTMLDivElement>();
let chartsInited = false;
let trendChart: echarts.ECharts | null = null;
let modelChart: echarts.ECharts | null = null;
let userChart: echarts.ECharts | null = null;
let deptChart: echarts.ECharts | null = null;

/** 惰性页签：首次切入再初始化 echarts（隐藏态初始化拿到 0 尺寸画布） */
const onTabChange = (name: string | number) => {
  if (name === 'analysis') {
    nextTick(() => {
      initCharts();
      void loadAnalysis();
    });
  }
};

const initCharts = () => {
  if (chartsInited) return;
  trendChart = echarts.init(trendRef.value!);
  modelChart = echarts.init(modelRef.value!);
  userChart = echarts.init(userRef.value!);
  deptChart = echarts.init(deptRef.value!);
  window.addEventListener('resize', resizeCharts);
  chartsInited = true;
};

const resizeCharts = () => {
  trendChart?.resize();
  modelChart?.resize();
  userChart?.resize();
  deptChart?.resize();
};

const loadAnalysis = async () => {
  const [beginDate, endDate] = analysisRange.value || [];
  const res: any = await getDcsAiUsageAnalysis({ beginDate, endDate });
  analysis.value = res.data || null;
  renderCharts();
};

const renderCharts = () => {
  const data = analysis.value;
  if (!data || !chartsInited) return;
  // 趋势：金额柱 + 次数线双轴
  const trend = data.trend || [];
  trendChart?.setOption({
    grid: { left: 60, right: 50, top: 30, bottom: 40 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: trend.map((d: any) => d.day?.substring(5)),
      axisLabel: { color: ct.value.faint },
      axisLine: { lineStyle: { color: ct.value.border } }
    },
    yAxis: [
      {
        type: 'value',
        name: '元',
        nameTextStyle: { color: ct.value.faint },
        axisLabel: { color: ct.value.faint, formatter: (v: number) => (v >= 1 ? v : v.toFixed(2)) },
        splitLine: { lineStyle: { color: ct.value.rule } }
      },
      { type: 'value', name: '次', splitLine: { show: false }, nameTextStyle: { color: ct.value.faint }, axisLabel: { color: ct.value.faint } }
    ],
    series: [
      { name: '消耗(元)', type: 'bar', data: trend.map((d: any) => Number(d.cost || 0)), itemStyle: { color: ct.value.accent }, barMaxWidth: 26 },
      {
        name: '调用次数',
        type: 'line',
        yAxisIndex: 1,
        data: trend.map((d: any) => Number(d.calls || 0)),
        itemStyle: { color: ct.value.series[1] },
        lineStyle: { color: ct.value.series[1] },
        smooth: true
      }
    ]
  });
  // 模型占比环图（无数据给空态文案）
  const models = (data.modelShare || []).map((m: any) => ({
    name: m.model_name || m.modelName || '(未知模型)',
    value: Number(m.cost || 0)
  }));
  modelChart?.setOption({
    color: ct.value.ramp,
    tooltip: { trigger: 'item', formatter: '{b}<br/>消耗 ¥{c}（{d}%）' },
    legend: { type: 'scroll', orient: 'vertical', right: 0, top: 'middle', textStyle: { fontSize: 11, color: ct.value.muted } },
    series: [
      {
        type: 'pie',
        radius: ['38%', '68%'],
        center: ['38%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: ct.value.surface, borderWidth: 2 },
        label: { show: false },
        data: models.length ? models : [{ name: '窗口内无调用', value: 0, itemStyle: { color: ct.value.rule } }]
      }
    ]
  });
  // Top10 用户横向条（含部门）
  const users = (data.topUsers || []).slice().reverse();
  userChart?.setOption({
    grid: { left: 10, right: 70, top: 10, bottom: 20, containLabel: true },
    tooltip: {
      trigger: 'item',
      formatter: (p: any) => {
        const u = users[p.dataIndex] || {};
        return `${u.user_name}（${u.dept_name}）<br/>消耗 ¥${Number(p.value || 0).toFixed(4)} · ${u.calls} 次`;
      }
    },
    xAxis: { type: 'value', axisLabel: { formatter: (v: number) => v.toFixed(2), color: ct.value.faint }, splitLine: { lineStyle: { color: ct.value.rule } } },
    yAxis: { type: 'category', data: users.map((u: any) => u.user_name), axisLabel: { width: 90, overflow: 'truncate', color: ct.value.muted } },
    series: [{ type: 'bar', data: users.map((u: any) => Number(u.cost || 0)), itemStyle: { color: ct.value.accent }, barMaxWidth: 16 }]
  });
  // 部门消耗横向条
  const depts = (data.deptShare || []).slice().reverse();
  deptChart?.setOption({
    grid: { left: 10, right: 70, top: 10, bottom: 20, containLabel: true },
    tooltip: {
      trigger: 'item',
      formatter: (p: any) => {
        const d = depts[p.dataIndex] || {};
        return `${d.dept_name}<br/>消耗 ¥${Number(p.value || 0).toFixed(4)} · ${d.calls} 次`;
      }
    },
    xAxis: { type: 'value', axisLabel: { formatter: (v: number) => v.toFixed(2), color: ct.value.faint }, splitLine: { lineStyle: { color: ct.value.rule } } },
    yAxis: { type: 'category', data: depts.map((d: any) => d.dept_name), axisLabel: { color: ct.value.muted } },
    series: [{ type: 'bar', data: depts.map((d: any) => Number(d.cost || 0)), itemStyle: { color: ct.value.series[1] }, barMaxWidth: 16 }]
  });
};

// 切主题后按新 token 重画四张图（canvas 读不到 CSS 变量）
watch(
  () => ct.value,
  () => renderCharts()
);

/** Q7：明细导出（按当前筛选） */
const exportUsage = () => {
  proxy?.download(
    '/dcs/ai-usage/export',
    { caller: queryParams.caller || undefined, scene: queryParams.scene || undefined, configId: queryParams.configId },
    `AI用量明细_${new Date().getTime()}.xlsx`
  );
};

/** Q5：分析报表导出（按窗口聚合） */
const exportAnalysis = () => {
  const [beginDate, endDate] = analysisRange.value || [];
  proxy?.download('/dcs/ai-usage/export-analysis', { beginDate, endDate }, `AI用量分析_${new Date().getTime()}.xlsx`);
};

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts);
  trendChart?.dispose();
  modelChart?.dispose();
  userChart?.dispose();
  deptChart?.dispose();
  // 卸载后再进来（keep-alive 淘汰 / 路由重建）必须重新 init：仍留 chartsInited=true 会拿到已销毁的实例
  trendChart = null;
  modelChart = null;
  userChart = null;
  deptChart = null;
  chartsInited = false;
});

onMounted(loadData);
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.page-title {
  font-size: 18px;
  font-weight: 600;
}
.page-subtitle {
  font-size: 13px;
  color: var(--app-faint);
  margin-top: 4px;
}
.filter-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
/* 调用方成本：一个连通带 + 1px 分隔，不再做四宫格磁贴（契约 §2.3） */
.caller-overview-card {
  margin-bottom: 0;
}
.caller-overview-card :deep(.el-card__body) {
  padding: 16px;
}
.caller-strip {
  grid-auto-flow: row;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 1px;
  margin-top: 0;
  background: var(--app-rule);
}
/* U-06(2026-10-26 拍板A)：≤992 跟 ui-kpi-strip 同步两列。scoped auto-fit(窄档算出 3 列) 会撞上
   全局「奇数末项拉满整行」规则——第 3 项被拽到第二行满宽，首行第三格露出 1px 缝底色的灰块；
   两列 + 拉满规则对 3/4 项都不留空格（3=2+1 满宽、4=2+2） */
@media (max-width: 992px) {
  .caller-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.caller-strip .ui-kpi {
  border: 0;
  background: var(--app-surface);
  padding: 14px 16px;
}
/* 375：明细表在容器内横滚，父链 min-width:0 防整页溢出 */
.usage-table-wrap {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
}
.usage-table-wrap :deep(.el-table) {
  /* 列宽之和：外层容器一处横滚，避免与 el-table 内部滚动条叠加 */
  min-width: 1610px;
  /* 只覆盖 EP 表格 CSS 变量，不重写组件（契约 §2.5） */
  --el-table-header-bg-color: transparent;
  --el-table-tr-bg-color: var(--app-surface);
  --el-table-row-hover-bg-color: var(--app-raised);
  --el-table-border-color: var(--app-rule);
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
}
.dcs-ops-page :deep(.el-tabs__content),
.dcs-ops-page :deep(.el-tab-pane) {
  min-width: 0;
}
.cost-zero {
  color: var(--app-faint);
}
.fee-rate {
  display: block;
  color: var(--app-faint);
  font-size: 12px;
  line-height: 16px;
}
.error-ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 业务批5 Q6-A：列头 ⓘ 信息钮——悬浮给 Token/场景的术语人话解释 */
.col-info {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 1px solid var(--el-border-color);
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1;
  cursor: help;
  vertical-align: 2px;
  margin-left: 3px;
}
/* ===== AI 缺口批 Q5：用量分析页签 ===== */
.usage-tabs {
  background: transparent;
}
.analysis-card {
  margin-bottom: 12px;
}
.analysis-filter {
  justify-content: flex-start;
}
.analysis-summary {
  margin-left: auto;
  display: flex;
  gap: 18px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.analysis-summary b {
  color: var(--el-text-color-primary);
  font-size: 14px;
}
.chart-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  margin: 10px 0 6px;
}
.chart {
  width: 100%;
  height: 300px;
}
.chart-lg {
  height: 320px;
}
</style>
