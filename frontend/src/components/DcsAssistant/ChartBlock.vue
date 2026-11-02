<template>
  <!-- 畸形 spec/type 统一降级为提示行（不炸页面；md 层已兜住 JSON.parse 失败，这里兜 type/结构） -->
  <div v-if="err" class="cb-err">图表无法渲染：{{ err }}</div>
  <div v-else class="cb-wrap">
    <div v-if="headTitle || headSub" class="cb-head">
      <span v-if="headTitle" class="cb-title">{{ headTitle }}</span>
      <span v-if="headSub" class="cb-sub">{{ headSub }}</span>
    </div>
    <div ref="el" class="cb-box" :style="boxStyle"></div>
  </div>
</template>

<script setup lang="ts">
/**
 * 批4 Q5：助手回答内嵌统计图（```chart 协议；「问数据」批·题4A 由四类型扩为七类型
 * bar/line/pie/gauge/funnel/sankey/retention——漏斗/留存格/页面流向三种新形同样只信白名单字段）。
 *
 * <p>挂载方式：md.ts 把 chart 围栏渲染成 .dcs-chart 占位 div（data-chart=JSON），TurnBlock
 * 用 createVNode+render 手动挂本组件（v-html 内容进不了 Vue 虚拟树）。spec 只信任白名单字段：
 * type/title/data[{name,value}]/value——其余字段一律忽略，option 在组件内构造（模型永远
 * 拿不到 ECharts option 原始结构，无注入面）。数据=工具返回真数由 chart.md 教学约束，
 * 组件只管渲染。窄容器：ResizeObserver 跟随宽度 resize。</p>
 *
 * <p>2026-10-13 视觉重做（五需求批 R2 / Q4A+Q5A）：标题移出画布改组件头部左对齐+口径小字；
 * 饼图 ≤6 项且标签贴边对齐（alignTo edge+引导线+小扇区隐标签），>6 项自动转横向条形图；
 * 柱/折线开顶部数据标签、Y 轴整数刻度、浅灰轴线；仪表盘去指针改中心大数字；商务色板
 * 替换 EP 裸五色。协议（type/title/data/value 白名单）不变，纯渲染层改动。</p>
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import * as echarts from 'echarts';
import { useChartTokens, alpha, type ChartTokens } from '@/utils/chart-theme';

const props = defineProps<{ spec: string }>();

const el = ref<HTMLDivElement | null>(null);
const err = ref('');
let chart: echarts.ECharts | null = null;
let ro: ResizeObserver | null = null;
/** 助手内嵌图与整站同一套取色：A 墨青 / D 青柠，切主题后重画 */
const ct = useChartTokens();
let currentSpec: ChartSpec | null = null;

type Datum = { name: string; value: number };
interface SankeyNode {
  name: string;
}
interface SankeyLink {
  source: string;
  target: string;
  value: number;
}
interface RetentionCell {
  day: number;
  visitors: number;
}
interface RetentionCohort {
  cohort: string;
  total: number;
  cells: RetentionCell[];
}
interface ChartSpec {
  type?: string;
  title?: string;
  data?: Datum[];
  value?: number;
  name?: string;
  nodes?: SankeyNode[];
  links?: SankeyLink[];
  cohorts?: RetentionCohort[];
  /** 留存粒度单位（后端给「按天/按周/按月」；tooltip 的「第 N 天/周/月」随它走，缺省=天） */
  unit?: string;
}

/** 饼图允许的最大扇区数：超过即自动转横向条形图（Q5A，2026-10-13 拍板） */
const PIE_MAX_SLICES = 6;

/** 「问数据」批·题4A：桑基节点/同期群数量上限（与服务端路径/留存窗口量级对齐，防挤爆画布） */
const SANKEY_MAX_NODES = 30;
const RETENTION_MAX_COHORTS = 14;

