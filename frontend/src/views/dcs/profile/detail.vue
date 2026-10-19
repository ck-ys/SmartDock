<template>
  <div class="app-container">
    <el-card v-loading="loading" shadow="never">
      <template #header>
        <div class="card-header">
          <span>画像详情：{{ source }} / {{ subjectType }} / {{ subjectId }}</span>
          <el-button @click="back">返回</el-button>
        </div>
      </template>

      <template v-if="snap">
        <el-descriptions :column="3" border style="margin-bottom: 16px">
          <el-descriptions-item label="总分">{{ snap.totalScore ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="计算时间">{{ snap.calcTime || '—' }}</el-descriptions-item>
          <el-descriptions-item label="快照链前驱">{{ snap.prevSnapshotId || '（首快照）' }}</el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">维度分数</el-divider>
        <el-table :data="dimensions">
          <el-table-column prop="dimensionCode" label="维度码" min-width="130" />
          <el-table-column prop="dimensionName" label="维度名" min-width="140" />
          <el-table-column prop="score" label="分数" width="100" />
          <el-table-column prop="level" label="档位" width="100" />
        </el-table>

        <el-divider content-position="left">最近快照链尾（新→旧）</el-divider>
        <el-timeline>
          <el-timeline-item v-for="h in history" :key="h.snapshotId" :timestamp="h.calcTime">
            总分 {{ h.totalScore ?? '—' }}
          </el-timeline-item>
        </el-timeline>
      </template>
      <el-empty v-else-if="!loading" description="该主体暂无画像快照（先产生事件并触发重算）" />
    </el-card>
  </div>
</template>

<script setup name="DcsProfileDetail" lang="ts">
import { getScore, type ProfileDimensionDetail, type ProfileSnapshot } from '@/api/dcs/core/profile';

const route = useRoute();
const router = useRouter();

const source = computed(() => String(route.params.source));
const subjectId = computed(() => String(route.params.subjectId));
/** 列表页把主体类型放在 query 里; 老链接没有该参数时按 user 兼容 */
const subjectType = computed(() => String(route.query.subjectType || 'user'));

const loading = ref(false);
const snap = ref<ProfileSnapshot | null>(null);
const history = ref<ProfileSnapshot[]>([]);

const back = () => router.back();

/** 后端快照存的是 dimension_detail(JSONB 字符串: dimCode -> {score,dimName,...})，
 *  表格需要扁平行；这里统一成 dimensionCode/dimensionName/score 供展示 */
const dimensions = computed(() => {
  const raw = snap.value?.dimensionDetail;
  if (!raw) return [];
  let obj: Record<string, ProfileDimensionDetail>;
  try {
    obj = typeof raw === 'string' ? JSON.parse(raw) : raw;
  } catch {
    return [];
  }
  return Object.entries(obj || {})
    .map(([code, v]) => ({
      dimensionCode: code,
      dimensionName: v?.dimName ?? code,
      score: v?.score ?? '—',
      level: v?.tags?.missing ? '缺证据' : ''
    }))
    .sort((a, b) => a.dimensionCode.localeCompare(b.dimensionCode));
});

const load = async () => {
  loading.value = true;
  try {
    const res = await getScore(source.value, subjectId.value, subjectType.value);
    snap.value = res.latest;
    history.value = res.history || [];
  } finally { loading.value = false; }
};

onMounted(load);
// 同组件内路由参数变化(列表页跳另一主体)必须重载, 否则停留在旧主体
watch([source, subjectId, subjectType], ([s, i, t], [os, oi, ot]) => {
  if ((s && s !== os) || (i && i !== oi) || (t && t !== ot)) load();
});
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>
