<template>
  <div class="risk-whitelist">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-text">
            <span class="page-title">白名单管理</span>
            <p class="page-sub">白名单内的 IP / 用户不参与风控、不触发验证码；IP 支持精确 IPv4/IPv6，USER 使用数字 ID，域名白名单接入后开放。</p>
          </div>
          <div>
            <el-button v-hasPermi="['dcs:risk:whitelist:edit']" type="primary" :icon="Plus" @click="onAdd">新增</el-button>
            <el-button link :icon="Refresh" @click="loadList">刷新</el-button>
          </div>
        </div>
      </template>

      <!-- 筛选 -->
      <el-form :inline="true" class="mb-3">
        <el-form-item label="类型">
          <el-select v-model="query.listType" placeholder="全部" clearable style="width: 120px" @change="onSearch">
            <el-option label="IP" value="IP" />
            <el-option label="用户" value="USER" />
          </el-select>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column label="类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="typeColor(row.listType)" size="small">{{ row.listType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="值" prop="value" min-width="200" show-overflow-tooltip />
        <el-table-column label="原因" prop="reason" min-width="180" show-overflow-tooltip />
        <el-table-column label="启用" width="100" align="center">
          <template #default="{ row }">
            <el-switch
              v-model="row.enabled"
              active-value="1"
              inactive-value="0"
              :loading="row._toggling"
              @change="(val: '0' | '1') => onToggleEnabled(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" width="160">
          <template #default="{ row }">{{ row.createTime ? parseTime(parseDcsServerTime(row.createTime), '{y}-{m}-{d} {h}:{i}') : '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="center">
          <template #default="{ row }">
            <el-button v-hasPermi="['dcs:risk:whitelist:edit']" type="primary" link size="small" :icon="Edit" @click="onEdit(row)">编辑</el-button>
            <el-button v-hasPermi="['dcs:risk:whitelist:edit']" type="danger" link size="small" :icon="Delete" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无白名单记录">
            <el-button v-if="checkPermi(['dcs:risk:whitelist:edit'])" type="primary" size="small" @click="onAdd">新增白名单</el-button>
          </el-empty>
        </template>
      </el-table>

      <pagination v-show="total > 0" :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="loadList" />
    </el-card>

    <!-- 新增/编辑对话框（共用） -->
    <el-dialog v-model="formDialog.visible" :title="formDialog.editingId ? '编辑白名单' : '新增白名单'" width="480px" append-to-body>
      <el-form ref="formRef" :model="formDialog.form" :rules="formRules" label-width="80px">
        <el-form-item label="类型" prop="listType">
          <el-radio-group v-model="formDialog.form.listType" :disabled="!!formDialog.editingId">
            <el-radio-button value="IP">IP 地址</el-radio-button>
            <el-radio-button value="USER">用户</el-radio-button>
          </el-radio-group>
          <div v-if="formDialog.editingId" class="text-xs text-gray-400 mt-1">编辑时类型不可改（语义不变）</div>
        </el-form-item>
        <el-form-item label="值" prop="value">
          <el-input v-model="formDialog.form.value" :placeholder="valuePlaceholder" :disabled="!!formDialog.editingId" />
          <div v-if="formDialog.editingId" class="text-xs text-gray-400 mt-1">编辑时值不可改（语义不变）</div>
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="formDialog.form.reason" type="textarea" :rows="2" placeholder="选填, 说明为何加入白名单" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="formDialog.form.enabled" active-value="1" inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="formDialog.saving" @click="onConfirm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { Plus, Refresh, Delete, Edit } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus';
import { listWhitelist, addWhitelist, updateWhitelist, deleteWhitelist, type RiskWhitelistVO } from '@/api/dcs/risk';
import { parseDcsServerTime } from './riskDashboardPresentation';
import { parseTime } from '@/utils/ruoyi';

import { checkPermi } from '@/utils/permission';

defineOptions({ name: 'DcsRiskWhitelist' });

const query = reactive({ pageNum: 1, pageSize: 20, listType: '' });
const list = ref<(RiskWhitelistVO & { _toggling?: boolean })[]>([]);
const total = ref(0);
const loading = ref(false);

const typeColor = (t: string) => (t === 'IP' ? 'primary' : t === 'USER' ? 'success' : 'warning');

