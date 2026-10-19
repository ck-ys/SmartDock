import request from '@/utils/request';
import { LoginInfoQuery, LoginInfoVO } from './types';
import { AxiosPromise } from 'axios';

// 查询登录日志列表
export function list(query: LoginInfoQuery): AxiosPromise<LoginInfoVO[]> {
  return request({
    url: '/monitor/logininfor/list',
    method: 'get',
    params: query
  });
}

// 删除登录日志
export function delLoginInfo(infoId: string | number | Array<string | number>) {
  return request({
    url: '/monitor/logininfor/' + infoId,
    method: 'delete'
  });
}

// 解锁用户登录状态（锁定 key 带租户前缀，须按日志行所属租户拼 key）
export function unlockLoginInfo(userName: string | Array<string>, tenantId: string) {
  return request({
    url: '/monitor/logininfor/unlock/' + userName,
    method: 'get',
    params: { tenantId }
  });
}

// 清空登录日志
export function cleanLoginInfo() {
  return request({
    url: '/monitor/logininfor/clean',
    method: 'delete'
  });
}
