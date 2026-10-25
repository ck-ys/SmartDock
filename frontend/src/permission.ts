import { to as tos } from 'await-to-js';
import router from './router';
import NProgress from 'nprogress';
import 'nprogress/nprogress.css';
import { getToken } from '@/utils/auth';
import { isHttp, isPathMatch } from '@/utils/validate';
import { isRelogin } from '@/utils/request';
import { useUserStore } from '@/store/modules/user';
import { useSettingsStore } from '@/store/modules/settings';
import { usePermissionStore } from '@/store/modules/permission';
import { restoreDynamicTenant } from '@/composables/tenantMirror';
import { ElMessage } from 'element-plus/es';

NProgress.configure({ showSpinner: false });
// /agreement/*: 用户协议/隐私政策页(2026-09-07 合规批)——必须免登录可达(未登录可读是合规红线)
const whiteList = ['/login', '/register', '/social-callback', '/register*', '/register/*', '/agreement', '/agreement/*'];

const isWhiteList = (path: string) => {
  return whiteList.some((pattern) => isPathMatch(pattern, path));
};

router.beforeEach(async (to) => {
  NProgress.start();
  if (getToken()) {
    to.meta.title && useSettingsStore().setTitle(to.meta.title as string);
    /* has token*/
    if (to.path === '/login') {
      NProgress.done();
      return { path: '/' };
    } else if (isWhiteList(to.path)) {
      return true;
    } else {
      if (useUserStore().roles.length === 0) {
        isRelogin.show = true;
        // 判断当前用户是否已拉取完user_info信息
        const [err] = await tos(useUserStore().getInfo());
        if (err) {
          await useUserStore().logout();
          ElMessage.error(err);
          return { path: '/' };
        }
        isRelogin.show = false;
        // 后端 getInfo 会清掉超管的动态租户：若本地记着切换过的单位，先重新下发给服务端再放行路由，
        // 否则壳层显示切换单位、首屏数据却回到默认单位（2026-10-24 三审 P0 回修）
        await restoreDynamicTenant(String(useUserStore().userId || ''), String(useUserStore().tenantId || ''));
        const accessRoutes = await usePermissionStore().generateRoutes();
        // 根据roles权限生成可访问的路由表
        accessRoutes.forEach((route) => {
          if (!isHttp(route.path)) {
            router.addRoute(route); // 动态添加可访问的路由表
          }
        });
        // 换账号弹回无权页面修复(2026-09-29): 消费 401 重登留下的 redirect 归属章——登录者与章主人
        // 不同人说明换了账号, redirect 作废落首页; 同人重登回原页、深链直达(无章)不受影响。章一次性, 消费即清。
        // 必须在 addRoute 之后判定: 跳首页重放时动态路由与侧边栏菜单须已就绪, 否则白屏空菜单
        const redirectOwner = sessionStorage.getItem('dcs:login-redirect-owner');
        if (redirectOwner !== null) {
          sessionStorage.removeItem('dcs:login-redirect-owner');
          if (String(useUserStore().userId || '') !== redirectOwner) {
            return { path: '/', replace: true };
          }
        }
        // hack方法 确保addRoutes已完成：以 replace 重放当前导航
        return { path: to.path, replace: true, params: to.params, query: to.query, hash: to.hash, name: to.name as string };
      } else {
        return true;
      }
    }
  } else {
    // 没有token
    if (isWhiteList(to.path)) {
      // 在免登录白名单，直接进入
      return true;
    } else {
      const redirect = encodeURIComponent(to.fullPath || '/');
      NProgress.done();
      // 否则全部重定向到登录页
      return `/login?redirect=${redirect}`;
    }
  }
});

router.afterEach(() => {
  NProgress.done();
});
