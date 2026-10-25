import { dynamicTenant } from '@/api/system/tenant';

/**
 * 动态租户的本地镜像（按用户存 localStorage）。
 *
 * 为什么要镜像：后端把超管的动态租户放在 Redis（按用户），但 /system/user/getInfo
 * 在超管场景会主动清掉它——整页刷新后服务端已经回到默认单位。前端若不记一份，
 * 壳层就显示不出「当前在哪家」；只记一份而不回放给服务端，就会变成「标签是 B、数据是 A」。
 * 所以镜像只当「提示」，真正让数据跟上的动作在 restoreDynamicTenant：刷新时把记住的单位
 * 重新下发给服务端，失败（单位被删等）就删掉镜像回到默认。
 *
 * 本文件不依赖 Vue 实例与 pinia，permission.ts / user store / useTenantSwitch 都能安全调用。
 */
const KEY = 'dcs:dynamic-tenant';

export const tenantMirrorKey = (userId: string) => `${KEY}:${userId}`;

export const readTenantMirror = (userId: string) => {
  try {
    return localStorage.getItem(tenantMirrorKey(userId)) || '';
  } catch {
    return '';
  }
};

export const writeTenantMirror = (userId: string, tenantId: string, defaultId: string) => {
  try {
    if (!tenantId || tenantId === defaultId) {
      localStorage.removeItem(tenantMirrorKey(userId));
    } else {
      localStorage.setItem(tenantMirrorKey(userId), tenantId);
    }
  } catch {
    // 隐私模式禁存时不阻断切换
  }
};

export const clearTenantMirror = (userId: string) => {
  try {
    localStorage.removeItem(tenantMirrorKey(userId));
  } catch {
    // ignore
  }
};

/**
 * 整页刷新后调用：若本地记着切换单位，先把它重新下发给服务端再放行路由，
 * 保证首屏数据与壳层标签一致。非超管 / 没记过 / 记的就是默认单位时不发请求。
 */
export const restoreDynamicTenant = async (userId: string, defaultId: string) => {
  const saved = readTenantMirror(userId);
  if (!saved || saved === defaultId) {
    return;
  }
  try {
    await dynamicTenant(saved);
  } catch {
    // 单位已删或无权限：删镜像，跟随默认单位
    clearTenantMirror(userId);
  }
};
