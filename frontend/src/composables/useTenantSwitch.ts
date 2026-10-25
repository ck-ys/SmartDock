import { computed, getCurrentInstance, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { getTenantList } from '@/api/login';
import { dynamicClear, dynamicTenant } from '@/api/system/tenant';
import { TenantVO } from '@/api/types';
import { useUserStore } from '@/store/modules/user';
import { useWorkspace } from '@/composables/useWorkspace';
import { clearTenantMirror, readTenantMirror, writeTenantMirror } from '@/composables/tenantMirror';

/**
 * 超管切换租户：左上品牌行、左下账号菜单共用这一份状态。
 * 模块级单例——租户列表一次请求，两处入口立刻同步。
 *
 * 当前单位的「记住 + 刷新回放」在 tenantMirror.ts（permission.ts 在路由放行前
 * 会先把镜像里的单位重新下发给服务端，保证标签和数据一致）。
 */
const tenants = ref<TenantVO[]>([]);
const tenantEnabled = ref(false);
const currentId = ref('');
const switching = ref(false);
// 首次加载是否已返回（无论成败）：给底区渲染同高占位，避免单位行晚到把菜单顶上去
const ready = ref(false);
let loadedFor = '';

export const useTenantSwitch = () => {
  const router = useRouter();
  const userStore = useUserStore();
  // 只在 setup 内调用；拿不到实例时跳过页面跳转相关能力（切换仍可用）
  const proxy = getCurrentInstance()?.proxy;
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
  // 底部单位行浮层用（2026-10-24 拍板 题2-1）：当前之外的单位全列，默认单位在列并带「默认」标记，
  // 点它即回默认，不再单设「回到默认单位」一行
  const unitOptions = computed(() => tenants.value.filter((item) => String(item.tenantId) !== currentId.value));

  const apply = (tenantId: string) => {
    currentId.value = tenantId;
    writeTenantMirror(userId.value, tenantId, defaultId.value);
    tenantName.value = tenants.value.find((item) => String(item.tenantId) === tenantId)?.companyName || '';
    tenantSwitched.value = !!tenantId && tenantId !== defaultId.value;
  };

  const load = async () => {
    if (!isSuperAdmin.value || loadedFor === userId.value) {
      return;
    }
    // 先占位再请求：BrandRow 与账号菜单同时 mount 也只发一次请求
    loadedFor = userId.value;
    try {
      const { data } = await getTenantList(true);
      tenantEnabled.value = data.tenantEnabled === undefined ? true : data.tenantEnabled;
      tenants.value = tenantEnabled.value ? data.voList || [] : [];
      const storedId = readTenantMirror(userId.value);
      // 服务端由 permission.ts 的回放保证与镜像一致；这里只对齐显示，镜像失效时回默认
      const stored = storedId && tenants.value.some((item) => String(item.tenantId) === storedId) ? storedId : '';
      apply(stored || defaultId.value);
    } catch {
      // 失败不记账（下次挂载重试）、不动镜像、不伪造当前单位
      loadedFor = '';
      tenants.value = [];
      currentId.value = defaultId.value;
      tenantName.value = '';
      tenantSwitched.value = false;
    } finally {
      ready.value = true;
    }
  };

  // 退出登录时服务端会清掉动态租户，本地镜像由 user store 的 logout 统一清
  const forget = () => {
    clearTenantMirror(userId.value);
    currentId.value = defaultId.value;
    tenantSwitched.value = false;
  };

  const switchTo = async (tenantId: string) => {
    if (switching.value) {
      return;
    }
    const target = tenantId === '__default' ? defaultId.value : tenantId;
    if (!target || target === currentId.value) {
      return;
    }
    switching.value = true;
    try {
      // 第一步只做切换本身：失败时状态原封不动，提示重试才有意义
      if (tenantId === '__default') {
        await dynamicClear();
      } else {
        await dynamicTenant(target);
      }
    } catch {
      ElMessage.error('切换单位失败，请重试');
      switching.value = false;
      return;
    }
    apply(target);
    try {
      await router.push('/');
      await proxy?.$tab.closeAllPage();
      await proxy?.$tab.refreshPage();
      ElMessage.success(tenantId === '__default' ? '已回到默认单位' : `已切换到「${currentTenant.value?.companyName || target}」，数据加载中`);
    } catch {
      // 单位已切成功，只是收尾导航没走完：别报「切换失败」骗用户重试
      ElMessage.warning('单位已切换，页面自动刷新失败，请手动刷新');
    } finally {
      switching.value = false;
    }
  };

  return {
    tenants,
    currentId,
    switching,
    ready,
    currentTenant,
    otherTenants,
    unitOptions,
    isSuperAdmin,
    isDefaultTenant,
    canSwitch,
    defaultId,
    load,
    forget,
    switchTo
  };
};