/** 只放行白名单类型；数值字段非法即降级（NaN/Infinity 画出来必炸） */
function parseSpec(raw: string): { ok: true; spec: ChartSpec } | { ok: false; msg: string } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ok: false, msg: 'JSON 格式错误' };
  }
  if (!parsed || typeof parsed !== 'object') {
    return { ok: false, msg: 'JSON 不是对象' };
  }
  const s = parsed as ChartSpec;
  const types = ['bar', 'line', 'pie', 'gauge', 'funnel', 'sankey', 'retention'];
  if (!types.includes(s.type ?? '')) {
    return { ok: false, msg: `不支持的类型「${s.type ?? ''}」（仅 bar/line/pie/gauge/funnel/sankey/retention）` };
  }
  if (s.type === 'gauge') {
    if (typeof s.value !== 'number' || !Number.isFinite(s.value)) {
      return { ok: false, msg: 'gauge 需要 value 数字' };
    }
    // 协议口径 0~100（chart.md）：超界拒绝降级提示，不静默钳制（指针钉端点+数值仍显 150 的荒谬观感）
    if (s.value < 0 || s.value > 100) {
      return { ok: false, msg: 'gauge 的 value 需在 0~100' };
    }
    return { ok: true, spec: s };
  }
  if (s.type === 'sankey') {
    const nodes = Array.isArray(s.nodes) ? s.nodes : [];
    const links = Array.isArray(s.links) ? s.links : [];
    if (nodes.length === 0 || links.length === 0) {
      return { ok: false, msg: 'sankey 需要 nodes 与 links（都不能为空）' };
    }
    const names = new Set<string>();
    for (const n of nodes) {
      if (!n || typeof n.name !== 'string' || !n.name) {
        return { ok: false, msg: 'nodes 每项需 {name: 文本}' };
      }
      names.add(n.name);
    }
    for (const l of links) {
      if (!l || typeof l.source !== 'string' || typeof l.target !== 'string'
        || typeof l.value !== 'number' || !Number.isFinite(l.value) || l.value < 0) {
        return { ok: false, msg: 'links 每项需 {source, target, value: 非负数字}' };
      }
      if (!names.has(l.source) || !names.has(l.target)) {
        return { ok: false, msg: 'links 的 source/target 必须出现在 nodes 里' };
      }
    }
    if (links.every((l) => l.value <= 0)) {
      return { ok: false, msg: '数据全部为 0，没有可画的流向' };
    }
    if (nodes.length > SANKEY_MAX_NODES) {
      const kept = new Set(nodes.slice(0, SANKEY_MAX_NODES).map((n) => n.name));
      s.nodes = nodes.slice(0, SANKEY_MAX_NODES);
      s.links = links.filter((l) => kept.has(l.source) && kept.has(l.target));
      // 截断可能把连线全裁掉（保留的节点两两无连线）——空 links 画不出桑基，明说降级（审查席 P2）
      if (s.links.length === 0) {
        return { ok: false, msg: `节点过多（${nodes.length} 个），截到 ${SANKEY_MAX_NODES} 个后连线被裁空` };
      }
    }
    return { ok: true, spec: s };
  }
  if (s.type === 'retention') {
    const cohorts = Array.isArray(s.cohorts) ? s.cohorts : [];
    if (cohorts.length === 0) {
      return { ok: false, msg: 'cohorts 不能为空' };
    }
    for (const c of cohorts) {
      if (!c || typeof c.cohort !== 'string' || typeof c.total !== 'number' || !Number.isFinite(c.total) || !Array.isArray(c.cells)) {
        return { ok: false, msg: '每个同期群需 {cohort: 文本, total: 数字, cells: 数组}' };
      }
      for (const cell of c.cells) {
        // day 补 isFinite（typeof NaN === 'number' 会放行，NaN 落分类轴格子直接丢——审查席 P2）
        if (!cell || typeof cell.day !== 'number' || !Number.isFinite(cell.day) || typeof cell.visitors !== 'number'
          || !Number.isFinite(cell.visitors) || cell.visitors < 0) {
          return { ok: false, msg: 'cells 每格需 {day: 数字, visitors: 非负数字}' };
        }
      }
    }
    // 只保留最近 N 个同期群（老 cohort 越多图越高）
    if (cohorts.length > RETENTION_MAX_COHORTS) {
      s.cohorts = cohorts.slice(-RETENTION_MAX_COHORTS);
    }
    return { ok: true, spec: s };
  }
  const data = Array.isArray(s.data) ? s.data : [];
  if (data.length === 0) {
    return { ok: false, msg: 'data 不能为空' };
  }
  for (const d of data) {
    if (!d || typeof d.name !== 'string' || typeof d.value !== 'number' || !Number.isFinite(d.value)) {
      return { ok: false, msg: 'data 每项需 {name: 文本, value: 数字}' };
    }
    if (s.type === 'pie' && d.value < 0) {
      return { ok: false, msg: 'pie 的数值不能为负' };
    }
  }
  // 全 0 出来是空白框，不如明说（与畸形降级同款「不炸不吞」哲学）
  if (data.every((d) => d.value <= 0)) {
    return { ok: false, msg: '数据全部为 0，没有可画的对比' };
  }
  // 教学同款上限：>12 项取前 12（chart.md 已约定，前端同口径截断防标签挤成一团）
  if (data.length > 12) {
    s.data = data.slice(0, 12);
  }
  return { ok: true, spec: s };
}