const valuePlaceholder = computed(() => {
  switch (formDialog.form.listType) {
    case 'IP':
      return '如 192.168.1.100';
    case 'USER':
      return '用户 ID, 如 9100000001';
    default:
      return '';
  }
});

const loadList = async () => {
  loading.value = true;
  try {
    const r: any = await listWhitelist({ ...query, listType: query.listType || undefined });
    if (r.code === 200) {
      list.value = (r.rows || []).map((x: RiskWhitelistVO) => ({ ...x, _toggling: false }));
      total.value = r.total || 0;
    }
  } finally {
    loading.value = false;
  }
};

const onSearch = () => {
  query.pageNum = 1;
  loadList();
};

// 启用状态切换（el-switch change 回调）
const onToggleEnabled = async (row: RiskWhitelistVO & { _toggling?: boolean }, val: '0' | '1') => {
  row._toggling = true;
  try {
    const r: any = await updateWhitelist(row.whitelistId!, { enabled: val });
    if (r.code === 200) {
      ElMessage.success(val === '1' ? '已启用' : '已禁用');
    } else {
      // 后端拒绝时回滚前端状态
      row.enabled = val === '1' ? '0' : '1';
      ElMessage.error(r.msg || '切换失败');
    }
  } catch {
    row.enabled = val === '1' ? '0' : '1';
    ElMessage.error('切换失败');
  } finally {
    row._toggling = false;
  }
};

// 新增/编辑共用 dialog
const formRef = ref<FormInstance>();
const formDialog = reactive({
  visible: false,
  saving: false,
  editingId: null as number | string | null,
  form: {
    listType: 'IP',
    value: '',
    reason: '',
    enabled: '1'
  } as RiskWhitelistVO
});
const formRules = {
  listType: [{ required: true, message: '请选择类型', trigger: 'change' }],
  value: [{ required: true, message: '请输入值', trigger: 'blur' }]
};

const onAdd = () => {
  formDialog.editingId = null;
  formDialog.form = { listType: 'IP', value: '', reason: '', enabled: '1' };
  formDialog.visible = true;
};

const onEdit = (row: RiskWhitelistVO) => {
  formDialog.editingId = row.whitelistId!;
  formDialog.form = {
    listType: row.listType,
    value: row.value,
    reason: row.reason || '',
    enabled: row.enabled || '1'
  };
  formDialog.visible = true;
};

const onConfirm = async () => {
  if (!formRef.value) return;
  await formRef.value.validate();
  formDialog.saving = true;
  try {
    if (formDialog.editingId) {
      // 编辑：调 updateWhitelist，只传可变字段（reason + enabled）
      const r: any = await updateWhitelist(formDialog.editingId, {
        reason: formDialog.form.reason,
        enabled: formDialog.form.enabled as '0' | '1'
      });
      if (r.code === 200) {
        ElMessage.success('已更新');
        formDialog.visible = false;
        loadList();
      } else {
        ElMessage.error(r.msg || '更新失败');
      }
    } else {
      // 新增：调 addWhitelist，传全字段
      const r: any = await addWhitelist({ ...formDialog.form, enabled: formDialog.form.enabled || '1' });
      if (r.code === 200) {
        ElMessage.success('已新增');
        formDialog.visible = false;
        loadList();
      } else {
        ElMessage.error(r.msg || '新增失败');
      }
    }
  } finally {
    formDialog.saving = false;
  }
};

const onDelete = async (row: RiskWhitelistVO) => {
  await ElMessageBox.confirm(`确认删除白名单 "${row.value}" ?`, '提示', { type: 'warning' });
  const r: any = await deleteWhitelist(row.whitelistId!);
  if (r.code === 200) {
    ElMessage.success('已删除');
    loadList();
  }
};

onMounted(loadList);
</script>

<style scoped lang="scss">
.risk-whitelist {
  padding: 12px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  font-weight: 600;
}
.page-title {
  font-size: 16px;
  color: var(--dcs-text-primary);
}
.page-sub {
  margin: 4px 0 0;
  color: var(--dcs-text-secondary);
  font-size: 12px;
  line-height: 1.6;
  font-weight: 400;
}
</style>
