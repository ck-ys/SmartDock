<template>
  <div class="sidebar-brand" :class="{ 'is-collapse': collapse }">
    <!-- 超管 + 开租户：整块是租户切换器（参考实现里 workspace switcher 就在这个位置） -->
    <el-dropdown v-if="canSwitch" trigger="click" placement="bottom-start" :disabled="tenantSwitching" @command="onSwitch">
      <button type="button" class="sidebar-brand__row" :title="`${title}${scope ? ' · ' + scope : ''}（点击切换租户）`">
        <img :src="logo" class="sidebar-brand__mark" alt="" />
        <span class="sidebar-brand__text">
          <b class="sidebar-brand__name">{{ title }}</b>
          <span v-if="scope" class="sidebar-brand__scope">{{ tenantSwitching ? '切换中…' : scope }}</span>
        </span>
        <el-icon class="sidebar-brand__chev"><ArrowDown /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item v-for="item in tenantList" :key="item.tenantId" :command="String(item.tenantId)" :disabled="String(item.tenantId) === currentId">
            {{ item.companyName }}
          </el-dropdown-item>
          <el-dropdown-item v-if="currentId !== defaultTenantId" divided command="__default">回到默认租户</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <!-- 普通用户：品牌回首页，不显示切换入口 -->
    <router-link v-else to="/" class="sidebar-brand__row">
      <img :src="logo" class="sidebar-brand__mark" alt="" />
      <span class="sidebar-brand__text">
        <b class="sidebar-brand__name">{{ title }}</b>
        <span v-if="scope" class="sidebar-brand__scope">{{ scope }}</span>
      </span>
    </router-link>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { getCurrentInstance } from 'vue';
import logo from '@/assets/logo/logo.png';
import { getTenantList } from '@/api/login';
import { dynamicClear, dynamicTenant } from '@/api/system/tenant';
import { TenantVO } from '@/api/types';
import { useUserStore } from '@/store/modules/user';
import { useWorkspace } from '@/composables/useWorkspace';

defineProps<{ collapse: boolean }>();

const title = import.meta.env.VITE_APP_LOGO_TITLE;
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const router = useRouter();
const userStore = useUserStore();

const tenantList = ref<TenantVO[]>([]);
const tenantEnabled = ref(false);
const tenantSwitching = ref(false);
const currentId = ref(String(userStore.tenantId || ''));
// 默认租户：登录时下发的那个，用于显示「回到默认租户」
const defaultTenantId = ref(String(userStore.tenantId || ''));

const isAdmin = computed(() => String(userStore.userId) === '1');
const canSwitch = computed(() => isAdmin.value && tenantEnabled.value && tenantList.value.length > 0);
// 左侧第二行：当前租户名；拿不到就不显示这一行（不编造文案）
const scope = computed(() => tenantList.value.find((item) => String(item.tenantId) === currentId.value)?.companyName || '');

// 写进共享态：左下用户行据此显示「角色 · 单位」，账号菜单据此决定是否显示「个人中心」
const { tenantName, tenantSwitched } = useWorkspace();
watch(scope, (value) => (tenantName.value = value), { immediate: true });
watch(currentId, (value) => (tenantSwitched.value = value !== defaultTenantId.value), { immediate: true });

onMounted(async () => {
  if (!isAdmin.value) {
    return;
  }
  try {
    const { data } = await getTenantList(true);
    tenantEnabled.value = data.tenantEnabled === undefined ? true : data.tenantEnabled;
    tenantList.value = tenantEnabled.value ? data.voList || [] : [];
  } catch {
    // 租户接口失败不阻断壳层：退回普通品牌块
    tenantList.value = [];
  }
});

// 切换租户：与旧顶栏下拉同一套流程（切完回首页、清页签、刷新），只是入口从顶栏搬到了左上
const onSwitch = async (tenantId: string) => {
  if (tenantSwitching.value) {
    return;
  }
  tenantSwitching.value = true;
  try {
    if (tenantId === '__default') {
      await dynamicClear();
      currentId.value = defaultTenantId.value;
      await router.push('/');
      await proxy?.$tab.closeAllPage();
      await proxy?.$tab.refreshPage();
      ElMessage.success('已回到默认租户');
      return;
    }
    if (tenantId === currentId.value) {
      return;
    }
    await dynamicTenant(tenantId);
    currentId.value = tenantId;
    await router.push('/');
    await proxy?.$tab.closeAllPage();
    await proxy?.$tab.refreshPage();
    const hit = tenantList.value.find((item) => String(item.tenantId) === tenantId);
    ElMessage.success(`已切换到「${hit?.companyName || tenantId}」，数据加载中`);
  } catch {
    ElMessage.error(tenantId === '__default' ? '切回默认租户失败，请重试' : '切换租户失败，请重试');
  } finally {
    tenantSwitching.value = false;
  }
};
</script>

<style lang="scss" scoped>
.sidebar-brand {
  flex: none;
  height: var(--app-topbar-h);
  display: flex;
  align-items: center;
  padding: 0 8px;
  box-sizing: border-box;
}

.sidebar-brand :deep(.el-dropdown) {
  width: 100%;
}

/* 品牌行在普通用户分支是 <a>：sidebar.scss 的菜单链接规则已排除本类，这里保持一行 flex */
.sidebar-brand .sidebar-brand__row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 48px;
  padding: 0 8px;
  border: 0;
  border-radius: var(--app-nav-radius);
  background: transparent;
  color: var(--app-text);
  font-family: inherit;
  text-align: left;
  text-decoration: none;
  cursor: pointer;

  &:hover {
    background: var(--app-raised);
  }
}

.sidebar-brand__mark {
  width: 28px;
  height: 28px;
  flex: none;
  object-fit: contain;
}

.sidebar-brand__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.25;
}

.sidebar-brand__name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sidebar-brand__scope {
  overflow: hidden;
  color: var(--app-muted);
  font-size: 12px;
  font-weight: 400;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sidebar-brand__chev {
  margin-left: auto;
  flex: none;
  color: var(--app-faint);
  font-size: 12px;
}

.sidebar-brand.is-collapse {
  padding: 0 4px;

  .sidebar-brand__row {
    justify-content: center;
    padding: 0;
  }

  .sidebar-brand__text,
  .sidebar-brand__chev {
    display: none;
  }
}
</style>
