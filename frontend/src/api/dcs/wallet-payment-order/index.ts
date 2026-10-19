import request from '@/utils/request';
import { AxiosPromise } from 'axios';

const BASE = '/dcs/wallet-payment-order';

export interface WalletPaymentOrderVO {
  orderId: number;
  tenantId: string;
  walletId: number;
  orderNo: string;
  channel: string;
  amount: number;
  currency: string;
  status: string;
  payTime: string;
  remark: string;
  createTime: string;
}

export const listWalletPaymentOrder = (params?: any): AxiosPromise<WalletPaymentOrderVO[]> => request({ url: `${BASE}/list`, method: 'get', params });

export const createWalletPaymentOrder = (data: Partial<WalletPaymentOrderVO>) => request({ url: `${BASE}/create`, method: 'post', data });

export const closeWalletPaymentOrder = (orderId: number) => request({ url: `${BASE}/${orderId}/close`, method: 'post' });
