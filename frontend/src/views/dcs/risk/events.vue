<template>
  <div class="risk-events">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-text">
            <span class="page-title">风险事件流水</span>
            <p class="page-sub">用户/行为的每一次风险记分留痕；可用风险分下限滤掉低分噪音，点开详情查看完整原文。</p>
          </div>
          <el-button link :icon="Refresh" @click="loadEvents">刷新</el-button>
        </div>
      </template>

      <!-- 筛选 -->
      <el-form :inline="true" :model="query" class="mb-3 filter-form">
        <el-form-item label="事件类型">
          <el-select v-model="query.eventType" placeholder="全部" clearable style="width: 160px">
            <el-option v-for="(label, key) in EVENT_TYPE_LABELS" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <template #label>用户ID<span class="filter-hint">(精确)</span></template>
          <el-input v-model="query.userId" placeholder="输入完整ID" clearable style="width: 150px" />
        </el-form-item>
        <el-form-item>
          <template #label>IP<span class="filter-hint">(精确)</span></template>
          <el-input v-model="query.ip" placeholder="输入完整IP" clearable style="width: 150px" />
        </el-form-item>
        <el-form-item label="风险分≥">
          <el-input-number v-model="query.minRiskScore" :min="0" :max="9999" controls-position="right" style="width: 120px" />
        </el-form-item>
        <el-form-item label="按分排序">
          <el-switch v-model="query.orderByScore" />
        </el-form-item>
        <el-form-item label="时间区间">
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="x"
            style="width: 320px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="onSearch">查询</el-button>
          <el-button :icon="RefreshLeft" @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="eventList" stripe>
        <el-table-column label="时间" prop="createdTime" width="160">
          <template #default="{ row }">{{ formatTime(row.createdTime) }}</template>
        </el-table-column>
        <el-table-column label="事件类型" width="120">
          <template #default="{ row }">
            <el-tag :type="EVENT_TYPE_COLORS[row.eventType]" effect="plain" size="small">
              {{ EVENT_TYPE_LABELS[row.eventType] || row.eventType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" prop="source" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small">{{ EVENT_SOURCE_LABELS[row.source] || row.source }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="用户" width="160" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.nickName || row.userName || row.userId || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="IP" prop="ip" width="140" show-overflow-tooltip />
        <!-- 列表态打码(2026-08-23 拍板: 防肩窥/截图外泄), 详情弹窗内明文 -->
        <el-table-column label="详情" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ maskSensitiveDetail(row.detail || '-') }}</template>
        </el-table-column>
        <el-table-column label="风险分" prop="riskScore" width="90" align="center">
          <template #default="{ row }">
            <el-tag type="danger" size="small">+{{ row.riskScore }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openDetail(row)">查看详情</el-button>
            <el-button v-if="row.userId" type="primary" link size="small" @click="viewProfile(row.userId)">查看画像</el-button>
          </template>
        </el-table-column>
      </el-table>

      <pagination v-show="total > 0" :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="loadEvents" />
    </el-card>

    <!-- 事件详情弹窗 -->
    <el-dialog v-model="detailDialog.visible" title="事件详情" width="560px" append-to-body>
      <el-descriptions v-if="detailDialog.data" :column="2" border>
        <el-descriptions-item label="事件 ID">{{ detailDialog.data.eventId }}</el-descriptions-item>
        <el-descriptions-item label="时间">{{ formatTime(detailDialog.data.createdTime) }}</el-descriptions-item>
        <el-descriptions-item label="事件类型">
          <el-tag :type="EVENT_TYPE_COLORS[detailDialog.data.eventType]" effect="plain" size="small">
            {{ EVENT_TYPE_LABELS[detailDialog.data.eventType] || detailDialog.data.eventType }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="来源">{{ EVENT_SOURCE_LABELS[detailDialog.data.source] || detailDialog.data.source }}</el-descriptions-item>
        <el-descriptions-item v-if="detailDialog.data.hitWord" label="命中词">{{ maskToken(detailDialog.data.hitWord) }}</el-descriptions-item>
        <el-descriptions-item label="用户">{{
          detailDialog.data.nickName || detailDialog.data.userName || detailDialog.data.userId || '-'
        }}</el-descriptions-item>
        <el-descriptions-item label="IP">{{ detailDialog.data.ip || '-' }}</el-descriptions-item>
        <el-descriptions-item label="风险分">
          <el-tag type="danger" size="small">+{{ detailDialog.data.riskScore }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="引用 ID">
          <template v-if="detailDialog.data.eventType === 'TASK_CONTENT_RISK' && detailDialog.data.refId">
            <el-link type="primary" :underline="false" @click="gotoMission(detailDialog.data.refId)">
              {{ detailDialog.data.refId }}（查看任务）
            </el-link>
          </template>
          <template v-else>{{ detailDialog.data.refId || '-' }}</template>
        </el-descriptions-item>
        <el-descriptions-item label="详情" :span="2">{{ detailDialog.data.detail || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-skeleton v-else :rows="4" animated />
      <template #footer>
        <el-button @click="detailDialog.visible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { Refresh, RefreshLeft, Search } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import { listEvents, getEventDetail, EVENT_TYPE_LABELS, EVENT_TYPE_COLORS, EVENT_SOURCE_LABELS, type RiskEventVO } from '@/api/dcs/risk';
import { parseDcsServerTime } from './riskDashboardPresentation';
import { parseTime } from '@/utils/ruoyi';
import { maskSensitiveDetail, maskToken } from './sensitiveMask';

defineOptions({ name: 'DcsRiskEvents' });

const router = useRouter();

/** 机检事件深链(收尾批 Q8, 2026-09-23): refId=missionId 跳任务详情; 行级定位需 detailId,
 * 导入时机检先于建行拿不到行号(技术死结), 到任务后靠列表定位——拍板 A 方案 */
const gotoMission = (missionId: number | string) => {
  detailDialog.visible = false;
  router.push({ name: 'DcsMissionDetail', params: { id: String(missionId) } });
};

const query = reactive({
  pageNum: 1,
  pageSize: 20,
  eventType: '',
  userId: '',
  ip: '',
  minRiskScore: undefined as number | undefined,
  orderByScore: false
});
// 时间区间：el-date-picker datetimerange 绑定 [startTime, endTime]（毫秒时间戳字符串）
// 独立于 query，提交查询时合并到 query 参数
const timeRange = ref<[string, string] | null>(null);
const eventList = ref<RiskEventVO[]>([]);
const total = ref(0);
const loading = ref(false);

const formatTime = (t?: string) => {
  // G13a(2026-09-16): 统一日期格式为连字符, 不再用 toLocaleString 的斜杠形态
  const d = parseDcsServerTime(t);
  return d ? parseTime(d, '{y}-{m}-{d} {h}:{i}') : '';
};

const loadEvents = async () => {
  loading.value = true;
  try {
    const r: any = await listEvents({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      eventType: query.eventType || undefined,
      userId: query.userId || undefined,
      ip: query.ip || undefined,
      // 合并时间区间参数
      startTime: timeRange.value?.[0] || undefined,
      endTime: timeRange.value?.[1] || undefined
    });
    if (r.code === 200) {
      eventList.value = r.rows || [];
      total.value = r.total || 0;
    }
  } finally {
    loading.value = false;
  }
};

const onSearch = () => {
  query.pageNum = 1;
  loadEvents();
};
const onReset = () => {
  query.eventType = '';
  query.userId = '';
  query.ip = '';
  query.minRiskScore = undefined;
  query.orderByScore = false;
  timeRange.value = null;
  onSearch();
};

// 事件详情弹窗
const detailDialog = reactive<{ visible: boolean; data: RiskEventVO | null }>({
  visible: false,
  data: null
});
const openDetail = async (row: RiskEventVO) => {
  detailDialog.visible = true;
  detailDialog.data = row; // 先展示列表已有数据避免空白
  try {
    const r: any = await getEventDetail(row.eventId);
    if (r.code === 200 && r.data) {
      detailDialog.data = r.data;
    }
  } catch {
    // 详情接口失败时保留列表数据
  }
};

const viewProfile = (userId: number | string) => {
  router.push(`/dcs/profile-detail/risk/${userId}?subjectType=user`);
};

onMounted(loadEvents);
</script>

<style scoped lang="scss">
.risk-events {
  padding: 12px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.filter-hint {
  color: var(--dcs-text-secondary);
  font-size: 11px;
}
.filter-form :deep(.el-form-item__label) {
  white-space: nowrap;
}
</style>
