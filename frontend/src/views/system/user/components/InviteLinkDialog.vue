<template>
  <el-dialog v-model="visible" title="邀请注册" width="940px" append-to-body destroy-on-close>
    <div class="invite-dialog">
      <!-- 生成表单 -->
      <el-form :inline="true" class="generate-form" @submit.prevent>
        <el-form-item v-if="isSuperAdmin" label="目标单位" class="nowrap-label">
          <el-select v-model="generateForm.tenantId" filterable placeholder="选择新用户加入的单位" style="width: 230px" @change="loadRoleOptions">
            <el-option v-for="t in tenantOptions" :key="t.tenantId" :label="t.companyName" :value="t.tenantId" />
          </el-select>
        </el-form-item>
        <el-form-item label="注册后角色" class="nowrap-label">
          <el-select v-model="generateForm.roleId" placeholder="选择角色" style="width: 190px" :loading="roleLoading">
            <el-option v-for="r in roleOptions" :key="r.roleId" :label="r.roleName" :value="r.roleId" />
          </el-select>
        </el-form-item>
        <el-form-item label="有效期" class="nowrap-label">
          <el-radio-group v-model="generateForm.validDays">
            <el-radio-button :value="7">7 天</el-radio-button>
            <el-radio-button :value="30">30 天</el-radio-button>
            <el-radio-button :value="0">永久</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Plus" :loading="generating" @click="handleGenerate">生成邀请链接</el-button>
        </el-form-item>
      </el-form>

      <!-- 最近生成：完整链接+一键复制（生成后常驻展示最近一条，便于立即转发） -->
      <el-alert v-if="latestLink" type="success" :closable="false" class="latest-link">
        <template #title>
          <div class="latest-link__row">
            <span class="latest-link__text" :title="latestLink">{{ latestLink }}</span>
            <el-button type="primary" link icon="CopyDocument" @click="copyLink(latestLink)">复制链接</el-button>
          </div>
        </template>
      </el-alert>

      <!-- 已有链接列表 -->
      <el-table v-loading="listLoading" :data="linkRows" stripe size="default" class="link-table">
        <el-table-column label="邀请码" min-width="135" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="code-text">{{ row.inviteCode }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="isSuperAdmin" label="单位" min-width="105" show-overflow-tooltip prop="companyName" />
        <el-table-column label="注册后角色" min-width="100" prop="roleName" show-overflow-tooltip />
        <el-table-column label="状态" width="78" align="center">
          <template #default="{ row }">
            <el-tag :type="statusOf(row).tagType" effect="light">{{ statusOf(row).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="有效期至" width="132" align="center">
          <template #default="{ row }">{{ row.expireTime ? formatTime(row.expireTime) : '永久' }}</template>
        </el-table-column>
        <el-table-column label="已注册" width="68" align="center" prop="useCount" />
        <el-table-column label="生成时间" width="132" align="center">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="122" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" icon="CopyDocument" @click="copyLink(buildLink(row.inviteCode))">复制</el-button>
            <el-button v-if="row.status === '0'" link type="danger" icon="CircleClose" @click="handleRevoke(row)"> 作废 </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager-row">
        <el-pagination
          v-model:current-page="pageNum"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          small
          @current-change="loadList"
        />
        <span class="pager-tip">不限注册次数，作废后链接立即失效（已注册用户不受影响）</span>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import dayjs from 'dayjs';
import { generateInviteLink, listInviteLink, listInviteRoles, revokeInviteLink, InviteLinkVO, InviteRoleOption } from '@/api/system/inviteLink';
import { listTenant } from '@/api/system/tenant';
import { useUserStore } from '@/store/modules/user';

const visible = defineModel<boolean>({ default: false });

const userStore = useUserStore();
/** 超管可选任意租户生成（单位管理员后端锁本租户，前端不显示租户下拉） */
const isSuperAdmin = computed(() => userStore.roles.includes('superadmin'));

const generateForm = reactive<{ tenantId: string; roleId: string | number | undefined; validDays: number }>({
  tenantId: '',
  roleId: undefined,
  validDays: 7
});
const tenantOptions = ref<any[]>([]);
const roleOptions = ref<InviteRoleOption[]>([]);
const roleLoading = ref(false);
const generating = ref(false);
const latestLink = ref('');

const linkRows = ref<InviteLinkVO[]>([]);
const listLoading = ref(false);
const pageNum = ref(1);
const pageSize = 10;
const total = ref(0);

watch(visible, (open) => {
  if (open) {
    pageNum.value = 1;
    if (isSuperAdmin.value && tenantOptions.value.length === 0) {
      listTenant({ pageNum: 1, pageSize: 100, tenantId: '', contactUserName: '', contactPhone: '', companyName: '' })
        .then((res: any) => {
          // 下拉先滤掉过期/停用单位（后端生成时也会拦 loginable，提前滤免得选完才报错；E2E 实证过期租户会被拦）
          tenantOptions.value = (res.rows || []).filter(
            (t: any) => t.status !== '1' && (!t.expireTime || dayjs(t.expireTime).isAfter(dayjs()))
          );
        })
        .catch(() => {
          // 拉取失败下拉为空，可重开重试（本接口仅超管有权，不弹全局错误——批1 同款处理）
        });
    }
    loadRoleOptions();
    loadList();
  } else {
    latestLink.value = '';
  }
});

function loadRoleOptions() {
  roleLoading.value = true;
  generateForm.roleId = undefined;
  listInviteRoles(isSuperAdmin.value && generateForm.tenantId ? generateForm.tenantId : undefined)
    .then((res: any) => {
      roleOptions.value = res.data || [];
    })
    .catch(() => {
      roleOptions.value = [];
    })
    .finally(() => {
      roleLoading.value = false;
    });
}

function handleGenerate() {
  if (!generateForm.roleId) {
    ElMessage.warning('请选择注册后角色');
    return;
  }
  if (isSuperAdmin.value && !generateForm.tenantId) {
    ElMessage.warning('请选择目标单位');
    return;
  }
  const expireTime = generateForm.validDays > 0 ? dayjs().add(generateForm.validDays, 'day').format('YYYY-MM-DD HH:mm:ss') : null;
  generating.value = true;
  generateInviteLink({
    tenantId: isSuperAdmin.value ? generateForm.tenantId : undefined,
    roleId: generateForm.roleId,
    expireTime
  })
    .then((res: any) => {
      const code: string = res.data?.inviteCode || '';
      latestLink.value = code ? buildLink(code) : '';
      ElMessage.success('邀请链接已生成');
      pageNum.value = 1;
      loadList();
    })
    .finally(() => {
      generating.value = false;
    });
}

function loadList() {
  listLoading.value = true;
  listInviteLink({ pageNum: pageNum.value, pageSize })
    .then((res: any) => {
      linkRows.value = res.rows || [];
      total.value = res.total || 0;
    })
    .finally(() => {
      listLoading.value = false;
    });
}

function handleRevoke(row: InviteLinkVO) {
  ElMessageBox.confirm(`确定作废该邀请链接？作废后新用户无法再通过它注册（已注册用户不受影响）。`, '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(() => {
      revokeInviteLink(row.linkId).then(() => {
        ElMessage.success('已作废');
        loadList();
      });
    })
    .catch(() => {});
}

/** 链接环境动态：前端拼 origin，后端只存邀请码 */
function buildLink(code: string) {
  return `${window.location.origin}/register?invite=${code}`;
}

/** 复制配方与任务ID同款：clipboard API 优先，execCommand 兜底（安全源陷阱 2026-09-17 坑） */
async function copyLink(text: string) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      ElMessage.success('链接已复制');
      return;
    }
  } catch {
    // clipboard 失败走兜底，不在此 return
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  const ok = document.execCommand('copy');
  ta.remove();
  if (ok) {
    ElMessage.success('链接已复制');
  } else {
    ElMessage.warning('复制失败，请手动划选复制');
  }
}

