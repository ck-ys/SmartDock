// 批5: 旧 mission 类型退役, 字段 VO 本地化(口径=微核心 task_field)
interface FieldVO { fieldCode: string; fieldName: string; fieldType: string; required?: string; options?: string }

export type AiProvider = 'OPENAI' | 'DEEPSEEK' | 'QWEN' | 'ZHIPU' | 'KIMI' | string;

/** 配置用途: CHAT=AI 调用(诊断/字段/评分/规则), EMBEDDING=向量化(Phase 4 相似检索) */
export type AiUseType = 'CHAT' | 'EMBEDDING';

/** 接口格式(与后端 AiApiFormat 同值): CHAT_COMPLETIONS=/chat/completions,
 *  ANTHROPIC_MESSAGES=/v1/messages, RESPONSES=/responses */
export type AiApiFormat = 'CHAT_COMPLETIONS' | 'ANTHROPIC_MESSAGES' | 'RESPONSES' | string;

/** 接口格式字典行(GET /ai-config/api-formats) */
export interface AiApiFormatVO {
  value: AiApiFormat;
  label: string;
  path: string;
}

/** 厂商模板行(GET /ai-config/templates): 选中即带出地址/格式/候选模型/控制台入口 */
export interface AiProviderTemplateVO {
  templateId: string;
  label: string;
  provider: AiProvider;
  apiFormat: AiApiFormat;
  baseUrl: string;
  defaultModel?: string;
  consoleUrl?: string;
  models?: string[];
  note?: string;
}

export interface AiConfigVO extends BaseEntity {
  configId: number | string;
  tenantId: number | string;
  provider: AiProvider;
  providerName?: string;
  /** 配置用途 (2026-08-01 新增, 区分 AI 调用 vs 向量化) */
  useType?: AiUseType;
  baseUrl: string;
  apiKey?: string;
  apiKeyMask?: string;
  /** 接口格式(Q5)：默认 CHAT_COMPLETIONS，非该格式仅支持连通测试不进对话主链 */
  apiFormat?: AiApiFormat;
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

export interface AiConfigForm {
  configId?: number | string;
  tenantId?: number | string;
  provider?: AiProvider;
  /** 配置用途 (2026-08-01 新增) */
  useType?: AiUseType;
  baseUrl?: string;
  apiKey?: string;
  apiKeyMask?: string;
  /** 接口格式(Q5) */
  apiFormat?: AiApiFormat;
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

export interface AiConfigQuery extends PageQuery {
  tenantId?: number | string;
  provider?: AiProvider;
  useType?: AiUseType;
  enabled?: '0' | '1';
}

export interface AiProviderPresetVO {
  provider: AiProvider;
  providerName: string;
  baseUrl: string;
  modelName: string;
  /** 接口格式(Q5) */
  apiFormat?: AiApiFormat;
  /** 厂商控制台/密钥页入口(Q5) */
  consoleUrl?: string;
}

/** 批B 厂商接入(钥匙单一真源): 每租户每厂商一行, 一处改全厂生效 */
export interface AiProviderAccessVO {
  id?: number | string;
  tenantId: number | string;
  provider: AiProvider;
  providerName?: string;
  baseUrl: string;
  apiKeyMask?: string;
  consoleUrl?: string;
  /** 接口格式(Q5)：钥匙按接入行配置，模型行可覆盖 */
  apiFormat?: AiApiFormat;
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

export interface AiProviderAccessForm {
  id?: number | string;
  tenantId: number | string;
  provider: AiProvider;
  baseUrl: string;
  /** 留空/****=沿用原密钥 */
  apiKey?: string;
  /** 接口格式(Q5)，留空=CHAT_COMPLETIONS */
  apiFormat?: AiApiFormat;
  consoleUrl?: string;
  remark?: string;
  /** Q8 钥匙池：标签与启停（编辑时传） */
  label?: string;
  enabled?: string;
}

export interface AiTestResultVO {
  /** 批C C2 P2-1：批量体检结果归属行（按 configId 对位，防筛选/翻页下标错位） */
  configId?: number | string;
  success: boolean;
  message: string;
  latencyMs?: number;
}

export interface AiProbeForm {
  provider?: AiProvider;
  baseUrl?: string;
  apiKey?: string;
  /** 接口格式(Q5)：非 Chat Completions 走 1-token 连通性探测 */
  apiFormat?: AiApiFormat;
  /** 批B: 钥匙留空时按 (tenantId,provider) 从厂商接入行补钥 */
  tenantId?: string;
  modelName?: string;
  temperature?: number;
  timeoutSeconds?: number;
}

export interface AiModelVO {
  modelId: string;
  ownedBy?: string;
  /** 业务批4 Q16-A：厂商 /models 自带定价时解析的免费标记（null=响应无定价，如实不标） */
  free?: boolean | null;
}

export interface AiBalanceVO {
  available?: boolean;
  message?: string;
  balances: Array<{
    currency?: string;
    totalBalance?: string;
    grantedBalance?: string;
    toppedUpBalance?: string;
  }>;
}

export type AiBalanceMap = Record<string, AiBalanceVO>;

export interface AiFieldGenerateForm {
  missionId: number | string;
  requirement: string;
}

export interface AiFieldGenerateVO {
  fields: FieldVO[];
  warnings: string[];
}

/** AI 调用消耗台账行 */
export interface AiUsageVO {
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
export interface AiUsageSummary {
  totalCalls: number;
  successCalls: number;
  totalCost: number;
  successRate: number;
  byCaller: Record<string, number>;
  costByCaller: Record<string, number>;
  dailyCost: Record<string, number>;
}

export interface AiModelPriceVO extends BaseEntity {
  priceId: number | string;
  tenantId: string;
  provider: AiProvider;
  modelName: string;
  currency?: string;
  inputPricePerMillion?: number;
  cachedInputPricePerMillion?: number;
  outputPricePerMillion?: number;
  sourceUrl?: string;
  enabled?: '0' | '1';
  remark?: string;
  /** 价格来源(Q6)：MODELS_DEV=从 models.dev 同步，IMPORT=JSON 导入，MANUAL=手工录入 */
  priceSource?: 'MANUAL' | 'MODELS_DEV' | 'IMPORT' | string;
  /** 手工覆盖标记：'1' 时同步任务不覆盖该行 */
  manualOverride?: '0' | '1' | string;
  /** 最近一次同步时间(MODELS_DEV 来源行) */
  lastSyncedAt?: string;
}

export interface AiModelPriceForm {
  priceId?: number | string;
  tenantId?: string;
  provider?: AiProvider;
  modelName?: string;
  currency?: string;
  inputPricePerMillion?: number;
  cachedInputPricePerMillion?: number;
  outputPricePerMillion?: number;
  sourceUrl?: string;
  enabled?: '0' | '1';
  /** '1'=手工覆盖，同步不覆盖（手工新增/编辑默认置 1） */
  manualOverride?: '0' | '1' | string;
  remark?: string;
}
