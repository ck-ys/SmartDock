<template>
  <div class="dcs-ops-page">
    <DcsOperationsPageHeader
      title="余额兜底策略"
      subtitle="只管一件事：钱包余额不足时怎么办。模型本身怎么定价，去「AI 模型配置」看。"
      :metrics="headerMetrics"
    >
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="openAdd">新增规则</el-button>
        <el-button :icon="Refresh" @click="getList">刷新</el-button>
      </template>
    </DcsOperationsPageHeader>

    <el-card shadow="never" class="dcs-ops-table-card">
      <!-- 搜索栏 -->
      <el-form :model="queryParams" :inline="true" class="search-form">
        <el-form-item label="服务码">
          <el-input v-model="queryParams.serviceCode" placeholder="服务码" clearable style="width: 200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="服务名">
          <el-input v-model="queryParams.serviceName" placeholder="服务名" clearable style="width: 200px" @keyup.enter="handleQuery" />
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

      <el-table v-loading="loading" :data="ruleList" border size="small">
        <el-table-column label="规则ID" prop="ruleId" min-width="172" align="center">
          <template #default="{ row }"
            ><span class="dcs-ops-id">{{ row.ruleId }}</span></template
          >
        </el-table-column>
        <el-table-column label="服务码" prop="serviceCode" min-width="160" show-overflow-tooltip />
        <el-table-column label="服务名" prop="serviceName" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.serviceName || '--' }}</template>
        </el-table-column>
        <el-table-column label="兜底策略" width="140" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="fallbackPolicyType(row.fallbackPolicy)" effect="plain">{{ fallbackPolicyLabel(row.fallbackPolicy) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="排序" prop="sortOrder" width="80" align="center" />
        <el-table-column label="启用" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled === '1' ? 'success' : 'info'">{{ row.enabled === '1' ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
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
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="640px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="服务码" prop="serviceCode">
              <el-input v-model="form.serviceCode" placeholder="如 AI_CALL / PROFILE_DIAGNOSE" :disabled="!!form.ruleId" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="服务名" prop="serviceName">
              <el-input v-model="form.serviceName" placeholder="服务显示名称" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="兜底策略" prop="fallbackPolicy">
              <el-select v-model="form.fallbackPolicy" style="width: 100%">
                <el-option v-for="opt in fallbackPolicyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="钱包范围" prop="walletScope">
              <el-select v-model="form.walletScope" style="width: 100%">
                <el-option label="主钱包" value="MAIN" />
                <el-option label="全部" value="ALL" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="排序" prop="sortOrder">
              <el-input-number v-model="form.sortOrder" :min="0" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="启用" prop="enabled">
              <el-switch v-model="form.enabled" active-value="1" inactive-value="0" />
            </el-form-item>
          </el-col>
        </el-row>
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
import {
  listServiceChargeRule,
  getServiceChargeRule,
  addServiceChargeRule,
  updateServiceChargeRule,
  delServiceChargeRule,
  type ServiceChargeRuleVO
} from '@/api/dcs/service-charge-rule';

defineOptions({ name: 'DcsServiceChargeRule' });

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const ruleList = ref<ServiceChargeRuleVO[]>([]);
const total = ref(0);
const headerMetrics = computed(() => [
  { label: '规则数量', value: total.value, hint: '符合当前筛选条件', tone: 'primary' as const },
  { label: '已启用', value: ruleList.value.filter((item) => item.enabled === '1').length, hint: '当前页生效规则', tone: 'success' as const },
  {
    label: '余额不足拦截',
    value: ruleList.value.filter((item) => item.fallbackPolicy === 'BLOCK').length,
    hint: '当前页直接阻断策略',
    tone: 'neutral' as const
  }
]);
const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  serviceCode: '',
  serviceName: '',
  enabled: ''
});

const fallbackPolicyOptions = [
  { value: 'BLOCK', label: '余额不足时拦截' },
  { value: 'ALGORITHM_FALLBACK', label: '切换算法兜底' },
  { value: 'ALLOW_FREE', label: '允许免费降级' },
  { value: 'ADMIN_OVERRIDE', label: '管理员覆写' }
];

