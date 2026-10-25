<template>
  <div class="p-2">
    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <div v-show="showSearch" class="mb-[10px]">
        <el-card shadow="hover">
          <el-form ref="queryFormRef" :model="queryParams" :inline="true">
            <el-form-item label="登录地址" prop="ipaddr">
              <el-input v-model="queryParams.ipaddr" placeholder="请输入登录地址" clearable @keyup.enter="handleQuery" />
            </el-form-item>
            <el-form-item label="用户名称" prop="userName">
              <el-input v-model="queryParams.userName" placeholder="请输入用户名称" clearable @keyup.enter="handleQuery" />
            </el-form-item>
            <el-form-item label="状态" prop="status">
              <el-select v-model="queryParams.status" placeholder="登录状态" clearable>
                <el-option v-for="dict in sys_common_status" :key="dict.value" :label="dict.label" :value="dict.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="登录时间">
              <el-date-picker
                v-model="dateRange"
                value-format="YYYY-MM-DD HH:mm:ss"
                type="daterange"
                range-separator="-"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                :default-time="[new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]"
              ></el-date-picker>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
              <el-button icon="Refresh" @click="resetQuery">重置</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </div>
    </transition>

    <el-card shadow="hover">
      <template #header>
        <el-row :gutter="10" class="mb8">
          <el-col :span="1.5">
            <el-button v-hasPermi="['monitor:logininfor:remove']" type="danger" plain icon="Delete" :disabled="multiple" @click="handleDelete()">
              删除
            </el-button>
          </el-col>
          <el-col :span="1.5">
            <el-button v-hasPermi="['monitor:logininfor:remove']" type="danger" plain icon="Delete" @click="handleClean">清空</el-button>
          </el-col>
          <el-col :span="1.5">
            <el-button v-hasPermi="['monitor:logininfor:unlock']" type="primary" plain icon="Unlock" :disabled="single" @click="handleUnlock">
              解锁
            </el-button>
          </el-col>
          <el-col :span="1.5">
            <el-button v-hasPermi="['monitor:logininfor:export']" type="warning" plain icon="Download" @click="handleExport">导出</el-button>
          </el-col>
          <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
        </el-row>
      </template>

      <div class="dcs-ops-table-wrap">
        <el-table
          ref="loginInfoTableRef"
          v-loading="loading"
          :data="loginInfoList"
          :default-sort="defaultSort"
          border
          class="ui-table"
          @selection-change="handleSelectionChange"
          @sort-change="handleSortChange"
        >
          <el-table-column type="selection" width="55" align="center" />
          <el-table-column label="访问编号" align="center" prop="infoId" />
          <el-table-column
            label="用户名称"
            align="center"
            prop="userName"
            :show-overflow-tooltip="true"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
          />
          <el-table-column label="客户端" align="center" prop="clientKey" :show-overflow-tooltip="true" />
          <el-table-column label="设备类型" align="center">
            <template #default="scope">
              <dict-tag :options="sys_device_type" :value="scope.row.deviceType" />
            </template>
          </el-table-column>
          <el-table-column label="地址" align="center" prop="ipaddr" :show-overflow-tooltip="true" />
          <el-table-column label="登录地点" align="center" prop="loginLocation" :show-overflow-tooltip="true" />
          <el-table-column label="操作系统" align="center" prop="os" :show-overflow-tooltip="true" />
          <el-table-column label="浏览器" align="center" prop="browser" :show-overflow-tooltip="true" />
          <el-table-column label="登录状态" align="center" prop="status">
            <template #default="scope">
              <dict-tag :options="sys_common_status" :value="scope.row.status" />
            </template>
          </el-table-column>
          <el-table-column label="描述" align="center" prop="msg" :show-overflow-tooltip="true" />
          <el-table-column label="访问时间" align="center" prop="loginTime" sortable="custom" :sort-orders="['descending', 'ascending']" width="180">
            <template #default="scope">
              <span>{{ proxy.parseTime(scope.row.loginTime) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="ui-empty">暂无登录日志</div>
          </template>
        </el-table>
      </div>

      <pagination v-show="total > 0" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" :total="total" @pagination="getList" />
    </el-card>
  </div>
</template>

<script setup name="Logininfor" lang="ts">
import { list, delLoginInfo, cleanLoginInfo, unlockLoginInfo } from '@/api/monitor/loginInfo';
import { LoginInfoQuery, LoginInfoVO } from '@/api/monitor/loginInfo/types';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { sys_device_type } = toRefs<any>(proxy?.useDict('sys_device_type'));
const { sys_common_status } = toRefs<any>(proxy?.useDict('sys_common_status'));

const loginInfoList = ref<LoginInfoVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const ids = ref<Array<number | string>>([]);
const single = ref(true);
const multiple = ref(true);
const selectName = ref<Array<string>>([]);
const selectTenantId = ref<Array<string>>([]);
const total = ref(0);
const dateRange = ref<[string, string]>(['', '']);
const defaultSort = ref<any>({ prop: 'loginTime', order: 'descending' });

const queryFormRef = ref<ElFormInstance>();
const loginInfoTableRef = ref<ElTableInstance>();
// 查询参数
const queryParams = ref<LoginInfoQuery>({
  pageNum: 1,
  pageSize: 10,
  ipaddr: '',
  userName: '',
  status: '',
  orderByColumn: defaultSort.value.prop,
  isAsc: defaultSort.value.order
});

/** 查询登录日志列表 */
const getList = async () => {
  loading.value = true;
  const res = await list(proxy?.addDateRange(queryParams.value, dateRange.value));
  loginInfoList.value = res.rows;
  total.value = res.total;
  loading.value = false;
};
/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};
/** 重置按钮操作 */
const resetQuery = () => {
  dateRange.value = ['', ''];
  queryFormRef.value?.resetFields();
  queryParams.value.pageNum = 1;
  loginInfoTableRef.value?.sort(defaultSort.value.prop, defaultSort.value.order);
};
/** 多选框选中数据 */
const handleSelectionChange = (selection: LoginInfoVO[]) => {
  ids.value = selection.map((item) => item.infoId);
  multiple.value = !selection.length;
  single.value = selection.length != 1;
  selectName.value = selection.map((item) => item.userName);
  // 解锁按日志行所属租户拼锁定 key（key 带租户前缀），须随选择同步记录
  selectTenantId.value = selection.map((item) => String(item.tenantId));
};
/** 排序触发事件 */
const handleSortChange = (column: any) => {
  queryParams.value.orderByColumn = column.prop;
  queryParams.value.isAsc = column.order;
  getList();
};
/** 删除按钮操作 */
const handleDelete = async (row?: LoginInfoVO) => {
  const infoIds = row?.infoId || ids.value;
  await proxy?.$modal.confirm('是否确认删除访问编号为"' + infoIds + '"的数据项?');
  await delLoginInfo(infoIds);
  await getList();
  proxy?.$modal.msgSuccess('删除成功');
};
/** 清空按钮操作 */
const handleClean = async () => {
  await proxy?.$modal.confirm('是否确认清空所有登录日志数据项?');
  await cleanLoginInfo();
  await getList();
  proxy?.$modal.msgSuccess('清空成功');
};
/** 解锁按钮操作 */
const handleUnlock = async () => {
  const username = selectName.value;
  const tenantId = selectTenantId.value[0];
  await proxy?.$modal.confirm('是否确认解锁用户"' + username + '"数据项?');
  await unlockLoginInfo(username, tenantId);
  proxy?.$modal.msgSuccess('用户' + username + '解锁成功');
};
/** 导出按钮操作 */
const handleExport = () => {
  proxy?.download(
    'monitor/logininfor/export',
    {
      ...queryParams.value
    },
    `logininfor_${new Date().getTime()}.xlsx`
  );
};

onMounted(() => {
  getList();
});
</script>

<style lang="scss" scoped>
.dcs-ops-table-wrap :deep(.el-table) {
  min-width: 980px;
}

/* 字典状态标签统一成药丸形（共用 DictTag 渲染的 el-tag 只调形状，不换组件） */
:deep(.el-tag) {
  height: 22px;
  padding: 0 10px;
  border-radius: var(--dcs-pill-radius);
  font-size: var(--dcs-font-xs);
  font-weight: 500;
}

:deep(.el-tag:not(.el-tag--plain)) {
  border-color: transparent;
}
</style>
