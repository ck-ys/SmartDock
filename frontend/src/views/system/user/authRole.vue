<template>
  <div class="p-2">
    <div class="panel">
      <h4 class="panel-title">基本信息</h4>
      <el-form :model="form" :inline="true">
        <el-row :gutter="10">
          <el-col :span="2.5">
            <el-form-item label="用户昵称" prop="nickName">
              <el-input v-model="form.nickName" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="2.5">
            <el-form-item label="登录账号" prop="userName">
              <el-input v-model="form.userName" disabled />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </div>
    <div class="panel">
      <h4 class="panel-title">角色信息</h4>
      <div>
        <div class="dcs-ops-table-wrap">
          <el-table
            ref="tableRef"
            v-loading="loading"
            border
            class="ui-table dcs-table-only"
            :row-key="getRowKey"
            :data="roles.slice((pageNum - 1) * pageSize, pageNum * pageSize)"
            @row-click="clickRow"
            @selection-change="handleSelectionChange"
          >
            <el-table-column label="序号" width="55" type="index" align="center">
              <template #default="scope">
                <span>{{ (pageNum - 1) * pageSize + scope.$index + 1 }}</span>
              </template>
            </el-table-column>
            <el-table-column type="selection" :reserve-selection="true" :selectable="checkSelectable" width="55"></el-table-column>
            <el-table-column label="角色编号" align="center" prop="roleId" />
            <el-table-column label="角色名称" align="center" prop="roleName" />
            <el-table-column label="权限字符" align="center" prop="roleKey" />
            <el-table-column label="创建时间" align="center" prop="createTime" width="180">
              <template #default="scope">
                <span>{{ proxy.parseTime(scope.row.createTime) }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <div class="ui-empty">暂无可分配角色</div>
            </template>
          </el-table>
        </div>
        <!-- 2026-11-13 题 2（P1-10）：≤992 表格改卡片。勾选仍走表格的选择状态（clickRow → toggleRowSelection），
             只是把整行点击换成卡片点击 + 一个勾选框，提交口径不变 -->
        <div class="dcs-mobile-list">
          <div v-if="roles.length === 0 && !loading" class="ui-empty">暂无可分配角色</div>
          <div
            v-for="row in roles.slice((pageNum - 1) * pageSize, pageNum * pageSize)"
            :key="row.roleId"
            class="dcs-mobile-card"
            :class="{ 'dcs-card--disabled': !checkSelectable(row) }"
            @click="toggleRoleCard(row)"
          >
            <div class="dcs-card-head">
              <span class="dcs-card-title">
                <!-- 勾选态只读自选择状态；点击事件交给卡片统一处理（同一路径只切换一次） -->
                <el-checkbox :model-value="selectedRoleIds.includes(row.roleId)" :disabled="!checkSelectable(row)" />
                {{ row.roleName }}
              </span>
              <span class="dcs-card-badge">
                <span class="ui-pill" :class="checkSelectable(row) ? 'ui-pill--pos' : 'ui-pill--muted'">{{ checkSelectable(row) ? '正常' : '停用' }}</span>
              </span>
            </div>
            <div class="dcs-card-meta">
              <span>权限字符 {{ row.roleKey }}</span>
            </div>
            <div class="dcs-card-time">{{ proxy?.parseTime(row.createTime) }}</div>
          </div>
        </div>
        <pagination v-show="total > 0" v-model:page="pageNum" v-model:limit="pageSize" :total="total" />
        <div class="auth-actions">
          <el-button type="primary" @click="submitForm()">提交</el-button>
          <el-button @click="close()">返回</el-button>
        </div>
        <div></div>
      </div>
    </div>
  </div>
</template>

<script setup name="AuthRole" lang="ts">
import { RoleVO } from '@/api/system/role/types';
import { getAuthRole, updateAuthRole } from '@/api/system/user';
import { UserForm } from '@/api/system/user/types';
import { RouteLocationRaw } from 'vue-router';
import { parseTime } from '@/utils/ruoyi';

const route = useRoute();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(true);
const total = ref(0);
const pageNum = ref(1);
const pageSize = ref(10);
const roleIds = ref<Array<string | number>>([]);
/** 题 2：卡片勾选态由选择事件回填（不读 row.flag——那张表在手机档是 display:none，flag 只属于表格内部状态） */
const selectedRoleIds = ref<Array<string | number>>([]);
const roles = ref<RoleVO[]>([]);
const form = ref<Partial<UserForm>>({
  nickName: undefined,
  userName: '',
  userId: undefined
});

const tableRef = ref<ElTableInstance>();

/** 单击选中行数据 */
const clickRow = (row: RoleVO) => {
  if (checkSelectable(row)) {
    row.flag = !row.flag;
    tableRef.value?.toggleRowSelection(row, row.flag);
  }
};
/** 多选框选中数据 */
const handleSelectionChange = (selection: RoleVO[]) => {
  roleIds.value = selection.map((item) => item.roleId);
  selectedRoleIds.value = roleIds.value;
};
/** 题 2：手机卡片上的勾选/点卡 = 表格的选择状态（两者共用一份，提交口径不变） */
const toggleRoleCard = (row: RoleVO) => {
  if (!checkSelectable(row)) return;
  tableRef.value?.toggleRowSelection(row);
};
/** 保存选中的数据编号 */
const getRowKey = (row: RoleVO): string => {
  return String(row.roleId);
};
/** 检查角色状态 */
const checkSelectable = (row: RoleVO): boolean => {
  return row.status === '0';
};
/** 关闭按钮 */
const close = () => {
  const obj = {
    fullPath: '',
    hash: '',
    matched: [],
    meta: undefined,
    name: undefined,
    params: undefined,
    query: undefined,
    redirectedFrom: undefined,
    path: '/system/user'
  };
  proxy?.$tab.closeOpenPage(obj as RouteLocationRaw);
};
/** 提交按钮 */
const submitForm = async () => {
  const userId = form.value.userId;
  const rIds = roleIds.value.join(',');
  await updateAuthRole({ userId: userId as string, roleIds: rIds });
  proxy?.$modal.msgSuccess('授权成功');
  close();
};

const getList = async () => {
  const userId = route.params && route.params.userId;
  if (userId) {
    loading.value = true;
    const res = await getAuthRole(userId as string);
    Object.assign(form.value, res.data.user);
    Object.assign(roles.value, res.data.roles);
    total.value = roles.value.length;
    await nextTick(() => {
      roles.value.forEach((row) => {
        if (row?.flag) {
          tableRef.value?.toggleRowSelection(row, true);
        }
      });
    });
    loading.value = false;
  }
};
onMounted(() => {
  getList();
});
</script>

<style lang="scss" scoped>
.dcs-ops-table-wrap :deep(.el-table) {
  min-width: 720px;
}

/* 2026-11-13 题 2：原来靠 margin-left:-120px 把按钮硬拉回中间，手机上会把按钮推出左缘；
   改成正常居中，桌面观感不变 */
.auth-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 24px;
}
</style>
