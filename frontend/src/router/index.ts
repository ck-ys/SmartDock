import { createWebHistory, createRouter, RouteRecordRaw } from 'vue-router';
/* Layout */
import Layout from '@/layout/index.vue';

/**
 * Note: 路由配置项 (DCS 模块已集成)
 *
 * hidden: true            // 不在侧边栏出现 (如详情/编辑页)
 * redirect: noRedirect    // 面包屑中不可点击
 * name: 'router-name'     // 路由名(配合keep-alive)
 * roles: ['admin']        // 路由权限角色
 * permissions: ['x:x:x']  // 路由权限按钮
 * meta : {
 *   noCache: true         // 不被keep-alive缓存
 *   title: 'title'        // 侧边栏/面包屑名
 *   icon: 'svg-name'      // 图标
 *   breadcrumb: false     // 不在breadcrumb显示
 *   activeMenu: '/x/y'    // 高亮侧边栏菜单
 * }
 */

// 公共路由
export const constantRoutes: RouteRecordRaw[] = [
  {
    path: '/redirect',
    component: Layout,
    hidden: true,
    children: [
      {
        path: '/redirect/:path(.*)',
        component: () => import('@/views/redirect/index.vue')
      }
    ]
  },
  {
    path: '/social-callback',
    hidden: true,
    component: () => import('@/layout/components/SocialCallback/index.vue')
  },
  {
    path: '/login',
    component: () => import('@/views/login.vue'),
    hidden: true
  },
  {
    path: '/register',
    component: () => import('@/views/register.vue'),
    hidden: true
  },
  // 协议页(2026-09-07 合规批): 免登录可达——顶层平铺不进 Layout(Layout onMounted 会拉起 SSE/WS, 协议页不应触发)
  {
    path: '/agreement/user',
    component: () => import('@/views/agreement/UserAgreement.vue'),
    hidden: true
  },
  {
    path: '/agreement/privacy',
    component: () => import('@/views/agreement/PrivacyPolicy.vue'),
    hidden: true
  },
  {
    path: '/401',
    component: () => import('@/views/error/401.vue'),
    hidden: true
  },
  {
    path: '',
    component: Layout,
    redirect: '/index',
    children: [
      {
        path: '/index',
        component: () => import('@/views/index.vue'),
        name: 'Index',
        meta: { title: '首页', icon: 'dashboard', affix: true }
      }
    ]
  },
  {
    path: '/user',
    component: Layout,
    hidden: true,
    redirect: 'noredirect',
    children: [
      {
        path: 'profile',
        component: () => import('@/views/system/user/profile/index.vue'),
        name: 'Profile',
        meta: { title: '个人中心', icon: 'user' }
      }
    ]
  },

  // ============================================================
  //  DCS 模块 - 隐藏的内部路由 (不在菜单展示, 通过编程式跳转)
  // ============================================================

  {
    path: '/dcs/mission/detail',
    component: Layout,
    hidden: true,
    permissions: ['dcs:submission:submit', 'dcs:task:list'],
    children: [
      {
        path: ':id',
        component: () => import('@/views/dcs/mission/detail.vue'),
        name: 'DcsMissionDetail',
        meta: { title: '任务详情', activeMenu: '/dcs/mission' }
      }
    ]
  },
  {
    path: '/insight/profile-detail',
    component: Layout,
    hidden: true,
    permissions: ['profile:score:query'],
    children: [
      {
        path: ':source/:subjectId',
        component: () => import('@/views/dcs/profile/detail.vue'),
        name: 'DcsProfileDetail',
        meta: { title: '画像详情', activeMenu: '/insight/profile' }
      }
    ]
  },
  // 2026-10-20 菜单 IA：画像域迁入「画像与分析」，旧地址重定向兜底
  // （对端批 8 E2E 若仍用旧地址也不会 404）
  { path: '/dcs/profile', redirect: '/insight/profile', hidden: true },
  { path: '/dcs/profile-studio', redirect: '/insight/profile-studio', hidden: true },
  { path: '/dcs/profile-rfm', redirect: '/insight/profile-rfm', hidden: true },
  { path: '/dcs/profile-segments', redirect: '/insight/profile-segments', hidden: true },
  { path: '/dcs/profile-review', redirect: '/insight/profile-review', hidden: true },
  { path: '/dcs/profile-analytics', redirect: '/insight/profile-analytics', hidden: true },
  {
    path: '/dcs/profile-detail/:source/:subjectId',
    redirect: (to) => ({ path: `/insight/profile-detail/${to.params.source}/${to.params.subjectId}`, query: to.query }),
    hidden: true
  },
  {
    path: '/dcs/review-task',
    component: Layout,
    hidden: true,
    children: [
      {
        path: '',
        component: () => import('@/views/dcs/audit/index.vue'),
        name: 'DcsDirectReviewTask',
        meta: { title: '审核事项' }
      }
    ]
  },
  // 注: 工作台 (/dcs/workbench) 已改由后端菜单驱动 (sys_menu menu_id=9009),
  //     见 backend/script/sql/postgres/postgres_ry_dcs_menu.sql

  // 菜单IA重组批2（2026-10-16）：租户钱包/钱包流水/充值订单 三菜单合并为「账户与账单」，
  // 旧收藏/通知深链地址兜底跳转（对应 sys_menu 9067/9068/9096，页面本体由后端菜单驱动）
  {
    path: '/tenant/tenant-wallet',
    redirect: '/tenant/account-billing?tab=overview',
    hidden: true
  },
  {
    path: '/tenant/tenant-wallet-ledger',
    redirect: '/tenant/account-billing?tab=ledger',
    hidden: true
  },
  {
    path: '/tenant/wallet-payment-order',
    redirect: '/tenant/account-billing?tab=orders',
    hidden: true
  },
  // 批3审查回修（2026-10-16）：识别域名旧菜单地址（9100 已删）兜底跳 ai-config 第 4 页签深链
  {
    path: '/tenant/ai-doc-domain',
    redirect: '/tenant/ai-config?tab=domains',
    hidden: true
  },

  // 404 兜底必须放最后
  {
    path: '/:pathMatch(.*)*',
    component: () => import('@/views/error/404.vue'),
    hidden: true
  }
];

// 动态路由，基于用户权限动态去加载
export const dynamicRoutes: RouteRecordRaw[] = [];

/**
 * 创建路由
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.VITE_APP_CONTEXT_PATH),
  routes: constantRoutes,
  // 刷新时，滚动条位置还原
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition;
    }
    return { top: 0 };
  }
});

export default router;
