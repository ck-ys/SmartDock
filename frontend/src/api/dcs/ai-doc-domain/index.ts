import request from '@/utils/request';
import { AxiosPromise } from 'axios';

const BASE = '/dcs/ai-doc-domain';

export interface AiDocDomainVO {
  domainId: number;
  tenantId?: string;
  domain: string;
  provider: string;
  remark: string;
  enabled: string;
  createTime: string;
}

export const listAiDocDomain = (params?: any): AxiosPromise<AiDocDomainVO[]> => request({ url: `${BASE}/list`, method: 'get', params });

export const getAiDocDomain = (domainId: number): AxiosPromise<AiDocDomainVO> => request({ url: `${BASE}/${domainId}`, method: 'get' });

export const addAiDocDomain = (data: Partial<AiDocDomainVO>) => request({ url: BASE, method: 'post', data });

export const updateAiDocDomain = (data: Partial<AiDocDomainVO>) => request({ url: `${BASE}/edit`, method: 'post', data });

export const delAiDocDomain = (domainIds: number | number[]) => request({ url: `${BASE}/${domainIds}`, method: 'post' });
