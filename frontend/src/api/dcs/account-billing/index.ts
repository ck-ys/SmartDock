import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/**
 * 账户与账单页（菜单IA重组批2，2026-10-16）：三页签聚合入口。
 * 旧三页 api（tenant-wallet / tenant-wallet-ledger / wallet-payment-order）保持原位不动，
 * 这里 re-export 提供统一导入面 + 新增钱包全量汇总接口（KPI 全量口径）。
 */
export * from '@/api/dcs/tenant-wallet';
export * from '@/api/dcs/tenant-wallet-ledger';
export * from '@/api/dcs/wallet-payment-order';

const BASE = '/dcs/tenant-wallet';

/** 钱包全量汇总（不限分页）：账户总览页签 KPI 数据源 */
export interface WalletSummaryVO {
  walletCount: number;
  totalBalance: number;
  totalFrozen: number;
  belowThreshold: number;
  abnormal: number;
}

export const getWalletSummary = (): AxiosPromise<WalletSummaryVO> => request({ url: `${BASE}/summary`, method: 'get' });
