// 批5: 旧 mission 类型退役, 字段 VO 本地化(口径=微核心 dcs_task_field)
interface DcsFieldVO { fieldCode: string; fieldName: string; fieldType: string; required?: string; options?: string }

export type DcsAiProvider = 'OPENAI' | 'DEEPSEEK' | 'QWEN' | 'ZHIPU' | 'KIMI' | string;

/** 配置用途: CHAT=AI 调用(诊断/字段/评分/规则), EMBEDDING=向量化(Phase 4 相似检索) */
export type DcsAiUseType = 'CHAT' | 'EMBEDDING';

export interface DcsAiConfigVO extends BaseEntity {
  configId: number | string;
  tenantId: number | string;
  provider: DcsAiProvider;
  providerName?: string;
  /** 配置用途 (2026-08-01 新增, 区分 AI 调用 vs 向量化) */
  useType?: DcsAiUseType;
  baseUrl: string;
  apiKey?: string;
  apiKeyMask?: string;
  /** 批B 厂商接入标记：true=钥匙由接入层统一提供（列表接入✓/表单免填 Key 用） */
  accessConfigured?: boolean;
  modelName: string;
  contextWindow?: number;
  /** 单次回答最大输出 tokens（批C C1；空=模型/框架默认） */
  maxTokens?: number;
  temperature?: number;
  timeoutSeconds?: number;
  enabled?: '0' | '1';
  /** 默认星标(批3):'1'=该用途默认★; undefined/null=普通可用行 */
  isDefault?: '0' | '1' | null;
  /** 输入单价(元/百万 tokens, 价格目录透出, 批4) */
  inputPricePerMillion?: number;
  /** 输出单价(元/百万 tokens, 价格目录透出, 批4) */
  outputPricePerMillion?: number;
  /** 价格币种(价格目录透出, 批4) */
  currency?: string;
  /** 每次 AI 调用单价(元) */
  unitPricePerCall?: number;
  balanceQueryEnabled?: '0' | '1';
  billingMode?: 'OFFICIAL_TOKEN' | 'FIXED_PER_CALL' | 'OFFICIAL_PLUS_FEE';
  serviceFeeRate?: number;
  /** 业务反馈批1015b Q13：积分消耗倍率（NULL/0 视同 1；订阅租户每回合扣分=基础分×此倍率） */
  creditMultiplier?: number;
  /** 思考模式强度(空=关闭/low/medium/high/max) */
  reasoningEffort?: string;
  remark?: string;
}

export interface DcsAiConfigForm {
  configId?: number | string;
  tenantId?: number | string;
  provider?: DcsAiProvider;
  /** 配置用途 (2026-08-01 新增) */
  useType?: DcsAiUseType;
  baseUrl?: string;
  apiKey?: string;
  apiKeyMask?: string;
  modelName?: string;
  contextWindow?: number;
  /** 单次回答最大输出 tokens（批C C1） */
  maxTokens?: number;
  /** 批C 回修：显式清空信号（编辑时空字段=true 才落 NULL） */
  clearMaxTokens?: boolean;
  temperature?: number;
  timeoutSeconds?: number;
  enabled?: '0' | '1';
  /** 每次 AI 调用单价(元) */
  unitPricePerCall?: number;
  balanceQueryEnabled?: '0' | '1';
  billingMode?: 'OFFICIAL_TOKEN' | 'FIXED_PER_CALL' | 'OFFICIAL_PLUS_FEE';
  serviceFeeRate?: number;
  /** 业务反馈批1015b Q13：积分消耗倍率（NULL/0 视同 1；订阅租户每回合扣分=基础分×此倍率） */
  creditMultiplier?: number;
  /** 思考模式强度(空=关闭/low/medium/high/max) */
  reasoningEffort?: string;
  remark?: string;
}

export interface DcsAiConfigQuery extends PageQuery {
  tenantId?: number | string;
  provider?: DcsAiProvider;
  useType?: DcsAiUseType;
  enabled?: '0' | '1';
}

export interface DcsAiProviderPresetVO {
  provider: DcsAiProvider;
  providerName: string;
  baseUrl: string;
  modelName: string;
}