/** 横向条形图（饼图超限降级目标，也复用其数据标签/轴线风格） */
/** 值轴顶部预留 15% 头部空间：防最高柱/点的数据标签贴顶被裁（视觉审查批注） */
function axisMaxWithHeadroom(value: { max: number }): number {
  return value.max + Math.max(1, Math.ceil(value.max * 0.15));
}

function hBarOption(s: ChartSpec, t: ChartTokens): echarts.EChartsCoreOption {
  const data = s.data ?? [];
  return {
    grid: { left: 6, right: 40, top: 6, bottom: 0, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: {
      type: 'value',
      minInterval: 1,
      max: axisMaxWithHeadroom,
      axisLabel: { color: t.faint, fontSize: 11 },
      splitLine: { lineStyle: { color: t.rule, type: 'dashed' } }
    },
    yAxis: {
      type: 'category',
      inverse: true,
      data: data.map((d) => d.name),
      axisLabel: { color: t.muted, fontSize: 11.5, width: 96, overflow: 'truncate' },
      axisLine: { lineStyle: { color: t.border } },
      axisTick: { show: false }
    },
    series: [
      {
        type: 'bar',
        data: data.map((d) => d.value),
        barMaxWidth: 18,
        itemStyle: { color: t.accent, borderRadius: [0, 3, 3, 0] },
        label: { show: true, position: 'right', fontSize: 11, color: t.muted }
      }
    ]
  };
}

function buildOption(s: ChartSpec, t: ChartTokens): echarts.EChartsCoreOption {
  // 标题已在组件头部渲染，画布内不再重复占位
  if (s.type === 'gauge') {
    return {
      series: [
        {
          type: 'gauge',
          startAngle: 210,
          endAngle: -30,
          min: 0,
          max: 100,
          radius: '92%',
          center: ['50%', '58%'],
          progress: { show: true, width: 13, roundCap: true, itemStyle: { color: t.accent } },
          axisLine: { lineStyle: { width: 13, color: [[1, t.rule]] } },
          axisTick: { show: false },
          splitLine: { length: 6, distance: -19, lineStyle: { width: 2, color: t.border } },
          axisLabel: { distance: -34, color: t.faint, fontSize: 10 },
          pointer: { show: false },
          // 去指针后数值放正中，不再被悬浮按钮遮挡/挤到边缘
          detail: { valueAnimation: true, fontSize: 26, fontWeight: 700, color: t.ink, offsetCenter: [0, '-6%'], formatter: '{value}%' },
          title: { offsetCenter: [0, '38%'], fontSize: 12, color: t.faint },
          data: [{ value: s.value, name: s.name ?? '' }]
        }
      ]
    };
  }
  // 「问数据」批·题4A：漏斗（哪一步掉人）——按值降序渲染，标签内嵌白字描边
  if (s.type === 'funnel') {
    const sorted = [...(s.data ?? [])].sort((a, b) => b.value - a.value);
    return {
      tooltip: { trigger: 'item', formatter: '{b}: {c} 人' },
      color: t.ramp,
      series: [
        {
          type: 'funnel',
          sort: 'descending',
          left: '4%',
          right: '4%',
          top: 8,
          bottom: 8,
          width: '92%',
          gap: 3,
          minSize: '12%',
          label: { show: true, position: 'inside', fontSize: 11, color: '#fff', textBorderColor: t.surface, textBorderWidth: 2 },
          labelLine: { show: false },
          itemStyle: { borderColor: t.surface, borderWidth: 1 },
          emphasis: { label: { fontSize: 12 } },
          data: sorted
        }
      ]
    };
  }
  // 「问数据」批·题4A：页面流向（桑基）——宽窄=人数，配色整站 ramp
  if (s.type === 'sankey') {
    return {
      tooltip: { trigger: 'item' },
      color: t.ramp,
      series: [
        {
          type: 'sankey',
          left: 8,
          right: 96,
          top: 10,
          bottom: 10,
          nodeGap: 10,
          emphasis: { focus: 'adjacency' },
          label: { color: t.muted, fontSize: 11 },
          lineStyle: { color: 'gradient', opacity: 0.35, curveness: 0.5 },
          itemStyle: { borderWidth: 0 },
          data: s.nodes ?? [],
          links: s.links ?? []
        }
      ]
    };
  }
  // 「问数据」批·题4A：留存格（同期群热力）——横轴=第 N 单元，色深=回访占比。
  // 单位词取 spec.unit（后端按粒度给「按天/按周/按月」），缺省「天」
  if (s.type === 'retention') {
    const cohorts = s.cohorts ?? [];
    // 横轴长度用 max(day)+1 而非 cells.length（cells 稀疏/跳天时 day 会越过类目数组长——审查席 P2）
    const maxDay = cohorts.reduce((m, c) => Math.max(m, c.cells.reduce((mm, cell) => Math.max(mm, cell.day + 1), 0)), 0);
    const xAxisData = Array.from({ length: maxDay }, (_, i) => String(i));
    const unitWord = typeof s.unit === 'string' && s.unit ? s.unit.replace('按', '') : '天';
    const heat: Array<[number, number, number]> = [];
    const cellIndex = new Map<string, RetentionCell>();
    cohorts.forEach((c, yi) => {
      c.cells.forEach((cell) => {
        const rate = c.total > 0 ? Math.round((cell.visitors / c.total) * 100) : 0;
        heat.push([cell.day, yi, rate]);
        cellIndex.set(`${cell.day}-${yi}`, cell);
      });
    });
    return {
      grid: { left: 8, right: 8, top: 8, bottom: 46, containLabel: true },
      tooltip: {
        position: 'top',
        formatter: (p: { value: [number, number, number] }) => {
          const cell = cellIndex.get(`${p.value[0]}-${p.value[1]}`);
          const cohort = cohorts[p.value[1]]?.cohort ?? '';
          if (!cell) {
            return `${cohort} 第 ${p.value[0]} ${unitWord}: ${p.value[2]}%`;
          }
          return `${cohort} 首访 · 第 ${p.value[0]} ${unitWord}回访 ${cell.visitors} 人（${p.value[2]}%）`;
        }
      },
      xAxis: { type: 'category', data: xAxisData, axisLabel: { color: t.faint, fontSize: 10 }, splitArea: { show: false } },
      yAxis: { type: 'category', data: cohorts.map((c) => c.cohort), axisLabel: { color: t.muted, fontSize: 10.5 } },
      visualMap: {
        min: 0,
        max: 100,
        calculable: false,
        orient: 'horizontal',
        left: 'center',
        bottom: 0,
        text: ['回访 100%', '0%'],
        textStyle: { color: t.faint, fontSize: 10 },
        inRange: { color: [alpha(t.accent, 0.08), alpha(t.accent, 0.4), alpha(t.accent, 0.88)] }
      },
      series: [
        {
          type: 'heatmap',
          label: { show: true, fontSize: 10, color: t.muted, formatter: (p: { value: [number, number, number] }) => (p.value[2] > 0 ? `${p.value[2]}%` : '') },
          itemStyle: { borderColor: t.surface, borderWidth: 1, borderRadius: 2 },
          data: heat
        }
      ]
    };
  }
  const data = s.data ?? [];
  const names = data.map((d) => d.name);
  const values = data.map((d) => d.value);
  if (s.type === 'pie') {
    if (data.length > PIE_MAX_SLICES) {
      return hBarOption(s, t);
    }
    return {
      tooltip: { trigger: 'item' },
      color: t.ramp,
      series: [
        {
          type: 'pie',
          radius: ['46%', '66%'],
          center: ['50%', '54%'],
          data,
          // 标签贴边对齐+引导线+极小扇区隐字：治「标签挤向中心」的根（Q5A）
          label: { alignTo: 'edge', edgeDistance: 12, minMargin: 4, fontSize: 11.5, color: t.muted, formatter: '{b} {c}' },
          labelLine: { length: 14, length2: 0, maxSurfaceAngle: 80, lineStyle: { color: t.border } },
          labelLayout: { hideOverlap: true },
          minShowLabelRadian: 0.08,
          itemStyle: { borderColor: t.surface, borderWidth: 2 }
        }
      ]
    };
  }
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 6, right: 20, top: 28, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category',
      data: names,
      axisLabel: { color: t.muted, fontSize: 11, interval: 0, width: 72, overflow: 'truncate' },
      axisLine: { lineStyle: { color: t.border } },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      max: axisMaxWithHeadroom,
      axisLabel: { color: t.faint, fontSize: 11 },
      splitLine: { lineStyle: { color: t.rule, type: 'dashed' } }
    },
    series: [
      s.type === 'bar'
        ? {
            type: 'bar',
            data: values,
            barMaxWidth: 26,
            itemStyle: { color: t.accent, borderRadius: [4, 4, 0, 0] },
            label: { show: true, position: 'top', fontSize: 11, color: t.muted }
          }
        : {
            type: 'line',
            data: values,
            smooth: false,
            symbol: 'circle',
            symbolSize: 6,
            lineStyle: { width: 2.5, color: t.accent },
            itemStyle: { color: t.accent, borderColor: t.surface, borderWidth: 1.5 },
            label: { show: true, position: 'top', fontSize: 11, color: t.muted },
            areaStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: alpha(t.accent, 0.24) },
                { offset: 1, color: alpha(t.accent, 0.03) }
              ])
            }
          }
    ]
  };
}

