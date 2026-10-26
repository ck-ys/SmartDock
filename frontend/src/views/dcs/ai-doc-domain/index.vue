<template>
  <div class="dcs-ops-page">
    <DcsOperationsPageHeader
      title="识别域名管理"
      subtitle="维护允许被识别为 AI 文档来源的域名及服务商映射，控制外部内容解析范围。"
      :metrics="headerMetrics"
    >
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="openAdd">新增域名</el-button>
        <el-button :icon="Refresh" @click="getList">刷新</el-button>
      </template>
    </DcsOperationsPageHeader>

    <el-card shadow="never" class="dcs-ops-table-card">
      <!-- 搜索栏 -->
      <el-form :model="queryParams" :inline="true" class="search-form">
        <el-form-item label="域名">
          <el-input v-model="queryParams.domain" placeholder="域名" clearable style="width: 220px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="服务商">
          <el-input v-model="queryParams.provider" placeholder="服务商" clearable style="width: 180px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="启用状态">
          <el-select v-model="queryParams.enabled" clearable placeholder="全部" style="width: 130px" @change="handleQuery">
            <el-option label="启用" value="1" />
            <el-option label="停用" value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleQuery">搜索</el-button>
          <el-button :icon="RefreshLeft" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="domainList" border size="small">
        <el-table-column label="域名" prop="domain" min-width="220" show-overflow-tooltip />
        <el-table-column label="服务商" width="160" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="providerTagType(row.provider)">{{ providerLabel(row.provider) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="备注" prop="remark" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '--' }}</template>
        </el-table-column>
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled === '1' ? 'success' : 'info'">{{ row.enabled === '1' ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" :icon="Delete" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </el-card>

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="600px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="域名" prop="domain">
          <el-input v-model="form.domain" placeholder="如 example.com" />
        </el-form-item>
        <el-form-item label="服务商" prop="provider">
          <el-select v-model="form.provider" clearable filterable placeholder="可选" style="width: 100%">
            <el-option v-for="opt in providerOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用" prop="enabled">
          <el-switch v-model="form.enabled" active-value="1" inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { Plus, Search, Refresh, RefreshLeft, Edit, Delete } from '@element-plus/icons-vue';
import type { FormInstance, FormRules } from 'element-plus';
import { listAiDocDomain, getAiDocDomain, addAiDocDomain, updateAiDocDomain, delAiDocDomain, type AiDocDomainVO } from '@/api/dcs/ai-doc-domain';

defineOptions({ name: 'DcsAiDocDomain' });

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const domainList = ref<AiDocDomainVO[]>([]);
const total = ref(0);
const headerMetrics = computed(() => [
  { label: '域名总数', value: total.value, hint: '符合当前筛选条件', tone: 'primary' as const },
  { label: '当前页启用', value: domainList.value.filter((item) => item.enabled === '1').length, hint: '允许参与文档识别', tone: 'success' as const },
  {
    label: '当前页停用',
    value: domainList.value.filter((item) => item.enabled !== '1').length,
    hint: '暂不参与识别',
    tone: 'warning' as const
  },
  {
    label: '服务商',
    value: new Set(domainList.value.map((item) => item.provider).filter(Boolean)).size,
    hint: '当前页供应商覆盖',
    tone: 'neutral' as const
  }
]);
const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  domain: '',
  provider: '',
  enabled: ''
});

const providerOptions = [
  { value: 'DEEPSEEK', label: 'DeepSeek' },
  { value: 'QWEN', label: '通义千问 (Qwen)' },
  { value: 'ZHIPU', label: '智谱 (Zhipu)' },
  { value: 'KIMI', label: 'Kimi' },
  { value: 'BAIDU_QIANFAN', label: '百度千帆' },
  { value: 'TENCENT_HUNYUAN', label: '腾讯混元' },
  { value: 'XFYUN_SPARK', label: '讯飞星火' },
  { value: 'MINIMAX', label: 'MiniMax' },
  { value: 'SILICONFLOW', label: 'SiliconFlow' },
  { value: 'OPENAI', label: 'OpenAI' },
  { value: 'CUSTOM', label: '自定义 (Custom)' }
];

const providerLabel = (p: string) => providerOptions.find((o) => o.value === p)?.label || p || '--';
const providerTagType = (p: string) => {
  const map: Record<string, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
    DEEPSEEK: 'success',
    QWEN: 'warning',
    ZHIPU: 'primary',
    KIMI: 'primary',
    BAIDU_QIANFAN: 'success',
    TENCENT_HUNYUAN: 'warning',
    XFYUN_SPARK: 'danger',
    MINIMAX: 'warning',
    SILICONFLOW: 'success',
    OPENAI: 'success',
    CUSTOM: 'info'
  };
  return map[p] || 'info';
};

const getList = async () => {
  loading.value = true;
  try {
    const res: any = await listAiDocDomain(queryParams);
    domainList.value = res.rows || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
};

const handleQuery = () => {
  queryParams.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryParams.domain = '';
  queryParams.provider = '';
  queryParams.enabled = '';
  queryParams.pageNum = 1;
  getList();
};

// 新增 / 编辑
const formRef = ref<FormInstance>();
const dialog = reactive({ visible: false, title: '' });
const submitting = ref(false);
const defaultForm = (): AiDocDomainVO => ({
  domainId: 0,
  domain: '',
  provider: '',
  remark: '',
  enabled: '1',
  createTime: ''
});
const form = reactive<AiDocDomainVO>(defaultForm());
const rules: FormRules = {
  domain: [{ required: true, message: '请输入域名', trigger: 'blur' }]
};

const openAdd = () => {
  Object.assign(form, defaultForm());
  dialog.title = '新增域名';
  dialog.visible = true;
  nextTick(() => formRef.value?.clearValidate());
};

const openEdit = async (row: AiDocDomainVO) => {
  const res: any = await getAiDocDomain(row.domainId);
  const detail = res.data || row;
  Object.assign(form, defaultForm(), detail);
  dialog.title = '编辑域名';
  dialog.visible = true;
  nextTick(() => formRef.value?.clearValidate());
};

const submit = async () => {
  if (!formRef.value) return;
  await formRef.value.validate();
  submitting.value = true;
  try {
    if (form.domainId) {
      await updateAiDocDomain(form);
      proxy?.$modal.msgSuccess('更新成功');
    } else {
      await addAiDocDomain(form);
      proxy?.$modal.msgSuccess('新增成功');
    }
    dialog.visible = false;
    getList();
  } finally {
    submitting.value = false;
  }
};

const onDelete = async (row: AiDocDomainVO) => {
  await proxy?.$modal.confirm(`确认删除域名「${row.domain}」?`);
  await delAiDocDomain(row.domainId);
  proxy?.$modal.msgSuccess('删除成功');
  getList();
};

onMounted(getList);
</script>

<style scoped>
/* U-14：页头已并基座 DcsOperationsPageHeader（页题 20px/副题令牌色），自绘页头死样式连同 EP 旧灰一并删除 */
.search-form {
  margin-bottom: 8px;
}
</style>
