<template>
  <div class="p-2">
    <el-row :gutter="10">
      <el-col :span="24" class="card-box">
        <el-card shadow="hover">
          <template #header>
            <Monitor style="width: 1em; height: 1em; vertical-align: middle" />
            <span style="vertical-align: middle">基本信息</span>
          </template>

          <div class="el-table el-table--enable-row-hover el-table--medium ui-table cache-info">
            <table style="width: 100%">
              <tbody>
                <tr>
                  <td class="el-table__cell is-leaf">
                    <div class="cell">Redis版本</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div v-if="cache.info" class="cell">{{ cache.info.redis_version }}</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div class="cell">运行模式</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div v-if="cache.info" class="cell">{{ cache.info.redis_mode === 'standalone' ? '单机' : '集群' }}</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div class="cell">端口</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div v-if="cache.info" class="cell">{{ cache.info.tcp_port }}</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div class="cell">客户端数</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div v-if="cache.info" class="cell">{{ cache.info.connected_clients }}</div>
                  </td>
                </tr>
                <tr>
                  <td class="el-table__cell is-leaf">
                    <div class="cell">运行时间(天)</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div v-if="cache.info" class="cell">{{ cache.info.uptime_in_days }}</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div class="cell">使用内存</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div v-if="cache.info" class="cell">{{ cache.info.used_memory_human }}</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div class="cell">累计CPU(秒)</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div v-if="cache.info" class="cell">{{ (parseFloat(cache.info.used_cpu_sys) + parseFloat(cache.info.used_cpu_user)).toFixed(2) }}</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div class="cell">内存配置</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div v-if="cache.info" class="cell">{{ cache.info.maxmemory_human }}</div>
                  </td>
                </tr>
                <tr>
                  <td class="el-table__cell is-leaf">
                    <div class="cell">AOF是否开启</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div v-if="cache.info" class="cell">{{ cache.info.aof_enabled === '0' ? '否' : '是' }}</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div class="cell">RDB是否成功</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div v-if="cache.info" class="cell">{{ cache.info.rdb_last_bgsave_status === 'ok' ? '成功' : '失败' }}</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div class="cell">Key数量</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div v-if="cache.dbSize" class="cell">{{ cache.dbSize }}</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div class="cell">网络入口/出口</div>
                  </td>
                  <td class="el-table__cell is-leaf">
                    <div v-if="cache.info" class="cell">
                      {{ cache.info.instantaneous_input_kbps }}KB/s/{{ cache.info.instantaneous_output_kbps }}KB/s
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" class="card-box">
        <el-card shadow="hover">
          <template #header>
            <Histogram style="width: 1em; height: 1em; vertical-align: middle" />
            <span style="vertical-align: middle">命令统计</span>
          </template>
          <div class="el-table el-table--enable-row-hover el-table--medium">
            <div ref="commandstats" style="height: 420px" />
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" class="card-box">
        <el-card shadow="hover">
          <template #header>
            <Odometer style="width: 1em; height: 1em; vertical-align: middle" /> <span style="vertical-align: middle">内存信息</span>
          </template>
          <div class="el-table el-table--enable-row-hover el-table--medium">
            <!-- 2026-10-30 题2（方案A）：没设内存上限（maxmemory=0）就不画指针表盘——永远贴 0 的图比没有图更误导 -->
            <div v-if="hasMemoryLimit" ref="usedmemory" style="height: 420px" />
            <div v-else-if="cache.info" class="mem-nolimit">
              <p class="mem-nolimit__label">内存用量</p>
              <p class="mem-nolimit__value">{{ cache.info.used_memory_human || '—' }}</p>
              <p class="mem-nolimit__hint">Redis 没设内存上限（maxmemory 为 0B），没有可比的「满格」基准，所以这里只给读数。配了上限后刷新本页会换成占比表盘。</p>
            </div>
            <div v-else class="mem-nolimit">
              <p class="mem-nolimit__label">内存信息</p>
              <p class="mem-nolimit__hint">暂时取不到 Redis 内存信息，请稍后刷新页面重试。</p>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup name="Cache" lang="ts">
