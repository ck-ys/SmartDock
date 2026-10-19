import request from '@/utils/request';
import { AxiosPromise } from 'axios';

const BASE = '/dcs/tenant-wallet';

export interface TenantWalletVO {
  walletId: number;
  tenantId: string;
  walletType: string;
  balanceAmount: number;
  frozenAmount: number;
  availableAmount: number;
  currency: string;
  status: string;
  lowBalanceThreshold: number;
  negativeAllowed: string;
  remark: string;
  createTime: string;
}

export interface WalletOperationForm {
  amount: number;
  reason: string;
  idempotencyKey?: string;
}

export const listTenantWallet = (params?: any): AxiosPromise<TenantWalletVO[]> => request({ url: `${BASE}/list`, method: 'get', params });

export const getTenantWallet = (walletId: number): AxiosPromise<TenantWalletVO> => request({ url: `${BASE}/${walletId}`, method: 'get' });

export const initMainWallet = (tenantId?: string) => request({ url: `${BASE}/init-main`, method: 'post', params: { tenantId } });

export const manualRecharge = (walletId: number, data: WalletOperationForm) =>
  request({ url: `${BASE}/${walletId}/manual-recharge`, method: 'post', data });

export const manualDeduct = (walletId: number, data: WalletOperationForm) =>
  request({ url: `${BASE}/${walletId}/manual-deduct`, method: 'post', data });

export const updateWalletThreshold = (walletId: number, lowBalanceThreshold: number) =>
  request({ url: `${BASE}/${walletId}/threshold`, method: 'post', data: { lowBalanceThreshold } });

export const updateWalletStatus = (walletId: number, status: string) =>
  request({ url: `${BASE}/${walletId}/status`, method: 'post', params: { status } });
