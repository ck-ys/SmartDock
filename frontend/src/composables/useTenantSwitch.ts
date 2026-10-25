import { computed, getCurrentInstance, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { getTenantList } from '@/api/login';
import { dynamicClear, dynamicTenant } from '@/api/system/tenant';
import { TenantVO } from '@/api/types';
import { useUserStore } from '@/store/modules/user';
import { useWorkspace } from '@/composables/useWorkspace';

/**
 * 超管切换租户：左上品牌行、左下账号菜单共用这一份状态。
 * 模块级单例——租户列表一次请求，两处入口立刻同步。
 *
 * 当前租户落一份 localStorage：动态租户存在服务端 Redis（按用户），前端刷新后
 * token 里仍是登录租户，不落盘就会「切完刷新又变回默认」，也找不到回默认的入口。
 */
const DYNAMIC_TENANT_KEY = 'dcs:dynamic-tenant';

const tenants = ref<TenantVO[]>([]);
const tenantEnabled = ref(false);
const currentId = ref('');
const switching = ref(false);
let loadedFor = '';

const storageKey = (userId: string) => `${DYNAMIC_TENANT_KEY}:${userId}`;

const readStored = (userId: string) => {
  try {
    return localStorage.getItem(storageKey(userId)) || '';
  } catch {
    return '';
  }
};

const writeStored = (userId: string, tenantId: string, defaultId: string) => {
  try {
    if (!tenantId || tenantId === defaultId) {
      localStorage.removeItem(storageKey(userId));
    } else {
      localStorage.setItem(storageKey(userId), tenantId);
    }
  } catch {
    // 隐私模式禁存时不阻断切换
  }
};

export const useTenantSwitch = () => {
  const router = useRouter();
  const userStore = useUserStore();
  const { proxy } = getCurrentInstance() as ComponentInternalInstance;
  const { tenantName, tenantSwitched } = useWorkspace();

  const userId = computed(() => String(userStore.userId || ''));
  const defaultId = computed(() => String(userStore.tenantId || ''));
  const isSuperAdmin = computed(() => userId.value === '1');
  // 只有一个单位时不给切换入口：点开没得选比不显示更困惑
  const canSwitch = computed(() => isSuperAdmin.value && tenantEnabled.value && tenants.value.length > 1);
  const currentTenant = computed(() => tenants.value.find((item) => String(item.tenantId) === currentId.value));
  const isDefaultTenant = computed(() => !currentId.value || currentId.value === defaultId.value);
  // 菜单里列「其它单位」；默认单位单独用「回到默认单位」表达，避免同一个目标出现两行
  const otherTenants = computed(() =>
    tenants.value.filter((item) => {
      const id = String(item.tenantId);
      return id !== currentId.value && id !== defaultId.value;
    })
  );

  const apply = (tenantId: string) => {
    currentId.value = tenantId;
    writeStored(userId.value, tenantId, defaultId.value);
    tenantName.value = tenants.value.find((item) => String(item.tenantId) === tenantId)?.companyName || '';
    tenantSwitched.value = !!tenantId && tenantId !== defaultId.value;
  };

  const load = async () => {
    if (!isSuperAdmin.value || loadedFor === userId.value) {
      return;
    }
    loadedFor = userId.value;
    try {
      const { data } = await getTenantList(true);
      tenantEnabled.value = data.tenantEnabled === undefined ? true : data.tenantEnabled;
      tenants.value = tenantEnabled.value ? data.voList || [] : [];
      const stored = readStored(userId.value);
      // 存储值必须还在列表里才认（租户被删或换库时自动回默认）
      apply(stored && tenants.value.some((item) => String(item.tenantId) === stored) ? stored : defaultId.value);
    } catch {
      // 租户接口失败不阻断壳层：退回普通品牌块
      tenants.value = [];
      apply(defaultId.value);
    }
  };

  // 退出登录时服务端会清掉动态租户，本地镜像必须同步清，否则下次登录仍显示上个单位
  const forget = () => {
    try {
      localStorage.removeItem(storageKey(userId.value));
    } catch {
      // ignore
    }
    currentId.value = defaultId.value;
    tenantSwitched.value = false;
  };

  const switchTo = async (tenantId: string) => {    if (switching.value) {
      return;
    }
    const target = tenantId === '__default' ? defaultId.value : tenantId;
    if (!target || target === currentId.value) {
      return;
    }
    switching.value = true;
    try {
      if (tenantId === '__default') {
        await dynamicClear();
      } else {
        await dynamicTenant(target);
      }
      apply(target);
      await router.push('/');
      await proxy?.$tab.closeAllPage();
      await proxy?.$tab.refreshPage();
      ElMessage.success(
        tenantId === '__default' ? '已回到默认单位' : `已切换到「${currentTenant.value?.companyName || target}」，数据加载中`
      );
    } catch {
      ElMessage.error('切换单位失败，请重试');
    } finally {
      switching.value = false;
    }
  };

  return {
    tenants,
    currentId,
    switching,
    currentTenant,
    otherTenants,
    isSuperAdmin,
    isDefaultTenant,
    canSwitch,
    load,
    forget,
    switchTo
  };
};
