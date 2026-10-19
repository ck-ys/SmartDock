<template>
  <div class="sensitive-word-page">
    <el-card shadow="never">
      <template #header>
        <div class="page-header">
          <div class="header-text">
            <span class="page-title">内容安全词库</span>
            <p class="page-subtitle">
              配置替换、阻断和复核规则，按场景（表单/文件/聊天）生效；列表打码，编辑抽屉内看原文。聊天入口现已暂停，仅表单与文件场景在实际生效。
            </p>
          </div>
          <div class="header-actions">
            <el-button link :icon="Refresh" @click="reload">刷新</el-button>
            <el-button v-hasPermi="['dcs:sensitive-word:list']" :icon="Aim" @click="openTest">命中测试</el-button>
            <el-button v-hasPermi="['dcs:sensitive-word:add']" :icon="Upload" @click="openImport">批量导入</el-button>
            <el-button v-hasPermi="['dcs:sensitive-word:add']" type="primary" :icon="Plus" @click="openAdd">新增词条</el-button>
          </div>
        </div>
      </template>

      <!-- 全量口径统计(2026-08-23: 旧版只算当前页有误导), 语义分色 -->
      <div class="summary-row">
        <div v-for="item in summaryItems" :key="item.label" class="summary-item">
          <span :class="['summary-value', `v-${item.tone}`]">{{ item.value }}</span>
          <span class="summary-label">{{ item.label }}</span>
        </div>
      </div>

      <el-form :model="queryParams" inline label-width="76px" class="filter-form">
        <el-form-item label="关键词">
          <el-input v-model="queryParams.wordText" clearable placeholder="词条内容" @keyup.enter="onSearch" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="queryParams.category" clearable filterable placeholder="全部分类" style="width: 150px">
            <el-option v-for="(label, key) in CATEGORY_LABELS" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="动作">
          <el-select v-model="queryParams.action" clearable placeholder="全部动作" style="width: 140px">
            <el-option v-for="item in actionOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="生效场景">
          <el-select v-model="queryParams.scene" clearable placeholder="全部场景" style="width: 140px">
            <el-option v-for="item in sceneOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.enabled" clearable placeholder="全部状态" style="width: 140px">
            <el-option label="启用" value="1" />
            <el-option label="停用" value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="onSearch">查询</el-button>
          <el-button :icon="RefreshLeft" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="wordList" stripe>
        <el-table-column label="词条" min-width="200">
          <template #default="{ row }">
            <div class="word-main">
              <!-- 列表态打码(2026-08-23 拍板), 编辑抽屉内明文 -->
              <span>{{ maskToken(row.wordText) }}</span>
              <el-tag v-if="row.builtin === '1'" size="small" type="info" effect="light">内置</el-tag>
            </div>
            <div class="word-meta">{{ row.remark || '发送前实时检测' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="120">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" type="info">{{ CATEGORY_LABELS[row.category] || row.category }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="110">
          <template #default="{ row }">
            <el-tag :type="actionType(row.action)" effect="light">{{ actionLabel(row.action) }}</el-tag>
          </template>
        </el-table-column>
        <!-- 近 7 天误报(收尾批 Q1+Q2, 2026-09-23): 误报/命中 + 疑似过严橙标; 排序仅当前页 -->
        <el-table-column label="近 7 天误报" width="170" sortable :sort-method="sortByFpRate">
          <template #header>
            <el-tooltip content="近 7 天被审核人判误报次数 / 命中次数；排序仅对当前页生效" placement="top">
              <span
                >近 7 天误报 <el-icon><InfoFilled /></el-icon
              ></span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <span v-if="row.fpCnt" class="fp-rate">{{ row.fpCnt }}/{{ row.hitCnt || 0 }}</span>
            <span v-else class="fp-none">—</span>
            <el-tag
              v-if="row.overstrict"
              size="small"
              type="warning"
              effect="light"
              class="overstrict-tag"
              title="误报率≥50% 且命中≥5 次，建议复核该词是否过严"
              >疑似过严</el-tag
            >
          </template>
        </el-table-column>
        <!-- 生效场景(2026-09-22 场景化): CSV 拆 tag, 未配置按存量口径显示"聊天" -->
        <el-table-column label="生效场景" width="150">
          <template #default="{ row }">
            <el-tag
              v-for="s in splitScenes(row.scene)"
              :key="s"
              size="small"
              effect="plain"
              :type="s === 'FORM' ? 'primary' : s === 'FILE' ? 'warning' : 'info'"
              class="scene-tag"
            >
              {{ sceneLabel(s) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled === '1' ? 'success' : 'info'" effect="light">{{ row.enabled === '1' ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" prop="updateTime" width="170" />
        <el-table-column label="操作" fixed="right" width="170">
          <template #default="{ row }">
            <el-button v-hasPermi="['dcs:sensitive-word:edit']" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.builtin !== '1'" v-hasPermi="['dcs:sensitive-word:remove']" link type="danger" @click="onDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="词库为空，可新增词条或批量导入" />
        </template>
      </el-table>
      <pagination v-show="total > 0" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <el-drawer v-model="drawerOpen" :title="drawerTitle" size="560px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" class="word-form">
        <el-form-item label="词条" prop="wordText">
          <el-input v-model="form.wordText" :disabled="editingBuiltin" maxlength="100" show-word-limit placeholder="请输入敏感词" />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-select v-model="form.category" filterable allow-create placeholder="GENERAL / POLITICS / ABUSE" style="width: 100%">
            <el-option v-for="(label, key) in CATEGORY_LABELS" :key="key" :label="`${label} (${key})`" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="处理动作" prop="action">
          <el-segmented v-model="form.action" :options="actionOptions" />
        </el-form-item>
        <el-form-item label="生效场景" prop="scenes">
          <el-checkbox-group v-model="formScenes">
            <el-checkbox v-for="item in sceneOptions" :key="item.value" :value="item.value">{{ item.label }}</el-checkbox>
          </el-checkbox-group>
          <div class="scene-hint">勾选后词条只在对应场景检测命中；两个都勾即全场景生效</div>
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="form.enabled" active-value="1" inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="4" maxlength="200" show-word-limit placeholder="说明命中场景或处理依据" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitForm">保存</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="importOpen" title="批量导入敏感词" width="520px" append-to-body>
      <el-alert title="单次最多 500 条；批内与库内重复词条自动跳过，非法词条跳过并计数。" type="info" :closable="false" show-icon class="mb-4" />
      <el-form label-width="86px">
        <el-form-item label="分类">
          <el-select v-model="importForm.category" filterable allow-create placeholder="GENERAL" style="width: 100%">
            <el-option v-for="(label, key) in CATEGORY_LABELS" :key="key" :label="`${label} (${key})`" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="动作">
          <el-segmented v-model="importForm.action" :options="actionOptions" />
        </el-form-item>
        <el-form-item label="生效场景">
          <el-checkbox-group v-model="importForm.scenes">
            <el-checkbox v-for="item in sceneOptions" :key="item.value" :value="item.value">{{ item.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="词条">
          <el-input v-model="importForm.words" type="textarea" :rows="8" placeholder="每行一个词条" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitImport">导入</el-button>
      </template>
    </el-dialog>

    <!-- 命中自测抽屉(2026-08-24): 真实过滤规则只读验证, 不产生风险事件; 结果为管理员验证场景, 命中词明文展示 -->
    <el-drawer v-model="testOpen" title="命中测试" size="560px" append-to-body>
      <el-alert
        title="粘贴文本就地验证词库效果：与真实链路同一套规则，选择场景后按该场景装载词库（含系统基线·仅聊天场景），仅测试不记分。"
        type="info"
        :closable="false"
        show-icon
        class="mb-4"
      />
      <el-form label-width="86px" class="mb-2">
        <el-form-item label="检测场景">
          <el-select v-model="testForm.scene" style="width: 100%">
            <el-option label="全部场景（含历史聊天词）" value="" />
            <el-option v-for="item in sceneOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-input
        v-model="testForm.text"
        type="textarea"
        :rows="6"
        maxlength="2000"
        show-word-limit
        placeholder="粘贴一段测试文本，点“开始测试”查看命中哪些词条及对应动作"
      />
      <div class="test-actions">
        <el-button type="primary" :loading="testForm.loading" :disabled="!testForm.text.trim()" @click="submitTest">开始测试</el-button>
        <el-button v-if="testForm.result" @click="Object.assign(testForm, { text: '', result: null })">清空</el-button>
      </div>
      <template v-if="testForm.result">
        <el-alert v-if="testForm.result.blocked" title="含阻断词：真实发送该文本会被直接拦截" type="error" :closable="false" show-icon class="mb-2" />
        <el-alert
          v-else-if="testForm.result.reviewRequired"
          title="含转人工复核词：发送后内容将进入人工复核"
          type="warning"
          :closable="false"
          show-icon
          class="mb-2"
        />
        <el-alert
          v-else-if="testForm.result.hits.length"
          title="含替换词：发送时命中部分将被打码"
          type="info"
          :closable="false"
          show-icon
          class="mb-2"
        />
        <el-alert v-else title="未命中任何词条，可正常发送" type="success" :closable="false" show-icon class="mb-2" />
        <el-table v-if="testForm.result.hits.length" :data="testForm.result.hits" stripe size="small">
          <el-table-column label="命中词" prop="word" min-width="140" />
          <el-table-column label="动作" width="110">
            <template #default="{ row }">
              <el-tag :type="row.action === 'BLOCK' ? 'danger' : row.action === 'REVIEW' ? 'warning' : 'success'" size="small">
                {{ row.action === 'BLOCK' ? '阻断' : row.action === 'REVIEW' ? '转复核' : '替换' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="位置(字符)" width="110" align="center">
            <template #default="{ row }">{{ row.start }}–{{ row.end }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { Aim, InfoFilled, Plus, Refresh, RefreshLeft, Search, Upload } from '@element-plus/icons-vue';
import {
  addSensitiveWord,
  batchImportSensitiveWords,
  delSensitiveWord,
  getSensitiveWord,
  getSensitiveWordStats,
  listSensitiveWord,
  testSensitiveWordMatch,
  updateSensitiveWord,
  type SensitiveWordTestResult
} from '@/api/dcs/sensitive-word';
import type { SensitiveWordForm, SensitiveWordQuery, SensitiveWordVO } from '@/api/dcs/sensitive-word/types';
import { maskToken } from './sensitiveMask';

defineOptions({ name: 'DcsSensitiveWord' });

type ActionType = 'MASK' | 'BLOCK' | 'REVIEW';

/** 分类中文标签(2026-08-23: 裸英文枚举改中文 tag; 未映射的分类原样展示) */
const CATEGORY_LABELS: Record<string, string> = {
  GENERAL: '通用',
  POLITICS: '涉政',
  ABUSE: '滥用',
  AD: '广告',
  PORNO: '违规内容',
  GAMBLE: '赌博',
  FRAUD: '诈骗'
};

const loading = ref(false);
const submitLoading = ref(false);
const drawerOpen = ref(false);
const importOpen = ref(false);
const testOpen = ref(false);
const testForm = reactive({
  text: '',
  loading: false,
  /** 检测场景(2026-09-22): 空=全部词, CHAT/FORM=按场景装载 */
  scene: '',
  result: null as SensitiveWordTestResult | null
});
const drawerTitle = ref('新增敏感词');
const total = ref(0);
const wordList = ref<SensitiveWordVO[]>([]);
const stats = ref<{ total: number; enabled: number; actionMask: number; actionBlock: number; actionReview: number } | null>(null);
const formRef = ref();

const queryParams = reactive<SensitiveWordQuery>({
  pageNum: 1,
  pageSize: 10,
  wordText: '',
  category: '',
  action: '',
  enabled: '',
  scene: ''
});

const form = reactive<SensitiveWordForm>({
  wordText: '',
  category: 'GENERAL',
  action: 'MASK',
  enabled: '1',
  builtin: '0',
  remark: '',
  scene: 'CHAT'
});
/** 表单场景勾选集(2026-09-22 场景化): 提交时拼 CSV 写回 form.scene */
const formScenes = ref<string[]>(['CHAT']);

const importForm = reactive({
  category: 'GENERAL',
  action: 'MASK' as ActionType,
  scenes: ['CHAT'] as string[],
  words: ''
});

const actionOptions = [
  { label: '替换', value: 'MASK' },
  { label: '阻断', value: 'BLOCK' },
  { label: '复核', value: 'REVIEW' }
];

/** 生效场景选项(2026-09-22): CHAT=聊天(含历史基线) / FORM=表单(表单填写·在线批量·Excel) / FILE=文件 */
const sceneOptions = [
  { label: '聊天', value: 'CHAT' },
  { label: '表单', value: 'FORM' },
  { label: '文件', value: 'FILE' }
];

/** CSV 拆场景数组; 空/非法值按存量口径回落 CHAT */
const splitScenes = (csv?: string): string[] => {
  const arr = (csv || 'CHAT')
    .split(',')
    .map((s) => s.trim().toUpperCase())
    .filter((s) => ['CHAT', 'FORM', 'FILE'].includes(s));
  return arr.length ? arr : ['CHAT'];
};
const sceneLabel = (s: string) => sceneOptions.find((item) => item.value === s)?.label || s;

const rules = {
  wordText: [{ required: true, message: '请输入敏感词', trigger: 'blur' }],
  action: [{ required: true, message: '请选择处理动作', trigger: 'change' }]
};

const editingBuiltin = computed(() => form.builtin === '1');
/** 全量口径统计卡(词条总数 primary / 启用 success / 阻断 danger / 转复核 warning) */
const summaryItems = computed(() => {
  const s = stats.value;
  return [
    { label: '词条总数', value: s?.total ?? '-', tone: 'primary' },
    { label: '启用规则', value: s?.enabled ?? '-', tone: 'success' },
    { label: '阻断规则', value: s?.actionBlock ?? '-', tone: 'danger' },
    { label: '转人工复核', value: s?.actionReview ?? '-', tone: 'warning' }
  ];
});

// 近 7 天误报列排序(收尾批 Q1): 按误报数降序优先, 同数按误报率——仅当前页(服务端分页)
const sortByFpRate = (a: SensitiveWordVO, b: SensitiveWordVO) => {
  const fa = a.fpCnt || 0;
  const fb = b.fpCnt || 0;
  if (fa !== fb) return fb - fa;
  const ra = (b.fpCnt || 0) / Math.max(b.hitCnt || 0, 1);
  const rb = (a.fpCnt || 0) / Math.max(a.hitCnt || 0, 1);
  return ra - rb;
};

const actionLabel = (action: ActionType) => actionOptions.find((item) => item.value === action)?.label || action;
const actionType = (action: ActionType): 'success' | 'danger' | 'warning' => {
  if (action === 'BLOCK') return 'danger';
  if (action === 'REVIEW') return 'warning';
  return 'success';
};

const resetForm = () => {
  Object.assign(form, {
    wordId: undefined,
    wordText: '',
    category: 'GENERAL',
    action: 'MASK',
    enabled: '1',
    builtin: '0',
    remark: '',
    scene: 'CHAT'
  });
  formScenes.value = ['CHAT'];
  formRef.value?.clearValidate?.();
};

const loadStats = async () => {
  try {
    const res: any = await getSensitiveWordStats();
    stats.value = res.data || null;
  } catch {
    stats.value = null;
  }
};

const getList = async () => {
  loading.value = true;
  try {
    const res: any = await listSensitiveWord(queryParams);
    wordList.value = res.rows || res.data || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
};
const reload = async () => {
  await Promise.all([getList(), loadStats()]);
};
const onSearch = () => {
  queryParams.pageNum = 1;
  getList();
};

const resetQuery = () => {
  Object.assign(queryParams, {
    pageNum: 1,
    pageSize: 10,
    wordText: '',
    category: '',
    action: '',
    enabled: '',
    scene: ''
  });
  getList();
};

const openAdd = () => {
  resetForm();
  drawerTitle.value = '新增敏感词';
  drawerOpen.value = true;
};

const openEdit = async (row: SensitiveWordVO) => {
  resetForm();
  const res: any = await getSensitiveWord(row.wordId);
  Object.assign(form, res.data || res);
  formScenes.value = splitScenes(form.scene);
  drawerTitle.value = '编辑敏感词';
  drawerOpen.value = true;
};

const submitForm = async () => {
  await formRef.value?.validate?.();
  if (!formScenes.value.length) {
    ElMessage.warning('请至少勾选一个生效场景');
    return;
  }
  form.scene = formScenes.value.join(',');
  submitLoading.value = true;
  try {
    if (form.wordId) {
      await updateSensitiveWord(form);
      ElMessage.success('已更新');
    } else {
      await addSensitiveWord(form);
      ElMessage.success('已新增');
    }
    drawerOpen.value = false;
    await reload();
  } finally {
    submitLoading.value = false;
  }
};

const onDelete = async (row: SensitiveWordVO) => {
  // 确认文案同样打码
  await ElMessageBox.confirm(`确认删除敏感词「${maskToken(row.wordText)}」吗？`, '删除确认', { type: 'warning' });
  await delSensitiveWord(row.wordId);
  ElMessage.success('已删除');
  await reload();
};

const openTest = () => {
  Object.assign(testForm, { text: '', loading: false, scene: '', result: null });
  testOpen.value = true;
};

const submitTest = async () => {
  testForm.loading = true;
  try {
    const res: any = await testSensitiveWordMatch(testForm.text.trim(), testForm.scene || undefined);
    testForm.result = res.data || null;
  } finally {
    testForm.loading = false;
  }
};

const openImport = () => {
  Object.assign(importForm, {
    category: 'GENERAL',
    action: 'MASK',
    scenes: ['CHAT'],
    words: ''
  });
  importOpen.value = true;
};

const submitImport = async () => {
  const words = importForm.words
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean);
  if (!words.length) {
    ElMessage.warning('请至少输入一个词条');
    return;
  }
  if (!importForm.scenes.length) {
    ElMessage.warning('请至少勾选一个生效场景');
    return;
  }
  submitLoading.value = true;
  try {
    // 单请求批量导入(2026-08-23: 替代逐条 POST, 重复/非法词条由服务端跳过计数)
    const res: any = await batchImportSensitiveWords({
      words,
      category: importForm.category || 'GENERAL',
      action: importForm.action,
      enabled: '1',
      scene: importForm.scenes.join(',')
    });
    // 结果文案在 msg 字段(R.ok(String) 重载语义)
    ElMessage.success(res?.msg || `已提交 ${words.length} 个词条`);
    importOpen.value = false;
    await reload();
  } finally {
    submitLoading.value = false;
  }
};

onMounted(reload);
</script>

<style scoped lang="scss">
.sensitive-word-page {
  padding: 12px;

  .page-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
  }

  .page-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--dcs-text-primary);
  }

  .page-subtitle {
    margin: 4px 0 0;
    color: var(--dcs-text-secondary);
    font-size: 12px;
    line-height: 1.6;
  }

  .header-actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    justify-content: flex-end;
  }

  .summary-row {
    display: grid;
    grid-template-columns: repeat(4, minmax(120px, 1fr));
    gap: 12px;
    margin-bottom: 16px;
  }

  .summary-item {
    min-height: 64px;
    border: 1px solid var(--dcs-border-color-lighter);
    border-radius: 6px;
    padding: 12px 14px;
    background: var(--dcs-bg-fill-lighter);
  }

  .summary-value {
    display: block;
    font-size: 22px;
    font-weight: 700;
    line-height: 1.2;

    &.v-primary {
      color: var(--el-color-primary);
    }
    &.v-success {
      color: var(--dcs-color-success);
    }
    &.v-danger {
      color: var(--dcs-color-danger);
    }
    &.v-warning {
      color: var(--dcs-color-warning);
    }
  }

  .summary-label {
    display: block;
    margin-top: 6px;
    color: var(--dcs-text-secondary);
    font-size: 13px;
  }

  .filter-form {
    :deep(.el-form-item) {
      margin-bottom: 8px;
    }
  }

  .word-main {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;
  }

  .test-actions {
    display: flex;
    gap: 8px;
    margin: 12px 0;
  }

  .word-meta {
    margin-top: 4px;
    color: var(--dcs-text-secondary);
    font-size: 12px;
  }

  .fp-rate {
    color: var(--el-color-warning);
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
  .fp-none {
    color: var(--dcs-text-placeholder, #c0c4cc);
  }
  .overstrict-tag {
    margin-left: 6px;
  }

  .scene-tag {
    margin-right: 4px;

    &:last-child {
      margin-right: 0;
    }
  }

  .scene-hint {
    width: 100%;
    color: var(--dcs-text-secondary);
    font-size: 11px;
    line-height: 1.6;
  }
}

@media (max-width: 768px) {
  .sensitive-word-page {
    .page-header {
      flex-direction: column;
    }

    .summary-row {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
}
</style>