/** 批B 厂商接入(钥匙单一真源): 每租户每厂商一行, 一处改全厂生效 */
export interface DcsAiProviderAccessVO {
  id?: number | string;
  tenantId: number | string;
  provider: DcsAiProvider;
  providerName?: string;
  baseUrl: string;
  apiKeyMask?: string;
  consoleUrl?: string;
  remark?: string;
  /** 本次钥匙变更已生效的模型行数(仅保存接口回填) */
  affectedModelCount?: number;
  updateTime?: string;
  /** AI 缺口批 Q8(2026-10-14)：钥匙标签/启停/主钥匙标记 */
  label?: string;
  enabled?: string;
  primary?: boolean;
  /** 厂商侧账户余额快照（查得非算得；null=无快照或不可量化如"不限"） */
  balanceAmount?: number | string | null;
  balanceCurrency?: string;
  balanceCheckedAt?: string;
  /** OK/UNSUPPORTED(厂商不支持)/FAILED(最近失败保留旧值)/null=未探测 */
  balanceStatus?: string | null;
  createTime?: string;
}

export interface DcsAiProviderAccessForm {
  id?: number | string;
  tenantId: number | string;
  provider: DcsAiProvider;
  baseUrl: string;
  /** 留空/****=沿用原密钥 */
  apiKey?: string;
  consoleUrl?: string;
  remark?: string;
  /** Q8 钥匙池：标签与启停（编辑时传） */
  label?: string;
  enabled?: string;
}

export interface DcsAiTestResultVO {
  /** 批C C2 P2-1：批量体检结果归属行（按 configId 对位，防筛选/翻页下标错位） */
  configId?: number | string;
  success: boolean;
  message: string;
  latencyMs?: number;
}

export interface DcsAiProbeForm {
  provider?: DcsAiProvider;
  baseUrl?: string;
  apiKey?: string;
  /** 批B: 钥匙留空时按 (tenantId,provider) 从厂商接入行补钥 */
  tenantId?: string;
  modelName?: string;
  temperature?: number;
  timeoutSeconds?: number;
}

export interface DcsAiModelVO {
  modelId: string;
  ownedBy?: string;
  /** 业务批4 Q16-A：厂商 /models 自带定价时解析的免费标记（null=响应无定价，如实不标） */
  free?: boolean | null;
}

export interface DcsAiBalanceVO {
  available?: boolean;
  message?: string;
  balances: Array<{
    currency?: string;
    totalBalance?: string;
    grantedBalance?: string;
    toppedUpBalance?: string;
  }>;
}

export type DcsAiBalanceMap = Record<string, DcsAiBalanceVO>;

export interface DcsAiFieldGenerateForm {
  missionId: number | string;
  requirement: string;
}

export interface DcsAiFieldGenerateVO {
  fields: DcsFieldVO[];
  warnings: string[];
}

/** AI 调用消耗台账行 */
export interface DcsAiUsageVO {
  usageId: number | string;
  tenantId: string;
  configId: number | string;
  provider?: string;
  modelName?: string;
  caller?: string;
  scene?: string;
  operatorId?: number | string;
  latencyMs?: number;
  costAmount?: number;
  baseCostAmount?: number;
  serviceFeeRate?: number;
  serviceFeeAmount?: number;
  currency?: string;
  billingMode?: 'OFFICIAL_TOKEN' | 'FIXED_PER_CALL' | 'OFFICIAL_PLUS_FEE';
  pricingSnapshot?: string;
  unitPrice?: number;
  promptTokens?: number;
  cachedPromptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  success?: '0' | '1';
  errorMsg?: string;
  createTime?: string;
}

/** AI 消耗统计 */
export interface DcsAiUsageSummary {
  totalCalls: number;
  successCalls: number;
  totalCost: number;
  successRate: number;
  byCaller: Record<string, number>;
  costByCaller: Record<string, number>;
  dailyCost: Record<string, number>;
}

export interface DcsAiModelPriceVO extends BaseEntity {
  priceId: number | string;
  tenantId: string;
  provider: DcsAiProvider;
  modelName: string;
  currency?: string;
  inputPricePerMillion?: number;
  cachedInputPricePerMillion?: number;
  outputPricePerMillion?: number;
  sourceUrl?: string;
  enabled?: '0' | '1';
  remark?: string;
}

export interface DcsAiModelPriceForm {
  priceId?: number | string;
  tenantId?: string;
  provider?: DcsAiProvider;
  modelName?: string;
  currency?: string;
  inputPricePerMillion?: number;
  cachedInputPricePerMillion?: number;
  outputPricePerMillion?: number;
  sourceUrl?: string;
  enabled?: '0' | '1';
  remark?: string;
}
