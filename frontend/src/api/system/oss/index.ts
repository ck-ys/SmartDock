import request from '@/utils/request';
import { getToken } from '@/utils/auth';
import { OssQuery, OssVO } from './types';
import { AxiosPromise } from 'axios';

// 查询OSS对象存储列表
export function listOss(query: OssQuery): AxiosPromise<OssVO[]> {
  return request({
    url: '/resource/oss/list',
    method: 'get',
    params: query
  });
}

// 查询OSS对象基于id串
export function listByIds(ossId: string | number): AxiosPromise<OssVO[]> {
  return request({
    url: '/resource/oss/listByIds/' + ossId,
    method: 'get'
  });
}

// 删除OSS对象存储
// custom 可透传请求级配置(如 { silent: true } 走静默通道, 失败不弹拦截器红通知——三审回修 dspro P2-3)
export function delOss(ossId: string | number | Array<string | number>, custom?: Record<string, any>) {
  return request({
    url: '/resource/oss/' + ossId,
    method: 'delete',
    ...(custom ? { custom } : {})
  });
}

/**
 * 构造文件预览URL（供 <img src> 使用）
 * token 通过 query 传递，复用 SSE/WebSocket 既有模式（桶可保持 private）
 */
export function previewUrl(ossId: string | number): string {
  return (
    import.meta.env.VITE_APP_BASE_API +
    '/resource/oss/preview/' +
    ossId +
    '?Authorization=Bearer ' +
    getToken() +
    '&clientid=' +
    import.meta.env.VITE_APP_CLIENT_ID
  );
}