import { getCache } from '@/api/monitor/cache';
import * as echarts from 'echarts';
import { CacheVO } from '@/api/monitor/cache/types';
import { useChartTokens } from '@/utils/chart-theme';

const cache = ref<Partial<CacheVO>>({});
const commandstats = ref();
const usedmemory = ref();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const ct = useChartTokens();
let commandstatsIns: echarts.ECharts | null = null;
let usedmemoryIns: echarts.ECharts | null = null;

/**
 * 2026-10-30 题2（方案A）：Redis INFO 的 *_human 带单位（"3.20G"/"512.00M"/"0B"），
 * 直接 parseFloat 会把 3.20G 读成 3.2。这里统一换算成 MB，读数与量程共用一把尺子。
 */
const memToMb = (human?: string): number => {
  const text = (human || '').trim();
  const value = parseFloat(text);
  if (!Number.isFinite(value)) return 0;
  const unit = text.replace(/[0-9.]/g, '').toUpperCase();
  if (unit.startsWith('T')) return value * 1024 * 1024;
  if (unit.startsWith('P')) return value * 1024 * 1024 * 1024;
  if (unit.startsWith('G')) return value * 1024;
  if (unit.startsWith('K')) return value / 1024;
  if (unit.startsWith('B')) return value / 1024 / 1024;
  return value;
};
const usedMemoryText = computed(() => cache.value.info?.used_memory_human || '—');
const maxMemoryText = computed(() => cache.value.info?.maxmemory_human || '—');
const usedMemoryMb = computed(() => memToMb(cache.value.info?.used_memory_human));
const maxMemoryMb = computed(() => memToMb(cache.value.info?.maxmemory_human));
/** 0 = 不限（Redis 语义），此时表盘没有意义 */
const hasMemoryLimit = computed(() => maxMemoryMb.value > 0);

/** 2026-11-01 Q8=乙：命令统计从玫瑰图改横向条形 TOP10——
    玫瑰图扇区角度不可比、命令名还得靠图例对，换成条形后「谁在吃 Redis」一眼看得出 */
const topCommands = computed(() => {
  // value 后端是字符串（INFO 里取的），这里保留联合类型，排序处统一 Number()
  const list = (cache.value.commandStats || []) as Array<{ name: string; value: string | number }>;
  return [...list].sort((a, b) => Number(b.value) - Number(a.value)).slice(0, 10);
});
const commandTotal = computed(() => (cache.value.commandStats || []).reduce((sum: number, x: any) => sum + (Number(x?.value) || 0), 0));

