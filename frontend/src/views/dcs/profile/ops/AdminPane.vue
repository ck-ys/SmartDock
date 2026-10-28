<template>
  <div class="admin-pane">
    <div class="pane-toolbar">
      <el-select v-model="source" class="source-select" @change="onSourceChanged">
        <el-option v-for="s in sourceOpts" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
    </div>

    <el-tabs v-model="tab" class="engine-tabs">
      <!-- ---------------------------------------------------------- 全量重算 -->
      <el-tab-pane label="全量重算" name="recalc">
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-head">
              <span>经典评分链重算</span>
              <span class="muted">ProfileScoreEngine → 画像列表 / 详情页生效</span>
            </div>
          </template>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="重算走经典评分链：画像列表与详情页的分数会更新；RFM 模型页读的是模型链快照，不经过这里。"
            style="margin-bottom: 12px"
          />
          <div class="recalc-actions">
            <el-button v-hasPermi="['profile:recalc:run']" type="primary" :loading="recalcStarting" @click="runRecalc"> 触发重算 </el-button>
            <el-button v-hasPermi="['profile:recalc:run']" v-if="batch && batch.status === 'FAILED'" :loading="resuming" @click="resumeRecalc">
              断点续跑
            </el-button>
          </div>
          <el-descriptions v-if="batch" :column="3" border size="small" style="margin-top: 12px">
            <el-descriptions-item label="批次">{{ batch.batchId }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small" :type="statusTone(batch.status)" effect="light">{{ statusText(batch.status) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="主体进度">{{ batch.doneSubjects ?? 0 }} / {{ batch.totalSubjects ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="错误信息" :span="3">
              <span class="mono small">{{ batch.errorMsg || '—' }}</span>
            </el-descriptions-item>
          </el-descriptions>
          <el-empty v-else :image-size="60" description="还没有发起过重算批次" />
        </el-card>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 协议与 Schema -->
      <el-tab-pane label="协议与 Schema" name="protocol" lazy>
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-head">
              <span>接入协议</span>
              <div class="head-actions">
                <el-button v-hasPermi="['profile:protocol:edit']" :loading="protocolValidating" @click="validateCurrentProtocol"
                  >校验当前协议</el-button
                >
                <el-button v-hasPermi="['profile:protocol:edit']" type="primary" :loading="protocolPublishing" @click="publishCurrentProtocol">
                  重新发布
                </el-button>
              </div>
            </div>
          </template>
          <el-descriptions v-if="protocol" :column="3" border size="small">
            <el-descriptions-item label="版本">{{ protocol.version || '—' }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small" effect="light">{{ protocol.status || '—' }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="发布时间">{{ formatTime(protocol.publishedAt) }}</el-descriptions-item>
          </el-descriptions>
          <el-input v-model="protocolManifestText" type="textarea" :rows="12" readonly class="mono" spellcheck="false" style="margin-top: 10px" />
          <el-alert
            v-if="protocolValidateResult"
            :type="protocolValidateResult.valid ? 'success' : 'error'"
            :closable="false"
            show-icon
            :title="protocolValidateResult.valid ? '校验通过' : '校验未通过'"
            :description="protocolValidateResult.errors?.join('；') || ''"
            style="margin-top: 10px"
          />
        </el-card>

        <el-card shadow="never" class="panel-card" style="margin-top: 14px">
          <template #header>
            <div class="panel-head">
              <span>事件 Schema（{{ schemas.length }}）</span>
            </div>
          </template>
          <el-table v-loading="schemaLoading" class="ui-table" :data="schemas">
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">该源还没有注册事件 Schema</p>
                <p class="ui-empty__hint">未注册的事件走宽松校验；注册后按 Schema 严格校验</p>
              </div>
            </template>
            <el-table-column prop="eventName" label="事件名" min-width="140" />
            <el-table-column prop="schemaVersion" label="版本" width="100" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" effect="light">{{ row.status || '—' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" width="170">
              <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
            </el-table-column>
          </el-table>

          <div class="schema-form">
            <div class="drawer-section-title">注册 / 更新 Schema</div>
            <div class="filter-bar">
              <el-input v-model="schemaForm.eventName" placeholder="事件名，如 task_submit" style="width: 200px" />
              <el-input v-model="schemaForm.schemaVersion" placeholder="版本，如 1-0-0" style="width: 140px" />
              <el-button v-hasPermi="['profile:schema:query']" :loading="schemaValidating" @click="validateSchema">校验</el-button>
              <el-button v-hasPermi="['profile:schema:edit']" type="primary" :loading="schemaSaving" @click="saveSchema">保存</el-button>
            </div>
            <el-input
              v-model="schemaFormText"
              type="textarea"
              :rows="6"
              class="mono"
              spellcheck="false"
              placeholder='Schema JSON，如 {"type":"object","required":["title"],"properties":{...}}'
            />
            <el-alert
              v-if="schemaValidateResult"
              :type="schemaValidateResult.valid ? 'success' : 'error'"
              :closable="false"
              show-icon
              :title="schemaValidateResult.valid ? '校验通过' : '校验未通过'"
              :description="schemaValidateResult.errors?.join('；') || ''"
              style="margin-top: 8px"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 测试发送（原演示灌数） -->
      <el-tab-pane label="测试发送" name="seed" lazy>
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-head">
              <span>批量发布事件</span>
              <span class="muted">接入调试用：粘贴事件数组（事件信封格式），逐条校验幂等；不要用它造演示数据——产品里每个数字都应来自真实业务操作</span>
            </div>
          </template>
          <div class="filter-bar">
            <el-button v-hasPermi="['profile:event:publish']" type="primary" :loading="seeding" @click="publishEvents">发布事件</el-button>
            <el-button @click="fillSeedExample">填入示例</el-button>
          </div>
          <el-input
            v-model="seedText"
            type="textarea"
            :rows="14"
            class="mono"
            spellcheck="false"
            placeholder='[{"eventName":"...","eventUid":"...","source":"...","subjectType":"user","subjectId":"...","occurredTime":"...","props":{}}]'
          />
          <el-alert
            v-if="seedResult"
            :type="seedOk ? 'success' : 'error'"
            :closable="false"
            show-icon
            :title="seedOk ? '发布完成' : '发布失败'"
            :description="JSON.stringify(seedResult)"
            style="margin-top: 10px"
          />
          <!-- 第二批接入闭环: 发完就地引导去画像页看效果(清单缓存已失效, 回页自动重拉);
               seedOk 初始即 true——没发过事件前不显示(三审 dsflash P2) -->
          <div v-if="seedResult && seedOk" style="margin-top: 10px">
            <el-button type="primary" @click="goSeeResult">去画像页看效果</el-button>
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup name="DcsProfileAdminPane" lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  getProtocolCurrent,
  getRecalcBatch,
  listEventSchemas,
  publishProfileEvents,
  publishProtocol,
  registerEventSchema,
  resumeRecalcBatch,
  triggerRecalc,
  validateEventSchema,
  validateProtocol,
  type ProfileProtocol,
  type ProfileRecalcBatch,
  type ProfileSchemaRegistry
} from '@/api/dcs/core/profile';
import { useSourceOptions, autoPickSource, invalidateSources } from '@/composables/useProfileSources';

const { list: sourceOpts, ensureSources } = useSourceOptions('subject');
const source = ref('dcs');
// 真实数据优先: 首挂单轮加载(三审 dspro P1)——onMounted 等清单→autoPick→一次 loadAll, 不再双载
const initialSource = source.value;

// 子 tab 深链: 空态引导的「测试发送」直达 ?sub=seed。
// 单源重构 M1 D 片: 「来源管理」页签隐藏(单源下无意义, 第二步批整块退役后端);
// 旧深链 ?sub=source 落回默认 recalc
const route = useRoute();
const SUB_TABS = ['recalc', 'protocol', 'seed'];
const subTabOf = (v: unknown) => (typeof v === 'string' && SUB_TABS.includes(v) ? v : 'recalc');
const tab = ref(subTabOf(route.query.sub));
// 三审 dspro P2: keep-alive 下二次导航同路由只变 query, onMounted 不会再跑
watch(
  () => route.query.sub,
  (v) => {
    const next = subTabOf(v);
    if (next !== tab.value) tab.value = next;
  }
);

// ---------------- 全量重算 ----------------
const batch = ref<ProfileRecalcBatch | null>(null);
const recalcStarting = ref(false);
const resuming = ref(false);
let pollTimer: ReturnType<typeof setInterval> | null = null;

const statusText = (s?: string) => (({ RUNNING: '重算中', DONE: '完成', FAILED: '失败' }) as Record<string, string>)[s || ''] || s || '—';
const statusTone = (s?: string): 'info' | 'success' | 'danger' => (({ RUNNING: 'info', DONE: 'success', FAILED: 'danger' }) as Record<string, 'info' | 'success' | 'danger'>)[s || ''] || 'info';

const stopPoll = () => {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = null;
};

const pollBatch = () => {
  stopPoll();
  const id = String(batch.value?.batchId ?? '');
  if (!id) return;
  pollTimer = setInterval(async () => {
    // 三审 dspro P1: 批次被切源清掉/换成新批次后, 旧轮询立即退场,
    // 不然旧源的「重算中」状态会一直挂在新源界面上
    if (!batch.value?.batchId || String(batch.value.batchId) !== id) {
      stopPoll();
      return;
    }
    try {
      const b = await getRecalcBatch(id);
      batch.value = b;
      if (b.status !== 'RUNNING') stopPoll();
    } catch {
      stopPoll();
    }
  }, 2000);
};

/** 三审 dspro P1: 切源=换一份配置——旧源的重算批次与轮询不清掉, 新源会挂着旧状态 */
const onSourceChanged = () => {
  stopPoll();
  batch.value = null;
  loadAll();
};

const runRecalc = async () => {
  await ElMessageBox.confirm(`确认对源 ${source.value} 发起全量重算？画像列表与详情页分数将按最新权重刷新。`, '触发重算', { type: 'warning' });
  recalcStarting.value = true;
  try {
    batch.value = await triggerRecalc(source.value);
    ElMessage.success('重算已启动');
    if (batch.value.status === 'RUNNING') pollBatch();
  } finally {
    recalcStarting.value = false;
  }
};

const resumeRecalc = async () => {
  if (!batch.value?.batchId) return;
  resuming.value = true;
  try {
    batch.value = await resumeRecalcBatch(String(batch.value.batchId));
    if (batch.value.status === 'RUNNING') pollBatch();
  } finally {
    resuming.value = false;
  }
};

// ---------------- 协议与 Schema ----------------
const protocol = ref<ProfileProtocol | null>(null);
const protocolManifestText = ref('');
const protocolValidating = ref(false);
const protocolPublishing = ref(false);
const protocolValidateResult = ref<{ valid: boolean; errors?: string[] } | null>(null);

let protocolSeq = 0;
const loadProtocol = async () => {
  const seq = ++protocolSeq;
  protocol.value = null;
  protocolValidateResult.value = null;
  try {
    const data = await getProtocolCurrent(source.value);
    // 三审 dspro P1(请求栅栏): 切源后旧源协议晚到盖进新源界面, 用户点「重新发布」
    // = 把 A 源的协议发布到 B 源——过期响应连同其 manifest 文本一起丢弃
    if (seq !== protocolSeq) return;
    protocol.value = data;
  } catch {
    if (seq !== protocolSeq) return;
    protocol.value = null;
  }
  protocolManifestText.value = protocol.value?.manifest ? JSON.stringify(protocol.value.manifest, null, 2) : '（该源暂无生效协议）';
};

const manifestOf = () => {
  try {
    return JSON.parse(protocolManifestText.value) as Record<string, unknown>;
  } catch {
    ElMessage.error('协议 manifest 不是合法 JSON');
    return null;
  }
};

const validateCurrentProtocol = async () => {
  const m = manifestOf();
  if (!m) return;
  protocolValidating.value = true;
  try {
    protocolValidateResult.value = await validateProtocol(m);
  } finally {
    protocolValidating.value = false;
  }
};

const publishCurrentProtocol = async () => {
  const m = manifestOf();
  if (!m) return;
  await ElMessageBox.confirm('确认重新发布当前协议？发布后立即对新事件生效。', '发布协议', { type: 'warning' });
  protocolPublishing.value = true;
  try {
    protocol.value = await publishProtocol(source.value, m);
    ElMessage.success('协议已发布');
    loadProtocol();
  } finally {
    protocolPublishing.value = false;
  }
};

const schemas = ref<ProfileSchemaRegistry[]>([]);
const schemaLoading = ref(false);
const schemaForm = reactive({ eventName: '', schemaVersion: '1-0-0' });
const schemaFormText = ref('');
const schemaValidating = ref(false);
const schemaSaving = ref(false);
const schemaValidateResult = ref<{ valid: boolean; errors?: string[] } | null>(null);

const loadSchemas = async () => {
  schemaLoading.value = true;
  try {
    schemas.value = await listEventSchemas(source.value);
  } finally {
    schemaLoading.value = false;
  }
};

const schemaBoOf = () => {
  try {
    return {
      eventName: schemaForm.eventName.trim(),
      schemaVersion: schemaForm.schemaVersion.trim() || '1-0-0',
      schema: JSON.parse(schemaFormText.value)
    };
  } catch {
    ElMessage.error('Schema JSON 不合法');
    return null;
  }
};

const validateSchema = async () => {
  const bo = schemaBoOf();
  if (!bo?.eventName) {
    ElMessage.warning('请先填事件名');
    return;
  }
  schemaValidating.value = true;
  try {
    schemaValidateResult.value = await validateEventSchema(source.value, bo);
  } finally {
    schemaValidating.value = false;
  }
};

const saveSchema = async () => {
  const bo = schemaBoOf();
  if (!bo?.eventName) {
    ElMessage.warning('请先填事件名');
    return;
  }
  schemaSaving.value = true;
  try {
    await registerEventSchema(source.value, bo);
    ElMessage.success('Schema 已保存');
    schemaValidateResult.value = null;
    loadSchemas();
  } finally {
    schemaSaving.value = false;
  }
};

// ---------------- 测试发送（原演示灌数） ----------------
const seedText = ref('');
const seeding = ref(false);
const seedResult = ref<Record<string, unknown> | null>(null);
const seedOk = ref(true);

const fillSeedExample = () => {
  const uid = 'test-send-' + Date.now();
  seedText.value = JSON.stringify(
    [
      {
        eventName: 'page_view',
        eventUid: uid + '-1',
        source: source.value,
        subjectType: 'visitor',
        subjectId: 'test-visitor-1',
        occurredTime: new Date().toISOString(),
        props: { pageUrl: '/test/engine-admin', title: '测试发送示例' }
      }
    ],
    null,
    2
  );
};

const publishEvents = async () => {
  let events: unknown[];
  try {
    events = JSON.parse(seedText.value);
    if (!Array.isArray(events) || !events.length) throw new Error('need array');
  } catch {
    ElMessage.error('请粘贴 JSON 事件数组');
    return;
  }
  await ElMessageBox.confirm(`确认发布 ${events.length} 条事件？eventUid 重复的会被幂等跳过。`, '发布事件', { type: 'warning' });
  seeding.value = true;
  try {
    seedResult.value = await publishProfileEvents(events);
    seedOk.value = true;
    // 三审 dspro P1: 测试发送可能带来全新源/首批主体——失效源清单缓存,
    // 画像页 onActivated 自愈重拉后空态才会解除, 否则旧缓存让空态永远走不出去
    invalidateSources();
  } catch (e) {
    seedResult.value = { error: String(e) };
    seedOk.value = false;
  } finally {
    seeding.value = false;
  }
};

// ---------------- 来源配置（UI 已随「来源管理」页签隐藏, 接口保留待第二步批退役） ----------------

// 测试发送闭环: 发完引导去画像页看效果(缓存已失效, 回页自动重拉)。
// 单源口径下画像页主列表与数据源无关, 不再带 ?source= 定位参数
const router = useRouter();
const goSeeResult = () => {
  router.push('/insight/profile');
};

// ---------------- 公共 ----------------
const formatTime = (v?: string) => (v ? String(v).replace('T', ' ').slice(0, 19) : '—');

const loadAll = () => {
  loadProtocol();
  loadSchemas();
};

onMounted(async () => {
  await ensureSources();
  if (source.value === initialSource) autoPickSource(sourceOpts, source);
  loadAll();
});

// keep-alive 缓存路由组件, 切菜单不触发 onBeforeUnmount(三审 dspro P1): 离开停轮询, 回页恢复
onDeactivated(stopPoll);
onActivated(() => {
  if (!pollTimer && batch.value?.status === 'RUNNING') pollBatch();
});
onBeforeUnmount(stopPoll);
</script>

<style scoped>
.admin-pane {
  min-width: 0;
}
.admin-pane :deep(.el-tabs__content) {
  overflow: visible;
}
.pane-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
@media (min-width: 993px) {
  .source-select {
    width: 150px;
  }
}
.recalc-actions {
  display: flex;
  gap: 8px;
}
.head-actions {
  display: flex;
  gap: 8px;
}
.schema-form {
  margin-top: 14px;
}
.schema-form .filter-bar {
  margin-bottom: 8px;
}
.mono {
  font-family: var(--el-font-family-mono, 'JetBrains Mono', Consolas, monospace);
  font-size: 12px;
}
</style>
