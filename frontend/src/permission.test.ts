import { describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  router: { beforeEach: vi.fn(), afterEach: vi.fn(), addRoute: vi.fn() },
  nprogress: { configure: vi.fn(), start: vi.fn(), done: vi.fn() },
  getToken: vi.fn(),
  userStore: { roles: ['common'], permissions: ['agentws:workspace:use'], getInfo: vi.fn(), logout: vi.fn() },
  settingsStore: { setTitle: vi.fn() },
  permissionStore: { generateRoutes: vi.fn() }
}));

vi.mock('./router', () => ({ default: mocks.router }));
vi.mock('nprogress', () => ({ default: mocks.nprogress }));
vi.mock('nprogress/nprogress.css', () => ({}));
vi.mock('@/utils/auth', () => ({ getToken: mocks.getToken }));
vi.mock('@/utils/request', () => ({ isRelogin: { show: false } }));
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.userStore }));
vi.mock('@/store/modules/settings', () => ({ useSettingsStore: () => mocks.settingsStore }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => mocks.permissionStore }));
vi.mock('element-plus/es', () => ({ ElMessage: { error: vi.fn() } }));

import './permission';

type Guard = (to: Record<string, any>, from: Record<string, any>) => unknown;

const registeredGuard = (): Guard => {
  const call = mocks.router.beforeEach.mock.calls.at(-1);
  expect(call).toBeDefined();
  return call![0] as Guard;
};

describe('global router guard (return-style navigation)', () => {
  it('redirects unauthenticated protected access to login keeping the redirect target', async () => {
    mocks.getToken.mockReturnValue(null);
    const guard = registeredGuard();
    const result = await guard({ path: '/dcs/mission', fullPath: '/dcs/mission?tab=1', meta: {} }, {});
    expect(result).toBe(`/login?redirect=${encodeURIComponent('/dcs/mission?tab=1')}`);
  });

  it('allows whitelist paths without a token', async () => {
    mocks.getToken.mockReturnValue(null);
    const guard = registeredGuard();
    expect(await guard({ path: '/login', fullPath: '/login', meta: {} }, {})).toBe(true);
    expect(await guard({ path: '/register', fullPath: '/register', meta: {} }, {})).toBe(true);
  });

  it('sends authenticated users away from the login page', async () => {
    mocks.getToken.mockReturnValue('token');
    const guard = registeredGuard();
    expect(await guard({ path: '/login', fullPath: '/login', meta: {} }, {})).toEqual({ path: '/' });
  });

  it('有工作台权限时，AI 助手菜单默认改道 /agent（含尾斜杠）', async () => {
    mocks.getToken.mockReturnValue('token');
    mocks.userStore.permissions = ['agentws:workspace:use'];
    const guard = registeredGuard();
    expect(await guard({ path: '/ai/assistant', fullPath: '/ai/assistant', query: {}, meta: {} }, {})).toEqual({
      path: '/agent',
      replace: true
    });
    expect(await guard({ path: '/ai/assistant/', fullPath: '/ai/assistant/', query: {}, meta: {} }, {})).toEqual({
      path: '/agent',
      replace: true
    });
  });

  it('工作台交接（from=agent）放行会话页', async () => {
    mocks.getToken.mockReturnValue('token');
    mocks.userStore.permissions = ['agentws:workspace:use'];
    const guard = registeredGuard();
    expect(await guard({ path: '/ai/assistant', fullPath: '/ai/assistant?from=agent', query: { from: 'agent' }, meta: {}, matched: [] }, {})).toBe(
      true
    );
  });

  it('没有工作台权限的存量角色不劫持，仍进会话页', async () => {
    mocks.getToken.mockReturnValue('token');
    mocks.userStore.permissions = ['dcs:assistant:use'];
    const guard = registeredGuard();
    expect(await guard({ path: '/ai/assistant', fullPath: '/ai/assistant', query: {}, meta: {}, matched: [] }, {})).toBe(true);
  });
});