/* 画布读不到 CSS 变量：文字/配色从主题 token 取，切深色时重画 */
const renderCharts = () => {
  if (!commandstats.value || !cache.value.commandStats) {
    return;
  }
  /* 类目轴自下而上画：反转一次，让调用次数最多的排在最上面 */
  const top = topCommands.value.slice().reverse();
  const total = commandTotal.value || 1;
  commandstatsIns = commandstatsIns || echarts.init(commandstats.value);
  /* 一条命令都没有时别画空坐标系：画布上留一句人话（Q8 回归席 P2-3） */
  if (!top.length) {
    commandstatsIns.setOption(
      { graphic: [{ type: 'text', left: 'center', top: 'middle', style: { text: '还没有命令统计', fill: ct.value.faint, fontSize: 13 } }] },
      true
    );
    return;
  }
  commandstatsIns.setOption(
    {
      color: ct.value.series,
      textStyle: { color: ct.value.faint },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: any) => {
          const it = Array.isArray(params) ? params[0] : params;
          const v = Number(it?.value) || 0;
          return `${it?.name}<br/>调用 ${v} 次（占 ${((v / total) * 100).toFixed(1)}%，全量 ${commandTotal.value} 次）`;
        }
      },
      grid: { left: 4, right: 64, top: 6, bottom: 6, containLabel: true },
      xAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: ct.value.rule } },
        // 数值已经标在条尾，横轴刻度只留网格线，省一条重复读数的文字带
        axisLabel: { show: false },
        axisLine: { show: false },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'category',
        data: top.map((x) => x.name),
        axisLabel: { color: ct.value.muted, width: 96, overflow: 'truncate' },
        axisLine: { show: false },
        axisTick: { show: false }
      },
      series: [
        {
          name: '命令',
          type: 'bar',
          barMaxWidth: 14,
          data: top.map((x) => Number(x.value) || 0),
          itemStyle: { borderRadius: [0, 3, 3, 0] },
          /* 条尾数值压成万/亿：9 位数（9 位起 64px 装不下）原来会被画布裁掉末位，
             读出来正好还像个正常数字。精确值在 tooltip 里 */
          label: {
            show: true,
            position: 'right',
            color: ct.value.muted,
            formatter: (p: any) => {
              const v = Number(p.value) || 0;
              if (v >= 1e8) return `${(v / 1e8).toFixed(2)}亿`;
              if (v >= 1e4) return `${(v / 1e4).toFixed(1)}万`;
              return String(v);
            }
          },
          animationEasing: 'cubicInOut',
          animationDuration: 1000
        }
      ]
    },
    true
  );
  // 2026-10-30 题2（方案A）：没设上限就没有表盘，图实例也别初始化（v-if 已把容器摘掉）
  if (!usedmemory.value) {
    usedmemoryIns?.dispose();
    usedmemoryIns = null;
    return;
  }
  usedmemoryIns = usedmemoryIns || echarts.init(usedmemory.value);
  usedmemoryIns.setOption(
    {
      color: ct.value.series,
      textStyle: { color: ct.value.faint },
      tooltip: {
        formatter: '{b} <br/>' + usedMemoryText.value + ' / 上限 ' + maxMemoryText.value
      },
      series: [
        {
          name: '内存',
          type: 'gauge',
          min: 0,
          // 量程跟 maxmemory 走，读数与量程统一换算成 MB——比例才是真实占比
          max: maxMemoryMb.value,
          // 仪表盘的读数与标题不跟 textStyle 走，深色下默认深灰字会看不见
          detail: { formatter: usedMemoryText.value, color: ct.value.ink },
          title: { color: ct.value.muted },
          // 刻度按 GB 读数，避免 819.2/1638.4 这类 MB 数字排满一圈
          axisLabel: { color: ct.value.faint, formatter: (v: number) => (v >= 1024 ? `${Math.round(v / 1024)}G` : `${Math.round(v)}M`) },
          data: [{ value: usedMemoryMb.value, name: '已用' }]
        }
      ]
    },
    true
  );
};

const resizeCharts = () => {
  commandstatsIns?.resize();
  usedmemoryIns?.resize();
};

const getList = async () => {
  proxy?.$modal.loading('正在加载缓存监控数据，请稍候！');
  let res: any = null;
  try {
    res = await getCache();
  } finally {
    // 失败也必须关遮罩：load 是全屏 lock，漏关会把整页锁死（批2 三审 P1）
    proxy?.$modal.closeLoading();
  }
  cache.value = res?.data || {};
  // v-if 要等这一帧 DOM 更新后才决定表盘容器在不在，先等渲染再画图（否则配了上限也不出图）
  await nextTick();
  renderCharts();
};

watch(() => ct.value, renderCharts);

onMounted(() => {
  getList();
  window.addEventListener('resize', resizeCharts);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts);
  commandstatsIns?.dispose();
  usedmemoryIns?.dispose();
  commandstatsIns = null;
  usedmemoryIns = null;
});
</script>

<style scoped>
/* 2026-10-30 题2：未设上限时的数字卡，替代指针表盘（同卡片同高度，版面不塌） */
/* 手机档：8 列信息表塞 384px 会把标签折成竖排、数值截半；改成表内横滚（与全站表格口径一致） */
@media (max-width: 992px) {
  .cache-info {
    overflow-x: auto;
  }

  .cache-info table {
    min-width: 720px;
  }
}

.mem-nolimit {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 420px;
  padding: 0 32px;
  text-align: center;
}

.mem-nolimit__label {
  margin: 0;
  color: var(--app-muted);
  font-size: var(--dcs-font-sm);
}

.mem-nolimit__value {
  margin: 0;
  color: var(--app-text);
  font-size: 34px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}

.mem-nolimit__hint {
  margin: 0;
  max-width: 340px;
  color: var(--app-faint);
  font-size: var(--dcs-font-sm);
  line-height: 1.6;
}
</style>
