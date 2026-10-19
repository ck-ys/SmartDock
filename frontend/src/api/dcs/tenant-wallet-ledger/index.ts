import request from '@/utils/request';
import { AxiosPromise } from 'axios';

const BASE = '/dcs/tenant-wallet-ledger';

export interface WalletLedgerVO {
  ledgerId: number;
  tenantId: string;
  walletId: number;
  bizType: string;
  serviceCode: string;
  /** 服务码中文标签（后端 AiServiceCodeLabels 统一下发；老数据可能缺失，前端常量兜底） */
  serviceCodeLabel?: string;
  direction: string;
  amount: number;
  currency: string;
  balanceBefore: number;
  balanceAfter: number;
  idempotencyKey: string;
  extraJson: string;
  createTime: string;
}

export const listWalletLedger = (params?: any): AxiosPromise<WalletLedgerVO[]> => request({ url: `${BASE}/list`, method: 'get', params });
