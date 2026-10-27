<template>
  <div class="doc-domain-panel">
    <div class="dd-toolbar">
      <el-form :model="queryParams" :inline="true" class="dd-search" @submit.prevent>
        <el-form-item label="域名">
          <el-input v-model="queryParams.domain" placeholder="域名" clearable style="width: 200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="服务商">
          <el-input v-model="queryParams.provider" placeholder="服务商" clearable style="width: 160px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.enabled" clearable placeholder="全部" style="width: 110px" @change="handleQuery">
            <el-option label="启用" value="1" />
            <el-option label="停用" value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleQuery">搜索</el-button>
          <el-button :icon="RefreshLeft" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
      <div class="dd-actions">
        <!-- 权限对齐后端 DcsAiDocDomainController 实际锚：写操作=dcs:ai:edit（dcs:ai-doc-domain:* 串全库不存在，系审查回修） -->
        <el-button type="primary" :icon="Plus" v-hasPermi="['dcs:ai:edit']" @click="openAdd">新增域名</el-button>
        <el-button :icon="Refresh" @click="getList">刷新</el-button>
      </div>
    </div>

    <div class="dd-table-wrap">
      <el-table v-loading="loading" :data="domainList" class="ui-table">
        <el-table-column label="域名" prop="domain" min-width="220" show-overflow-tooltip />
        <el-table-column label="服务商" width="160" align="center">
          <template #default="{ row }">
            <span class="ui-pill ui-pill--muted">{{ providerLabel(row.provider) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="备注" prop="remark" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '--' }}</template>
        </el-table-column>
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <span class="ui-pill" :class="row.enabled === '1' ? 'ui-pill--pos' : 'ui-pill--muted'">{{ row.enabled === '1' ? '启用' : '停用' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :icon="Edit" v-hasPermi="['dcs:ai:edit']" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" :icon="Delete" v-hasPermi="['dcs:ai:edit']" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="ui-empty">
            <p class="ui-empty__title">还没有识别域名</p>
            <p class="ui-empty__hint">添加域名后，这些外部域名下的文档才能被解析为 AI 文档来源</p>
          </div>
        </template>
      </el-table>
    </div>

    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />

    <div class="dd-footnote">识别域名是文档识别的供给侧配置——控制哪些外部域名的内容允许被解析为 AI 文档来源，与模型/钥匙同属识别链路的运维项。</div>

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
/**
 * 识别域名面板（菜单IA重组批3，2026-10-16）：自 ai-doc-domain/index.vue 整页抽为面板，
 * 作为「AI 模型与钥匙」第 4 页签入驻（原独立菜单 9100 已删，页签权限沿用 9060 的 dcs:ai:list 锚）。
 * 逻辑零改动迁移（查询/增删改/服务商映射）；操作按钮补 v-hasPermi 守卫（原页未挂）。
 */
import { Plus, Search, Refresh, RefreshLeft, Edit, Delete } from '@element-plus/icons-vue';
import type { FormInstance, FormRules } from 'element-plus';
import { listAiDocDomain, getAiDocDomain, addAiDocDomain, updateAiDocDomain, delAiDocDomain, type AiDocDomainVO } from '@/api/dcs/ai-doc-domain';

defineOptions({ name: 'AiConfigDocDomainPanel' });

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const domainList = ref<AiDocDomainVO[]>([]);
const total = ref(0);
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
.doc-domain-panel {
  min-width: 0;
}
.dd-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}
.dd-search {
  margin-bottom: 0;
}
.dd-actions {
  display: flex;
  margin-bottom: 8px;
}
/* 375：表格在容器内横滚，父链 min-width:0 防整页横向溢出 */
.dd-table-wrap {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
  /* 2026-10-27 拍板 Q5=丙：内容区改灰画布后，透明表头 + 白行的表格会被看成被切掉一截；
     给它一张自己的纸面，表头与表身同底色 */
  background: var(--app-surface);
  border: 1px solid var(--app-rule);
  border-radius: var(--app-card-radius);
}
.dd-table-wrap :deep(.el-table) {
  /* 列宽之和：外层容器一处横滚，避免与 el-table 内部滚动条叠加 */
  min-width: 990px;
  /* 只覆盖 EP 表格 CSS 变量，不重写组件（契约 §2.5） */
  --el-table-header-bg-color: transparent;
  --el-table-tr-bg-color: var(--app-surface);
  --el-table-row-hover-bg-color: var(--app-raised);
  --el-table-border-color: var(--app-rule);
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
}
.dd-footnote {
  margin-top: 10px;
  font-size: var(--dcs-font-sm);
  color: var(--app-muted);
}
</style>
