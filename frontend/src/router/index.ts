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

// 助手菜单路由常量（批一 P0-1：路径从菜单里取，不手写——对应 sys_menu 11930「AI 助手」path=ai
// 下 11927「AI助手管理」path=assistant-admin；菜单挂载位置若调整，只改这里）
export const ASSISTANT_ADMIN_PATH = '/ai/assistant-admin';

// 404 兜底路由 path（批一 P0-3：permission.ts 守卫按它识别「未命中任何路由」，
// 抽常量防两处字面量漂移后守卫静默失效——收口复审席位A P2-1。
// 顺序约束：本常量必须声明在下方 constantRoutes 数组（:31 起）之前——数组字面量求值时
// 即引用它，挪到数组之后会在模块加载期抛 TDZ ReferenceError 整站白屏（终审席位④ P2-2）
export const NOT_FOUND_ROUTE_PATH = '/:pathMatch(.*)*';

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
  // 403 说明页（批一 P0-3）：Layout 包裹保壳层（菜单/顶栏）在——区别于 401/404 整屏页；
  // 不取 name，TagsView.addTags 按 route.name 门控，错误页不留页签
  {
    path: '/403',
    component: Layout,
    hidden: true,
    children: [
      {
        path: '',
        component: () => import('@/views/error/403.vue'),
        // 与 403.vue 正文同口径：前端无法区分「无权」与「不存在」，页头不做确定性断言
        //（终审席位④ P2-1：旧值「无访问权限」与双口径正文打架，AI 助手浮层还会复述页头）
        meta: { title: '页面不可用' }
      }
    ]
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
  // 2026-10-27 画像方向第一批: 工作室/RFM/分群收进「引擎与运维」折叠组, 旧址(含 insight 前缀)兜底到新址
  { path: '/dcs/profile-studio', redirect: '/insight/engine-ops/profile-studio', hidden: true },
  { path: '/insight/profile-studio', redirect: '/insight/engine-ops/profile-studio', hidden: true },
  { path: '/dcs/profile-rfm', redirect: '/insight/engine-ops/profile-rfm', hidden: true },
  { path: '/insight/profile-rfm', redirect: '/insight/engine-ops/profile-rfm', hidden: true },
  { path: '/dcs/profile-segments', redirect: '/insight/engine-ops/profile-segments', hidden: true },
  { path: '/insight/profile-segments', redirect: '/insight/engine-ops/profile-segments', hidden: true },
  // 低分复核并入画像页抽屉; 数据运维/引擎健康/引擎管理三页合一为「引擎运维」(tab 深链)
  { path: '/dcs/profile-review', redirect: '/insight/profile?review=1', hidden: true },
  { path: '/insight/profile-review', redirect: '/insight/profile?review=1', hidden: true },
  { path: '/insight/profile-ops', redirect: '/insight/engine-ops/profile-ops', hidden: true },
  { path: '/insight/profile-health', redirect: '/insight/engine-ops/profile-ops?tab=health', hidden: true },
  { path: '/insight/engine-admin', redirect: '/insight/engine-ops/profile-ops?tab=admin', hidden: true },
  // 第二批补: 合规中心旧地址兜底(第一批挪折叠组时漏配)
  { path: '/insight/compliance', redirect: '/insight/engine-ops/compliance', hidden: true },
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
    path: NOT_FOUND_ROUTE_PATH,
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
