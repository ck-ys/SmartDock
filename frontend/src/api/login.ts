import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { LoginData, LoginResult, VerifyCodeResult, TenantInfo, TenantVO } from './types';
import { UserInfo } from '@/api/system/user/types';

// pc端固定客户端授权id
const clientId = import.meta.env.VITE_APP_CLIENT_ID;

/**
 * @param data {LoginData}
 * @returns
 */
export function login(data: LoginData): AxiosPromise<LoginResult> {
  const params = {
    ...data,
    clientId: data.clientId || clientId,
    grantType: data.grantType || 'password'
  };
  return request({
    url: '/auth/login',
    headers: {
      isToken: false,
      isEncrypt: true,
      repeatSubmit: false
    },
    method: 'post',
    data: params
  });
}

// 注册方法
export function register(data: any) {
  const params = {
    ...data,
    clientId: clientId,
    grantType: 'password'
  };
  return request({
    url: '/auth/register',
    headers: {
      isToken: false,
      isEncrypt: true,
      repeatSubmit: false
    },
    method: 'post',
    data: params
  });
}

/**
 * 注册页按邀请码取链接信息（批3 2026-09-26，匿名）
 * 无效/作废/过期码后端统一报"邀请链接无效或已失效"，不区分原因防打探
 */
export function getInviteInfo(code: string): AxiosPromise<{
  tenantId: string;
  companyName: string;
  roleId: string | number;
  roleName: string;
  expireTime: string | null;
}> {
  return request({
    url: '/auth/invite/info',
    headers: { isToken: false },
    method: 'get',
    params: { code }
  });
}

/**
 * 忘记密码·发送邮箱验证码（批4 2026-09-26，匿名）
 * tenantId 由 /auth/tenant/listByLoginId 识别后选定传入；返回打码邮箱供 toast 展示
 */
export function sendPwdResetCode(tenantId: string, loginId: string): AxiosPromise<string> {
  return request({
    url: '/auth/pwd-reset/code',
    headers: { isToken: false },
    method: 'get',
    params: { tenantId, loginId }
  });
}

/**
 * 忘记密码·提交重置（验证码+新密码；成功后验证码立即失效）
 */
export function commitPwdReset(data: { tenantId: string; loginId: string; code: string; password: string }) {
  return request({
    url: '/auth/pwd-reset/commit',
    headers: { isToken: false, repeatSubmit: false },
    method: 'post',
    data
  });
}

/**
 * 注销
 */
export function logout() {
  if (import.meta.env.VITE_APP_SSE === 'true') {
    request({
      url: '/resource/sse/close',
      method: 'get'
    });
  }
  return request({
    url: '/auth/logout',
    method: 'post'
  });
}

/**
 * 获取验证码
 */
export function getCodeImg(): AxiosPromise<VerifyCodeResult> {
  return request({
    url: '/auth/code',
    headers: {
      isToken: false
    },
    method: 'get',
    timeout: 20000
  });
}

/**
 * 第三方登录
 */
export function callback(data: LoginData): AxiosPromise<any> {
  const LoginData = {
    ...data,
    clientId: clientId,
    grantType: 'social'
  };
  return request({
    url: '/auth/social/callback',
    method: 'post',
    data: LoginData
  });
}

// 获取用户详细信息
export function getInfo(): AxiosPromise<UserInfo> {
  return request({
    url: '/system/user/getInfo',
    method: 'get'
  });
}

// 获取租户列表
export function getTenantList(isToken: boolean): AxiosPromise<TenantInfo> {
  return request({
    url: '/auth/tenant/list',
    headers: {
      isToken: isToken
    },
    method: 'get'
  });
}

// 按账号/手机号识别可登录单位（登录改造 2026-09-25：0=账号不存在 1=直接进 N=弹窗选）
export function getTenantsByLoginId(loginId: string): AxiosPromise<TenantVO[]> {
  return request({
    url: '/auth/tenant/listByLoginId',
    headers: {
      isToken: false
    },
    params: { loginId },
    method: 'get'
  });
}
