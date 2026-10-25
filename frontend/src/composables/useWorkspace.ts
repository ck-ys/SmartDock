import { ref } from 'vue';

/**
 * 当前工作区（租户）名 + 是否切到了非默认租户。
 * 左上品牌行拿到租户列表后写入，左下用户行读它显示「角色 · 单位」，账号菜单据此隐藏「个人中心」
 * （旧顶栏同一口径：切租户后个人信息页仍是默认租户的数据，进去只会看错人）。
 * 模块级 ref 在应用内共享，避免两个组件各拉一次租户列表。
 */
const tenantName = ref('');
const tenantSwitched = ref(false);

export const useWorkspace = () => ({ tenantName, tenantSwitched });
