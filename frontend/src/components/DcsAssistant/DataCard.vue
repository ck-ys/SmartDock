<template>
  <div v-if="card" class="dc" :data-tool="card.tool">
    <!-- 头行：标题 + 数据截止 + 导出 -->
    <div class="dh">
      <span>{{ card.title }}</span>
      <span class="asof" :title="card.asOf">数据截止 {{ fmtTime(card.asOf) }}</span>
      <button class="dex" title="导出当前视图为 CSV" @click="exportCsv"><IconSpan name="download" /> 导出</button>
    </div>

    <!-- 状态筛选 chips（带计数；todos/alerts 按 todoType，missions 按 status）；
         未交名单卡右端附「明细 ⇄ 按部门汇总」双模切换（原型 DP-4） -->
    <div v-if="buckets.length > 1 || isUnsubmitted" class="ftools">
      <button
        v-for="b in buckets"
        :key="b.key"
        class="dfchip"
        :class="{ on: activeBucket === b.key }"
        @click="activeBucket = activeBucket === b.key ? '' : b.key"
      >
        {{ b.key }} <i>{{ b.n }}</i>
      </button>
      <template v-if="isUnsubmitted">
        <span class="fsp" />
        <button class="dfchip modec" :class="{ on: mode === 'list' }" @click="switchMode('list')">
          <IconSpan name="clipboard" :size="12" /> 明细
        </button>
        <button class="dfchip modec" :class="{ on: mode === 'agg' }" @click="switchMode('agg')">
          <IconSpan name="chart" :size="12" /> 按部门汇总
        </button>
      </template>
    </div>

    <!-- 聚合视图（mission_detail 单行：计数格 + 状态分布柱图） -->
    <div v-if="isDetail" class="agg">
      <div class="agg-grid">
        <div v-for="c in aggCells" :key="c.label" class="acell" :class="c.cls">
          <b>{{ c.value }}</b
          ><span>{{ c.label }}</span>
        </div>
      </div>
      <div class="chart" role="img" :aria-label="`状态分布柱图：${chartBars.map((b) => b.label + ' ' + b.value).join('，')}`">
        <div class="cbody">
          <div v-for="b in chartBars" :key="b.label" class="cbar" :class="{ today: b.hi }">
            <span class="v">{{ b.value }}</span>
            <i class="b" :style="{ height: b.pct + '%' }" />
          </div>
        </div>
        <div class="clab">
          <span v-for="b in chartBars" :key="b.label">{{ b.label }}</span>
        </div>
      </div>
    </div>

    <!-- 按部门汇总视图（DP-4：部门分组计数+占比+催办建议；k>=30 今天优先催，否则明天催）。
         批D回修：名单被截断时本地分布不是全量——必须亮警示，不再自称"全部" -->
    <div v-if="showCollegeAgg" class="twrap cagg">
      <div v-if="card.truncated" class="aggwarn">
        ⓘ 名单共 {{ card.total }} 人，一次只带到前
        {{ card.rows.length }} 人——下面的分布只是可见部分，不是全量；要看完整真数，直接问助手「按部门汇总未交」
      </div>
      <table>
        <thead>
          <tr>
            <th>部门</th>
            <th>未交人数</th>
            <th>占比</th>
            <th>建议</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="g in collegeAggRows" :key="g.k" class="dr">
            <td :title="g.k">{{ g.k }}</td>
            <td>
              <b>{{ g.n }}</b> 人
            </td>
            <td class="mono">{{ g.pct }}%</td>
            <td class="mono" :class="{ hot: g.hot }">{{ g.advice }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 明细表（窄抽屉下列宽超出时卡内横向滚，不撑破抽屉） -->
    <div v-else-if="!isDetail" class="twrap">
      <table>
        <thead>
          <tr>
            <th v-for="c in card.columns" :key="c.key" :class="{ srt: c.sortable }" @click="toggleSort(c.key)">
              {{ c.label }}<span v-if="sortKey === c.key" class="sarr"><IconSpan :name="sortAsc ? 'arrow-up' : 'arrow-down'" :size="11" /></span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="r in pagedRows"
            :key="r.__k"
            class="dr"
            :class="rowCls(r)"
            :title="r.missionId ? '点击在页面上查看该任务' : ''"
            @click="onRow(r)"
          >
            <td v-for="c in card.columns" :key="c.key" :class="{ mono: c.mono }">
              <template v-if="c.key === '__status'"
                ><span class="stw"><i :style="{ background: dotColor(r.status) }" />{{ statusName(r.status) }}</span></template
              >
              <template v-else-if="c.key === '__progress'">
                <span class="pwrap"
                  >{{ r.completedCount ?? 0 }} / {{ r.totalCount ?? 0
                  }}<i v-if="pct(r) != null" class="pbar"><b :style="{ width: pct(r) + '%' }" /></i
                ></span>
              </template>
              <template v-else>{{ c.fmt ? c.fmt(r[c.key]) : (r[c.key] ?? '—') }}</template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 分页 + 尾链 -->
    <div class="dfoot">
      <span v-if="isByDept" class="cnt">
        未交合计 {{ card.userTotal }} 人（服务端全量分组，真数）<template v-if="card.caliberNote"> · {{ card.caliberNote }}</template>
      </span>
      <span v-else-if="showCollegeAgg && card.truncated" class="cnt"
        >仅统计可见 {{ card.rows.length }} / 共 {{ card.total }} 人 · 点「明细」切回</span
      >
      <span v-else-if="showCollegeAgg" class="cnt">汇总基于全部 {{ card.rows.length }} 人（按部门分组）· 点「明细」切回</span>
      <button v-else-if="filteredRows.length > shown" class="more" @click="shown += PAGE">
        加载更多（已显示 {{ pagedRows.length }} / {{ filteredRows.length }}）
      </button>
      <span v-else class="cnt">共 {{ filteredRows.length }} 行<template v-if="card.truncated">（工具侧已截断）</template></span>
    </div>
    <div v-if="firstMission" class="pgolink" @click="onRow({ missionId: firstMission.missionId, missionName: firstMission.missionName })">
      <IconSpan name="arrow-up-right" /> 在页面上查看「{{ firstMission.missionName }}」 ›
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 数据卡（批C-2，原型 .dcard 翻译）：rows 型工具结果的交互式数据原件——
 * 状态筛选 chips（带计数）/表头排序/20 行分页加载/逾期行底色/CSV 导出/行点击页面联动（pgolink）。
 * mission_detail 单行任务走聚合视图：七状态计数格 + 分布柱图（明细↔聚合双模）。
 *
 * <p>数据契约：后端工具统一返回 {asOf, total, rows, truncated}（ToolContracts）；
 * 解析失败或无 rows 时本组件不渲染（父级回退纯文本摘要）——展示的是工具真值，禁前端编数。</p>
 */
import type { AssistantStep } from './useAssistant';
import { parseToolJson } from './toolJson';
import { fmtIsoTime } from './util';
// 批5: 旧任务树 statusColor 退役, 微核心 3 态本地化
type StatusTagType = 'info' | 'success' | 'danger';
const STATUS_LABEL_MAP: Record<string, string> = { DRAFT: '草稿', COLLECTING: '收集中', CLOSED: '已关闭' };
const statusTagType = (s?: string): StatusTagType => ({ DRAFT: 'info', COLLECTING: 'primary', CLOSED: 'info' })[s || ''] || 'info';
import IconSpan from './IconSpan.vue';

const props = defineProps<{ step: AssistantStep }>();
const emit = defineEmits<{ pgoto: [payload: { missionId: string; missionName: string }] }>();

const PAGE = 20;

/* 批I 口径统一：任务状态中文标签+配色不再自造表（旧表 3 态配色语义与权威相反、
 * 2 个标签漂移：IN_DRAFT 草稿中→草稿 / RETURNED 已退回→已退修）——
 * import statusColor.ts 单一真源，色板类型映射到本卡圆点用的十六进制 */
const TAG_HEX: Record<StatusTagType, string> = {
  primary: '#409eff',
  success: '#67c23a',
  info: '#909399',
  warning: '#e6a23c',
  danger: '#f56c6c'
};
const STATUS_NAME = STATUS_LABEL_MAP;
const TODO_TYPE_NAME: Record<string, string> = {
  FILL: '填表',
  APPEND: '追加',
  REVISE: '待修改',
  AUDIT: '待审核',
  DECLARED_DONE: '已报完'
};

interface Col {
  key: string;
  label: string;
  sortable?: boolean;
  mono?: boolean;
  fmt?: (v: unknown) => string;
}

const TOOL_CARDS: Record<string, { title: string; columns: Col[] }> = {
  query_my_todos: {
    title: '我的待办',
    columns: [
      { key: 'missionName', label: '任务' },
      { key: 'todoType', label: '类型', sortable: true, fmt: (v) => TODO_TYPE_NAME[String(v)] ?? String(v ?? '—') },
      { key: 'topicName', label: '科目' },
      { key: 'dueTime', label: '截止', sortable: true, mono: true, fmt: fmtDate }
    ]
  },
  query_deadline_alerts: {
    title: '逾期与临期',
    columns: [
      { key: 'missionName', label: '任务' },
      { key: 'topicName', label: '科目' },
      { key: 'dueTime', label: '截止', sortable: true, mono: true, fmt: fmtDate },
      { key: 'priority', label: '优先级', sortable: true }
    ]
  },
  query_my_missions: {
    title: '我可见的任务',
    columns: [
      { key: 'missionName', label: '任务' },
      { key: '__status', label: '状态', sortable: false },
      { key: '__progress', label: '进度' },
      { key: 'endTime', label: '截止', sortable: true, mono: true, fmt: fmtDate }
    ]
  },
  query_submission_status: {
    title: '科目提交进度',
    columns: [
      { key: 'topicName', label: '科目' },
      { key: 'targetUserCount', label: '目标人数', sortable: true, mono: true },
      { key: 'submittedUserCount', label: '已提交', sortable: true, mono: true },
      { key: 'pendingCount', label: '待审', sortable: true, mono: true },
      { key: 'completedCount', label: '已完成', sortable: true, mono: true }
    ]
  },
  query_unsubmitted: {
    title: '未交名单',
    columns: [
      { key: 'nickName', label: '姓名' },
      { key: 'userName', label: '工号', mono: true },
      { key: 'deptName', label: '部门' }
    ]
  },
  // 批D(AU-20 B)：助手按部门汇总工具的专属卡——服务端全量分组真数，
  // 与未交名单卡上「按部门汇总」本地切换页签（仅可见行）区分开
  query_unsubmitted_by_dept: {
    title: '未交·按部门汇总',
    columns: [
      { key: 'deptName', label: '部门' },
      { key: 'userCount', label: '未交人数', sortable: true, mono: true },
      { key: 'percent', label: '占比', sortable: true, mono: true, fmt: (v) => `${v}%` },
      { key: 'advice', label: '催办建议' }
    ]
  },
  // AU-25(1008 拍板B)：概要行配 10 列真导出（后端 15 字段里挑汇报口径；tile 视图渲染不受影响，
  // columns 仅导出走）；时间列导出统一 fmtDate 人读格式
  query_mission_detail: {
    title: '任务详情',
    columns: [
      { key: 'missionName', label: '任务名' },
      { key: '__status', label: '状态' },
      { key: 'endTime', label: '截止时间' },
      { key: 'publisherName', label: '发布人' },
      { key: 'totalCount', label: '总数' },
      { key: 'completedCount', label: '已完成' },
      { key: 'pendingAuditCount', label: '待审核' },
      { key: 'draftCount', label: '草稿' },
      { key: 'notStartedCount', label: '未开始' },
      { key: 'terminatedCount', label: '已终止' }
    ]
  }
};

function fmtDate(v: unknown): string {
  if (!v) {
    return '—';
  }
  const s = String(v);
  const m = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(s);
  return m ? `${m[1]} ${m[2]}` : s.slice(0, 16);
}

const statusName = (s: unknown) => STATUS_NAME[String(s)] ?? String(s ?? '—');
const dotColor = (s: unknown) => TAG_HEX[statusTagType(String(s) || undefined)];
function pct(r: Record<string, unknown>): number | null {
  const t = Number(r.totalCount ?? 0);
  if (!t) {
    return null;
  }
  return Math.round((Number(r.completedCount ?? 0) / t) * 100);
}

interface ParsedRow extends Record<string, unknown> {
  __k: string;
}

const card = computed(() => {
  const j = parseToolJson(props.step.resultFull);
  const def = TOOL_CARDS[props.step.name];
  if (!j || !def || !Array.isArray(j.rows) || j.rows.length === 0) {
    return null;
  }
  const rows = j.rows.map((r, i) => ({ ...(r as Record<string, unknown>), __k: String(i) }) as ParsedRow);
  return {
    tool: props.step.name,
    title: def.title,
    columns: def.columns,
    asOf: j.asOf ?? '',
    total: j.total ?? rows.length,
    truncated: !!j.truncated,
    userTotal: typeof j.userTotal === 'number' ? j.userTotal : null,
    caliberNote: typeof j.caliberWarning === 'string' ? j.caliberWarning : '',
    rows
  };
});

const isDetail = computed(() => card.value?.tool === 'query_mission_detail');

/** 批D：助手部门汇总工具卡（全量真数；尾链亮出未交总人数+口径提示） */
const isByDept = computed(() => card.value?.tool === 'query_unsubmitted_by_dept');

/** 未交名单卡（批J-3）：「明细 ⇄ 按部门汇总」双模切换（原型 DP-4 dataCard pool 模式） */
const isUnsubmitted = computed(() => card.value?.tool === 'query_unsubmitted');
const mode = ref<'list' | 'agg'>('list');
const showCollegeAgg = computed(() => isUnsubmitted.value && mode.value === 'agg');
/** 部门分组：计数降序+占比+催办建议（k>=30 今天优先催，否则明天催——原型 L1329 口径） */
const collegeAggRows = computed(() => {
  if (!card.value) {
    return [];
  }
  const total = card.value.rows.length;
  const groups = new Map<string, number>();
  card.value.rows.forEach((r) => {
    const k = String(r.deptName ?? '').trim() || '未分部门';
    groups.set(k, (groups.get(k) ?? 0) + 1);
  });
  return [...groups.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([k, n]) => ({
      k,
      n,
      pct: total ? Math.round((n / total) * 100) : 0,
      advice: n >= 30 ? '今天优先催' : '明天催',
      hot: n >= 30
    }));
});

function switchMode(m: 'list' | 'agg') {
  mode.value = m;
  activeBucket.value = '';
}

/** 聚合视图：七状态计数格 + 柱图（原型 dcard 明细↔聚合双模的聚合端） */
const aggCells = computed(() => {
  const r = card.value?.rows[0] ?? {};
  const mk = (label: string, key: string, cls = '') => ({ label, value: r[key] ?? 0, cls });
  return [
    mk('总数', 'totalCount', 'hi'),
    mk('已完成', 'completedCount', 'ok'),
    mk('已提交', 'submittedCount'),
    mk('待审核', 'pendingAuditCount'),
    mk('草稿', 'draftCount'),
    mk('未开始', 'notStartedCount'),
    mk('已终止', 'terminatedCount', 'dim')
  ];
});
const chartBars = computed(() => {
  const cells = aggCells.value;
  const max = Math.max(1, ...cells.map((c) => Number(c.value) || 0));
  return cells.map((c) => ({
    label: c.label,
    value: Number(c.value) || 0,
    pct: Math.max(4, Math.round(((Number(c.value) || 0) / max) * 100)),
    hi: c.cls.includes('hi')
  }));
});

/** 筛选分桶：todos/alerts 按 todoType，missions 按 status */
const bucketKey = computed(() =>
  ['query_my_todos', 'query_deadline_alerts'].includes(card.value?.tool ?? '') ? 'todoType' : card.value?.tool === 'query_my_missions' ? 'status' : ''
);
const buckets = computed(() => {
  if (!card.value || !bucketKey.value) {
    return [];
  }
  const m = new Map<string, number>();
  card.value.rows.forEach((r) => {
    const raw = String(r[bucketKey.value] ?? '');
    const k = bucketKey.value === 'todoType' ? (TODO_TYPE_NAME[raw] ?? raw) : (STATUS_NAME[raw] ?? raw);
    if (k) {
      m.set(k, (m.get(k) ?? 0) + 1);
    }
  });
  return [...m.entries()].map(([key, n]) => ({ key, n })).sort((a, b) => b.n - a.n);
});

const activeBucket = ref('');
const sortKey = ref('');
const sortAsc = ref(true);
const shown = ref(PAGE);

const filteredRows = computed(() => {
  if (!card.value) {
    return [];
  }
  let rows = card.value.rows;
  if (activeBucket.value && bucketKey.value) {
    rows = rows.filter((r) => {
      const raw = String(r[bucketKey.value] ?? '');
      const k = bucketKey.value === 'todoType' ? (TODO_TYPE_NAME[raw] ?? raw) : (STATUS_NAME[raw] ?? raw);
      return k === activeBucket.value;
    });
  }
  if (sortKey.value) {
    const key = sortKey.value;
    const dir = sortAsc.value ? 1 : -1;
    rows = [...rows].sort((a, b) => {
      const av = a[key];
      const bv = b[key];
      const an = Number(av);
      const bn = Number(bv);
      if (!Number.isNaN(an) && !Number.isNaN(bn) && av !== null && bv !== null && av !== '' && bv !== '') {
        return (an - bn) * dir;
      }
      return String(av ?? '').localeCompare(String(bv ?? ''), 'zh') * dir;
    });
  }
  return rows;
});
const pagedRows = computed(() => filteredRows.value.slice(0, shown.value));

const firstMission = computed(() => card.value?.rows.find((r) => r.missionId && r.missionName) ?? null);

function toggleSort(key: string) {
  if (sortKey.value === key) {
    sortAsc.value = !sortAsc.value;
  } else {
    sortKey.value = key;
    sortAsc.value = true;
  }
}

function isOver(r: Record<string, unknown>): boolean {
  const t = r.dueTime ?? r.endTime;
  if (!t) {
    return false;
  }
  return new Date(String(t)).getTime() < Date.now();
}
/** 原型 ddlCls：临期（今天内到期）=soon 行（琥珀底），已过期=over 行（红底） */
function isSoon(r: Record<string, unknown>): boolean {
  const t = r.dueTime ?? r.endTime;
  if (!t || isOver(r)) {
    return false;
  }
  return new Date(String(t)).getTime() - Date.now() < 24 * 3600 * 1000;
}
function rowCls(r: Record<string, unknown>) {
  if (isOver(r)) {
    return 'over';
  }
  return isSoon(r) ? 'soon' : '';
}

function onRow(r: Record<string, unknown>) {
  if (r.missionId && r.missionName) {
    emit('pgoto', { missionId: String(r.missionId), missionName: String(r.missionName) });
  }
}

/** CSV 导出：当前筛选+排序视图（BOM 防 Excel 中文乱码） */
function exportCsv() {
  if (!card.value) {
    return;
  }
  const cols = card.value.columns;
  const head = cols.map((c) => c.label).join(',');
  const lines = filteredRows.value.map((r) =>
    cols
      .map((c) => {
        const v =
          c.key === '__status'
            ? statusName(r.status)
            : c.key === '__progress'
              ? `${r.completedCount ?? 0}/${r.totalCount ?? 0}`
              : /Time$/.test(c.key)
                ? fmtDate(r[c.key])
                : String(r[c.key] ?? '');
        return `"${v.replace(/"/g, '""')}"`;
      })
      .join(',')
  );
  const blob = new Blob(['\uFEFF' + head + '\n' + lines.join('\n')], { type: 'text/csv;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  // AU-25：概要导出标「任务概要」与名单类导出区分，防止老师拿错文件
  a.download = `助手导出-${isDetail.value ? '任务概要' : card.value.title}-${new Date().toISOString().slice(0, 10)}.csv`;
  a.click();
  URL.revokeObjectURL(a.href);
}

/** 批I：ISO 时间格式化收口到公共 util（原本地实现同款口径） */
const fmtTime = fmtIsoTime;

/** 筛选/换卡重置分页与排序（组件按 step 复用实例时防脏态） */
watch(
  () => [props.step.id, activeBucket.value],
  () => {
    shown.value = PAGE;
    mode.value = 'list';
  }
);
</script>

<style lang="scss" scoped>
.dc {
  border: 1px solid var(--dcs-border-color);
  border-radius: 10px;
  background: var(--el-bg-color);
  overflow: hidden;
  font-size: 12.5px;
}
.dh {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-bottom: 1px solid #f0f2f5;
  font-size: 13px;
  font-weight: 600;
  color: var(--dcs-text-primary);

  .asof {
    margin-left: auto;
    font-weight: 400;
    font-size: 11px;
    color: var(--dcs-text-secondary);
    background: var(--el-fill-color-light);
    padding: 1px 8px;
    border-radius: 10px;
  }
  .dex {
    font-size: 11px;
    color: var(--dcs-text-secondary);
    border: none;
    padding: 1px 2px;
    background: none;
    cursor: pointer;

    &:hover {
      color: var(--el-color-primary);
    }
  }
}
.ftools {
  display: flex;
  gap: 6px;
  padding: 6px 10px;
  border-bottom: 1px solid #f0f2f5;
  flex-wrap: wrap;
  align-items: center;

  .fsp {
    flex: 1;
  }

  .dfchip {
    font-size: 11.5px;
    color: var(--dcs-text-secondary);
    background: var(--el-bg-color);
    border: 1px solid var(--dcs-border-color-light);
    border-radius: 10px;
    padding: 1px 8px;
    cursor: pointer;

    i {
      font-style: normal;
      color: var(--dcs-text-placeholder);
      margin-left: 3px;
    }
    &:hover {
      color: var(--el-color-primary);
      border-color: var(--el-color-primary-light-5);
    }
    &.on {
      color: var(--el-color-primary);
      border-color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
    }
  }
  .modec {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    white-space: nowrap;
  }
}
/* 明细表滚动承载：nowrap 列在窄抽屉下超宽时卡内横滚，不撑破抽屉布局 */
.twrap {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}
th {
  background: var(--el-fill-color-light);
  padding: 6px 10px;
  text-align: left;
  color: var(--dcs-text-secondary);
  font-weight: 600;
  font-size: 12px;
  white-space: nowrap;

  &.srt {
    cursor: pointer;
    user-select: none;

    &:hover {
      color: var(--el-color-primary);
    }
  }
  .sarr {
    margin-left: 2px;
  }
}
td {
  padding: 7px 10px;
  border-top: 1px solid #f0f2f5;
  color: var(--dcs-text-regular);
  white-space: nowrap;

  &:first-child {
    white-space: normal;
    min-width: 120px;
  }
  &.mono {
    font-variant-numeric: tabular-nums;
  }
}
tr.dr {
  cursor: pointer;
  transition: background 0.12s;

  &:hover td {
    background: var(--el-color-primary-light-9);
  }
  &.over td {
    background: #fdf1f0;

    &.mono {
      color: var(--dcs-color-danger);
      font-weight: 600;
    }
  }
}
.stw {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;

  i {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    flex: none;
  }
}
.pwrap {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-variant-numeric: tabular-nums;

  .pbar {
    width: 44px;
    height: 4px;
    border-radius: 2px;
    background: var(--el-fill-color);
    overflow: hidden;

    b {
      display: block;
      height: 100%;
      background: var(--el-color-primary);
    }
  }
}
.dfoot {
  padding: 7px 10px;
  text-align: center;
  border-top: 1px solid #f0f2f5;

  .more {
    border: none;
    background: none;
    font-size: 12px;
    color: var(--el-color-primary);
    cursor: pointer;
    white-space: nowrap;

    &:hover {
      text-decoration: underline;
    }
  }
  .cnt {
    font-size: 12px;
    color: var(--dcs-text-secondary);
  }
}
.pgolink {
  font-size: 11.5px;
  color: var(--el-color-primary);
  cursor: pointer;
  padding: 6px 12px;
  border-top: 1px solid #f0f2f5;
  background: var(--el-color-primary-light-9);
  text-align: center;

  &:hover {
    background: var(--el-color-primary-light-8);
  }
}

/* 按部门汇总视图（DP-4）：计数 b 主色加粗、建议列 hot=今天优先催走 warning 色；
   固定列宽+部门列省略——长部门名（教学单位-技术研发中心）不撑爆建议列（视觉复核实锤） */
.cagg {
  /* 批D回修：截断名单上的本地分布警示条（EP 警告色，不冒充全量） */
  .aggwarn {
    margin: 8px 10px 2px;
    padding: 5px 10px;
    border: 1px solid #f5dab1;
    border-radius: 6px;
    background: #fdf6ec;
    color: #b88230;
    font-size: 11.5px;
    line-height: 1.6;
  }
  table {
    table-layout: fixed;
  }
  th:nth-child(1),
  td:nth-child(1) {
    width: 40%;
  }
  th:nth-child(4),
  td:nth-child(4) {
    width: 22%;
  }
  td:first-child {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  td:nth-child(2),
  td:nth-child(3) {
    white-space: nowrap;
  }
  td b {
    color: var(--dcs-text-primary);
  }
  td.hot {
    color: var(--dcs-color-warning, #e6a23c);
    font-weight: 600;
  }
}

/* 聚合视图（mission_detail）：计数格 + 柱图 */
.agg {
  padding: 10px 12px;
}
.agg-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(76px, 1fr));
  gap: 8px;
  margin-bottom: 10px;

  .acell {
    border: 1px solid #f0f2f5;
    border-radius: 8px;
    padding: 6px 8px;
    display: flex;
    flex-direction: column;
    gap: 2px;
    font-variant-numeric: tabular-nums;

    b {
      font-size: 16px;
      color: var(--dcs-text-primary);
    }
    > span {
      font-size: 11px;
      color: var(--dcs-text-secondary);
    }
    &.hi b {
      color: var(--el-color-primary);
    }
    &.ok b {
      color: var(--dcs-color-success);
    }
    &.dim b {
      color: var(--dcs-text-placeholder);
    }
  }
}
.chart {
  border: 1px solid #f0f2f5;
  border-radius: 10px;

  .cbody {
    display: flex;
    align-items: flex-end;
    gap: 12px;
    height: 140px;
    padding: 14px 18px 2px;
  }
  .cbar {
    flex: 1;
    display: flex;
    flex-direction: column;
    justify-content: flex-end;
    align-items: center;
    gap: 4px;
    height: 100%;

    .v {
      font-size: 10.5px;
      color: var(--dcs-text-secondary);
    }
    .b {
      width: 100%;
      max-width: 36px;
      border-radius: 4px 4px 0 0;
      background: linear-gradient(#a0cfff, #79bbff);
    }
    &.today .b {
      background: linear-gradient(#79bbff, var(--el-color-primary));
    }
    &.today .v {
      color: var(--el-color-primary);
      font-weight: 600;
    }
  }
  .clab {
    display: flex;
    gap: 12px;
    padding: 2px 18px 8px;

    > span {
      flex: 1;
      text-align: center;
      font-size: 10.5px;
      color: var(--dcs-text-placeholder);
    }
  }
}
</style>
