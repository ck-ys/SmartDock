<template>
  <div>
    <div class="dcs-ops-table-wrap">
      <el-table :data="devices" border class="ui-table" style="width: 100%; height: 100%">
        <el-table-column label="设备类型" align="center">
          <template #default="scope">
            <dict-tag :options="sys_device_type" :value="scope.row.deviceType" />
          </template>
        </el-table-column>
        <el-table-column label="主机" align="center" prop="ipaddr" :show-overflow-tooltip="true" />
        <el-table-column label="登录地点" align="center" prop="loginLocation" :show-overflow-tooltip="true" />
        <el-table-column label="操作系统" align="center" prop="os" :show-overflow-tooltip="true" />
        <el-table-column label="浏览器" align="center" prop="browser" :show-overflow-tooltip="true" />
        <el-table-column label="登录时间" align="center" prop="loginTime" width="180">
          <template #default="scope">
            <span>{{ proxy.parseTime(scope.row.loginTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="删除" placement="top">
              <el-button link type="primary" icon="Delete" @click="handldDelOnline(scope.row)"> </el-button>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <div class="ui-empty">暂无在线设备</div>
        </template>
      </el-table>
    </div>
  </div>
</template>

<script setup name="Online" lang="ts">
import { delOnline } from '@/api/monitor/online';
import { propTypes } from '@/utils/propTypes';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { sys_device_type } = toRefs<any>(proxy?.useDict('sys_device_type'));

const props = defineProps({
  devices: propTypes.any.isRequired
});
const devices = computed(() => props.devices);

/** 删除按钮操作 */
const handldDelOnline = (row: any) => {
  ElMessageBox.confirm('删除设备后，在该设备登录需要重新进行验证')
    .then(() => {
      return delOnline(row.tokenId);
    })
    .then((res: any) => {
      if (res.code === 200) {
        proxy?.$modal.msgSuccess('删除成功');
        proxy?.$tab.refreshPage();
      } else {
        proxy?.$modal.msgError(res.msg);
      }
    })
    .catch(() => {});
};
</script>

<style lang="scss" scoped>
.dcs-ops-table-wrap :deep(.el-table) {
  min-width: 680px;
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
