import request from '@/utils/request';
import { AxiosPromise } from 'axios';

const BASE = '/dcs/service-charge-rule';

export interface ServiceChargeRuleVO {
  ruleId: number;
  tenantId?: string;
  serviceCode: string;
  serviceName: string;
  chargeMode: string;
  fallbackPolicy: string;
  walletScope: string;
  fixedAmount: number;
  lowBalanceThreshold: number;
  enabled: string;
  sortOrder: number;
  remark: string;
}

export const listServiceChargeRule = (params?: any): AxiosPromise<ServiceChargeRuleVO[]> => request({ url: `${BASE}/list`, method: 'get', params });

export const getServiceChargeRule = (ruleId: number): AxiosPromise<ServiceChargeRuleVO> => request({ url: `${BASE}/${ruleId}`, method: 'get' });

export const addServiceChargeRule = (data: Partial<ServiceChargeRuleVO>) => request({ url: BASE, method: 'post', data });

export const updateServiceChargeRule = (data: Partial<ServiceChargeRuleVO>) => request({ url: `${BASE}/edit`, method: 'post', data });

export const delServiceChargeRule = (ruleIds: number | number[]) => request({ url: `${BASE}/${ruleIds}`, method: 'post' });