const fallbackPolicyLabel = (p: string) => fallbackPolicyOptions.find((o) => o.value === p)?.label || p || '--';
const fallbackPolicyType = (p: string) => {
  const map: Record<string, string> = { BLOCK: 'danger', ALGORITHM_FALLBACK: 'warning', ALLOW_FREE: 'success', ADMIN_OVERRIDE: 'primary' };
  return map[p] || 'info';
};

const getList = async () => {
  loading.value = true;
  try {
    const res: any = await listServiceChargeRule(queryParams);
    ruleList.value = res.rows || [];
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
  queryParams.serviceCode = '';
  queryParams.serviceName = '';
  queryParams.enabled = '';
  queryParams.pageNum = 1;
  getList();
};

// 新增 / 编辑
const formRef = ref<FormInstance>();
const dialog = reactive({ visible: false, title: '' });
const submitting = ref(false);
const defaultForm = (): ServiceChargeRuleVO => ({
  ruleId: 0,
  serviceCode: '',
  serviceName: '',
  // 业务反馈批1015c Q3(拍板A)：chargeMode/fixedAmount 为历史遗留死字段（运行时扣费
  // 全程不读，只消费 fallbackPolicy）——不再随表单提交默认值，彻底去"计费"化。
  // 显式 undefined=JSON 序列化丢键不提交，且防 openAdd 的 Object.assign 缺键不清残留
  // （编辑过 A 规则再点新增，A 的旧计费值会被带进新规则 INSERT——dspro P2）。
  // 注：库中该列 NOT NULL，后端 normalizeRule 会对空 chargeMode 兜底写回 'AI_USAGE_COST'
  // （存量默认值，运行时不消费）；fixedAmount 可空（m3 P2-3：口径与后端实现对齐）
  chargeMode: undefined as unknown as string,
  fixedAmount: undefined as unknown as number,
  fallbackPolicy: 'BLOCK',
  walletScope: 'MAIN',
  lowBalanceThreshold: 0,
  enabled: '1',
  sortOrder: 0,
  remark: ''
});
const form = reactive<ServiceChargeRuleVO>(defaultForm());
const rules: FormRules = {
  serviceCode: [{ required: true, message: '请输入服务码', trigger: 'blur' }],
  serviceName: [{ required: true, message: '请输入服务名', trigger: 'blur' }],
  fallbackPolicy: [{ required: true, message: '请选择兜底策略', trigger: 'change' }]
};

const openAdd = () => {
  Object.assign(form, defaultForm());
  dialog.title = '新增兜底规则';
  dialog.visible = true;
  nextTick(() => formRef.value?.clearValidate());
};

const openEdit = async (row: ServiceChargeRuleVO) => {
  const res: any = await getServiceChargeRule(row.ruleId);
  const detail = res.data || row;
  Object.assign(form, defaultForm(), detail);
  dialog.title = '编辑兜底规则';
  dialog.visible = true;
  nextTick(() => formRef.value?.clearValidate());
};

const submit = async () => {
  if (!formRef.value) return;
  await formRef.value.validate();
  submitting.value = true;
  try {
    if (form.ruleId) {
      await updateServiceChargeRule(form);
      proxy?.$modal.msgSuccess('更新成功');
    } else {
      await addServiceChargeRule(form);
      proxy?.$modal.msgSuccess('新增成功');
    }
    dialog.visible = false;
    getList();
  } finally {
    submitting.value = false;
  }
};

const onDelete = async (row: ServiceChargeRuleVO) => {
  await proxy?.$modal.confirm(`确认删除服务「${row.serviceName || row.serviceCode}」的兜底规则?`);
  await delServiceChargeRule(row.ruleId);
  proxy?.$modal.msgSuccess('删除成功');
  getList();
};

onMounted(getList);
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.page-title {
  font-size: 18px;
  font-weight: 600;
}
.page-subtitle {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}
.search-form {
  margin-bottom: 8px;
}
</style>