/** 状态三态：作废(灰) > 已过期(灰) > 有效(绿) */
function statusOf(row: InviteLinkVO): { label: string; tagType: 'success' | 'info' } {
  if (row.status === '1') return { label: '已作废', tagType: 'info' };
  if (row.expireTime && dayjs(row.expireTime).isBefore(dayjs())) return { label: '已过期', tagType: 'info' };
  return { label: '有效', tagType: 'success' };
}

function formatTime(t?: string) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-';
}
</script>

<script lang="ts">
export default { name: 'InviteLinkDialog' };
</script>

<style lang="scss" scoped>
.invite-dialog {
  .generate-form {
    /* inline 表单 label 默认 5em 放不下"注册后角色"四个字会被截断（视觉复核 6 分硬伤①） */
    :deep(.el-form-item) {
      margin-bottom: 12px;
    }

    :deep(.nowrap-label .el-form-item__label) {
      white-space: nowrap;
    }
  }

  .latest-link {
    margin-bottom: 12px;

    .latest-link__row {
      display: flex;
      align-items: center;
      gap: 8px;
      min-width: 0;
    }

    .latest-link__text {
      flex: 1;
      min-width: 0;
      font-size: 13px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      direction: rtl; /* 长链接从尾部对齐，露出 ?invite= 码段 */
      text-align: left;
    }
  }

  .link-table {
    :deep(.el-table__cell) {
      padding: 10px 0;
    }

    .code-text {
      font-family: monospace;
      font-size: 12px;
      color: #606266;
    }
  }

  .pager-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 10px;

    .pager-tip {
      font-size: 12px;
      color: #909399;
    }
  }
}
</style>
