<template>
  <div class="app-container engine-admin-page">
    <dcs-operations-page-header
      eyebrow="画像引擎"
      title="引擎管理"
      subtitle="全量重算、接入协议与事件 Schema、演示灌数——画像引擎的运维入口，全部动作留痕可查。"
    >
      <template #actions>
        <el-select v-model="source" class="source-select" @change="loadAll">
          <el-option v-for="s in sourceOpts" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </template>
    </dcs-operations-page-header>

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

      <!-- ---------------------------------------------------------- 演示灌数 -->
      <el-tab-pane label="演示灌数" name="seed" lazy>
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-head">
              <span>批量发布事件</span>
              <span class="muted">低频运维动作：粘贴事件数组（事件信封格式），逐条校验幂等</span>
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
            placeholder='[{"eventName":"...","eventUid":"demo-...","source":"...","subjectType":"user","subjectId":"...","occurredTime":"...","props":{}}]'
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
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup name="DcsProfileEngineAdmin" lang="ts">
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
import { useSourceOptions } from '@/composables/useProfileSources';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';

const { list: sourceOpts, ensureSources } = useSourceOptions('subject');
ensureSources();

const tab = ref('recalc');
const source = ref('dcs');

// ---------------- 全量重算 ----------------
const batch = ref<ProfileRecalcBatch | null>(null);
const recalcStarting = ref(false);
const resuming = ref(false);
let pollTimer: ReturnType<typeof setInterval> | null = null;

const statusText = (s?: string) => (({ RUNNING: '重算中', DONE: '完成', FAILED: '失败' }) as Record<string, string>)[s || ''] || s || '—';
const statusTone = (s?: string) => (({ RUNNING: 'info', DONE: 'success', FAILED: 'danger' }) as Record<string, string>)[s || ''] || 'info';

const pollBatch = () => {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = setInterval(async () => {
    if (!batch.value?.batchId) return;
    try {
      const b = await getRecalcBatch(String(batch.value.batchId));
      batch.value = b;
      if (b.status !== 'RUNNING' && pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
      }
    } catch {
      if (pollTimer) clearInterval(pollTimer);
      pollTimer = null;
    }
  }, 2000);
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

const loadProtocol = async () => {
  protocol.value = null;
  protocolValidateResult.value = null;
  try {
    protocol.value = await getProtocolCurrent(source.value);
  } catch {
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

// ---------------- 演示灌数 ----------------
const seedText = ref('');
const seeding = ref(false);
const seedResult = ref<Record<string, unknown> | null>(null);
const seedOk = ref(true);

const fillSeedExample = () => {
  const uid = 'demo-engine-' + Date.now();
  seedText.value = JSON.stringify(
    [
      {
        eventName: 'page_view',
        eventUid: uid + '-1',
        source: source.value,
        subjectType: 'visitor',
        subjectId: 'demo-visitor-1',
        occurredTime: new Date().toISOString(),
        props: { pageUrl: '/demo/engine-admin', title: '引擎管理示例' }
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
  } catch (e) {
    seedResult.value = { error: String(e) };
    seedOk.value = false;
  } finally {
    seeding.value = false;
  }
};

// ---------------- 公共 ----------------
const formatTime = (v?: string) => (v ? String(v).replace('T', ' ').slice(0, 19) : '—');

const loadAll = () => {
  loadProtocol();
  loadSchemas();
};

onMounted(() => {
  loadAll();
});

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer);
});
</script>

<style scoped>
.engine-admin-page :deep(.el-tabs__content) {
  overflow: visible;
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