/** 头部：标题（左）+口径小字（右） */
const headTitle = ref('');
const headSub = ref('');

/** 画布高度：饼(≤6)260 / 柱折线 260 / 仪表盘 230 / 降级条形按项数自适应 */
const boxStyle = computed(() => {
  let r: { ok: true; spec: ChartSpec } | { ok: false; msg: string };
  let parsed: ChartSpec | null = null;
  try {
    r = parseSpec(props.spec);
    if (r.ok) {
      parsed = r.spec;
    }
  } catch {
    parsed = null;
  }
  if (!parsed) {
    return {};
  }
  const s = parsed;
  if (s.type === 'gauge') {
    return { height: '230px' };
  }
  if (s.type === 'funnel') {
    return { height: '300px' };
  }
  if (s.type === 'sankey') {
    return { height: '340px' };
  }
  if (s.type === 'retention') {
    const n = (s.cohorts ?? []).length;
    return { height: `${Math.min(150 + n * 46, 430)}px` };
  }
  const n = (s.data ?? []).length;
  if (s.type === 'pie' && n > PIE_MAX_SLICES) {
    return { height: `${Math.min(150 + n * 30, 440)}px` };
  }
  return { height: '260px' };
});

onMounted(() => {
  const r = parseSpec(props.spec);
  if (!r.ok) {
    err.value = (r as { msg?: string }).msg ?? '图表数据解析失败';
    return;
  }
  if (!el.value) {
    return;
  }
  const s = r.spec;
  headTitle.value = s.title ?? '';
  if (s.type === 'gauge') {
    headSub.value = s.name ? `${s.name}` : '';
  } else if (s.type === 'funnel') {
    const d = s.data ?? [];
    headSub.value = d.length ? `共 ${d.length} 步 · 首步 ${d[0].value} 人` : '';
  } else if (s.type === 'sankey') {
    headSub.value = `共 ${(s.nodes ?? []).length} 页 · ${(s.links ?? []).length} 条流向`;
  } else if (s.type === 'retention') {
    const cs = s.cohorts ?? [];
    const w = cs[0]?.cells.length ?? 0;
    headSub.value = `共 ${cs.length} 个同期群 · 横轴=第 N 天 · 窗口 ${w}`;
  } else {
    const n = (s.data ?? []).length;
    const total = (s.data ?? []).reduce((acc, d) => acc + d.value, 0);
    headSub.value = `共 ${n} 项 · 合计 ${total}`;
  }
  chart = echarts.init(el.value);
  currentSpec = s;
  chart.setOption(buildOption(s, ct.value));
  ro = new ResizeObserver(() => chart?.resize());
  ro.observe(el.value);
});

// 切主题后按新 token 重画（canvas 读不到 CSS 变量）
watch(
  () => ct.value,
  () => {
    if (chart && currentSpec) {
      chart.setOption(buildOption(currentSpec, ct.value), true);
    }
  }
);

onBeforeUnmount(() => {
  ro?.disconnect();
  ro = null;
  chart?.dispose();
  chart = null;
});
</script>

<style lang="scss" scoped>
.cb-err {
  padding: 10px 14px;
  border: 1px dashed var(--el-color-warning-light-5);
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
  color: var(--dcs-text-secondary);
  font-size: 12.5px;
}
.cb-wrap {
  width: 100%;
  border: 1px solid var(--app-border, #ebeef5);
  border-radius: 10px;
  padding: 12px 12px 6px;
  background: var(--app-surface, #fff);
}
.cb-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 0 2px 6px;
}
.cb-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--dcs-text-primary, #1f2d3d);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cb-sub {
  flex-shrink: 0;
  font-size: 11.5px;
  color: var(--dcs-text-secondary, #909399);
}
.cb-box {
  width: 100%;
  height: 260px;
}
</style>
