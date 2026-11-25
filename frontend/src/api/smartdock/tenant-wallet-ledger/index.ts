import request from '@/utils/request';
import { AxiosPromise } from 'axios';

const BASE = '/tenant-wallet-ledger';

export interface WalletLedgerVO {
  ledgerId: number;
  tenantId: string;
  walletId: number;
  bizType: string;
  serviceCode: string;
  /** 服务码中文标签（后端 AiServiceCodeLabels 统一下发；老数据可能缺失，前端常量兜底） */
  serviceCodeLabel?: string;
  /**
   * 流水摘要（后端算）：优先 extra_json.reason（操作人填的原因），无则回退服务码中文标签。
   * 手动充值/扣减的 serviceCode 为空，只有这个字段能给出人话描述。
   */
  summary?: string;
  direction: string;
  amount: number;
  currency: string;
  balanceBefore: number;
  balanceAfter: number;
  idempotencyKey: string;
  extraJson: string;
  createTime: string;
}

/** 按当前筛选条件的合计（2026-11-25 钱包流水批 Q5：合计跟筛选走，不再跟当前页走） */
export interface WalletLedgerSummaryVO {
  totalIn: number;
  totalOut: number;
  net: number;
  count: number;
}

export const listWalletLedger = (params?: any): AxiosPromise<WalletLedgerVO[]> => request({ url: `${BASE}/list`, method: 'get', params });

/** 合计与列表同口径（同一组筛选、同一套租户收窄、同一个时间半开区间） */
export const getWalletLedgerSummary = (params?: any): AxiosPromise<WalletLedgerSummaryVO> =>
  request({ url: `${BASE}/summary`, method: 'get', params });
