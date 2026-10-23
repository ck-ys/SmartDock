import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import {
  DcsAiConfigForm,
  DcsAiConfigQuery,
  DcsAiConfigVO,
  DcsAiFieldGenerateForm,
  DcsAiFieldGenerateVO,
  DcsAiBalanceVO,
  DcsAiBalanceMap,
  DcsAiModelVO,
  DcsAiProbeForm,
  DcsAiProviderAccessForm,
  DcsAiProviderAccessVO,
  DcsAiProviderPresetVO,
  DcsAiTestResultVO,
  DcsAiModelPriceForm,
  DcsAiModelPriceVO,
  DcsAiApiFormatVO,
  DcsAiProviderTemplateVO
} from './types';

/** 类型再导出——页面按名导入（DcsAiProvider=厂商字典键别名） */
export type { DcsAiConfigVO, DcsAiProviderPresetVO, DcsAiApiFormatVO, DcsAiProviderTemplateVO } from './types';
export type { DcsAiProvider } from './types';

const CONFIG_BASE = '/dcs/ai-config';

/** 能力目录匹配(批1/批5): 输入模型名自动解析思考档位/上下文窗口, 永不 404 */
export const matchAiCapability = (params: { provider: string; model: string }): AxiosPromise<any> =>
  request({ url: '/dcs/ai/capability/match', method: 'get', params });

/** 助手模型设置读(批3/批5): 助手默认指向+自选开关 */
export const getAssistantModelSetting = (): AxiosPromise<any> => request({ url: '/dcs/assistant/model-setting', method: 'get' });

/** 助手可选模型列表(批4/批5): 本租户可用 CHAT 行+单价, ★排最前 */
export const getAssistantModelOptions = (): AxiosPromise<DcsAiConfigVO[]> => request({ url: '/dcs/assistant/model-setting/options', method: 'get' });

/** 保存助手模型设置(批3/批5): 助手默认指向+自选开关+范围; 批1015c Q1 增后台默认槽
 *  (backendDefaultConfigId: 0=显式清空回跟随接口默认, 不传=null 保持原值) */
export const saveAssistantModelSetting = (data: {
  assistantDefaultConfigId: number | string;
  allowUserSwitch?: string;
  switchScope?: string | null;
  backendDefaultConfigId?: number | string;
}) => request({ url: '/dcs/assistant/model-setting/edit', method: 'post', data });

/** 业务反馈批2 Q4/Q6(2026-10-16)画像设置弹窗的保存通道：后端同方法挂 POST+PUT 双通道，
 *  铁律#21(客户网络 WAF 只放行 GET/POST)新前端一律走 POST。参数增 backendThinkingTier
 *  (FAST/BALANCED/DEEP；''=显式清空)+backendMaxTokens(0=显式不限制)；assistantDefaultConfigId
 *  后端非空校验——必须先读当前值原样回传。 */
export const saveAssistantModelSettingPost = (data: {
  assistantDefaultConfigId: number | string;
  backendDefaultConfigId?: number | string;
  backendThinkingTier?: string;
  backendMaxTokens?: number;
  allowUserSwitch?: string;
  switchScope?: string | null;
}) => request({ url: '/dcs/assistant/model-setting', method: 'post', data });

/** 能力覆盖(批6/Q13): 管理端✎弹窗保存 MANUAL 行, 只影响本租户, 重复保存=更新
 *  业务批3-①: 补 maxOutputTokens(批C 漏改的类型债)与 supports_* 三列(不提交=继承官方目录) */
export const overrideAiCapability = (data: {
  provider: string;
  modelName: string;
  tierKind: 'TIERS' | 'SWITCH' | 'NONE';
  tierValues?: string[];
  semanticMap?: Record<string, string>;
  contextWindow?: number | null;
  maxOutputTokens?: number | null;
  supportsImage?: boolean;
  supportsVideo?: boolean;
  supportsAudio?: boolean;
}): AxiosPromise<any> => request({ url: '/dcs/ai/capability/override', method: 'post', data });

/** 恢复官方映射(批6/Q13): 删本租户 MANUAL 行回退 SEED */
export const resetAiCapability = (params: { provider: string; model: string }): AxiosPromise<void> =>
  request({ url: '/dcs/ai/capability/reset', method: 'post', params });

export const listDcsAiConfig = (query?: DcsAiConfigQuery): AxiosPromise<DcsAiConfigVO[]> =>
  request({ url: `${CONFIG_BASE}/list`, method: 'get', params: query });

export const getDcsAiConfig = (configId: number | string): AxiosPromise<DcsAiConfigVO> =>
  request({ url: `${CONFIG_BASE}/${configId}`, method: 'get' });

export const listDcsAiProviderPresets = (): AxiosPromise<DcsAiProviderPresetVO[]> => request({ url: `${CONFIG_BASE}/presets`, method: 'get' });

/** Q5(2026-10-23) 厂商模板：选中即带出接口地址/格式/候选模型/控制台入口 */
export const listDcsAiProviderTemplates = (): AxiosPromise<DcsAiProviderTemplateVO[]> =>
  request({ url: `${CONFIG_BASE}/templates`, method: 'get' });

/** Q5(2026-10-23) 接口格式字典（含官方路径，用于表单只读预览） */
export const listDcsAiApiFormats = (): AxiosPromise<DcsAiApiFormatVO[]> =>
  request({ url: `${CONFIG_BASE}/api-formats`, method: 'get' });

/** AI 模型厂商字典(从 dcs_ai_provider 字典加载, 供厂商下拉) */
export const listDcsAiProviderDicts = (): AxiosPromise<any[]> => request({ url: `${CONFIG_BASE}/provider-dicts`, method: 'get' });

/** 查询当前租户+厂商+模型的有效价格(切换模型时回填) */
export const getDcsAiEffectivePrice = (params: { tenantId: string; provider: string; modelName: string }): AxiosPromise<any> =>
  request({ url: `/dcs/ai-price/effective`, method: 'get', params });

/** 保存/更新模型价格(有则更新无则新增) */
export const upsertDcsAiModelPrice = (data: DcsAiModelPriceForm): AxiosPromise<any> => request({ url: `/dcs/ai-price/upsert`, method: 'post', data });

/** 智能识别计费参数(混合: 正则+AI, 从官方文档 URL 提取) */
export const detectDcsAiPrice = (data: { provider: string; modelName: string; sourceUrl: string }): AxiosPromise<any> =>
  request({ url: `${CONFIG_BASE}/detect-price`, method: 'post', data });

export const addDcsAiConfig = (data: DcsAiConfigForm) => request({ url: CONFIG_BASE, method: 'post', data });

export const updateDcsAiConfig = (data: DcsAiConfigForm) => request({ url: `${CONFIG_BASE}/edit`, method: 'post', data });

export const delDcsAiConfig = (configIds: number | string | Array<number | string>) =>
  request({ url: `${CONFIG_BASE}/${configIds}`, method: 'post' });

export const testDcsAiConfig = (configId: number | string): AxiosPromise<DcsAiTestResultVO> =>
  request({ url: `${CONFIG_BASE}/${configId}/test`, method: 'post' });

export const probeDcsAiModels = (data: DcsAiProbeForm): AxiosPromise<DcsAiModelVO[]> =>
  request({ url: `${CONFIG_BASE}/probe/models`, method: 'post', data });

export const probeDcsAiBalance = (data: DcsAiProbeForm): AxiosPromise<DcsAiBalanceVO> =>
  request({ url: `${CONFIG_BASE}/probe/balance`, method: 'post', data });

export const probeDcsAiSpeed = (data: DcsAiProbeForm): AxiosPromise<DcsAiTestResultVO> =>
  request({ url: `${CONFIG_BASE}/probe/speed`, method: 'post', data });

export const probeSavedDcsAiModels = (configId: number | string, data?: DcsAiProbeForm): AxiosPromise<DcsAiModelVO[]> =>
  request({ url: `${CONFIG_BASE}/${configId}/probe/models`, method: 'post', data });

export const probeSavedDcsAiBalance = (configId: number | string, data?: DcsAiProbeForm): AxiosPromise<DcsAiBalanceVO> =>
  request({ url: `${CONFIG_BASE}/${configId}/probe/balance`, method: 'post', data });

export const probeSavedDcsAiSpeed = (configId: number | string, data?: DcsAiProbeForm): AxiosPromise<DcsAiTestResultVO> =>
  request({ url: `${CONFIG_BASE}/${configId}/probe/speed`, method: 'post', data });

/** 批量查余额(业务批3-②): silent=true 走 request 静默通道(轮询场景不弹错不弹重登框);
 *  repeatSubmit:false——只读轮询 POST 免重复提交拦截(与用户手动查询 500ms 内同刻撞车会被误拦) */
export const getDcsAiBalances = (configIds: Array<number | string>, silent = false): AxiosPromise<DcsAiBalanceMap> =>
  request({
    url: `${CONFIG_BASE}/balances`,
    method: 'post',
    data: configIds,
    headers: { repeatSubmit: false },
    ...(silent ? { custom: { silent: true } } : {})
  });

/** CC Switch: 一键切换活跃配置(同租户仅一条 enabled='1') */
export const activateDcsAiConfig = (configId: number | string) => request({ url: `${CONFIG_BASE}/${configId}/activate`, method: 'post' });

/** 批B 厂商接入查询: 某租户某厂商的钥匙/接口地址状态(null=从未配置, 表单切「首次配置需填Key」形态) */
export const getDcsAiProviderAccess = (params: { tenantId: string; provider: string }): AxiosPromise<DcsAiProviderAccessVO | null> =>
  request({ url: `${CONFIG_BASE}/access`, method: 'get', params });

/** 批B 厂商接入保存(钥匙单一真源, 一处改全厂生效); apiKey 留空=沿用原密钥。
 *  2026-10-16 PUT→POST：客户网络 WAF 只放行 GET/POST，PUT 一发即"后端接口连接异常"（铁律#21） */
export const updateDcsAiProviderAccess = (data: DcsAiProviderAccessForm): AxiosPromise<DcsAiProviderAccessVO> =>
  request({ url: `${CONFIG_BASE}/access`, method: 'post', data });

/** Q8 补遗(2026-10-15)：显式新增一把钥匙——PUT 无 id=编辑主钥匙的兼容语义插不进第二把 */
export const addDcsAiProviderAccessKey = (data: DcsAiProviderAccessForm): AxiosPromise<DcsAiProviderAccessVO> =>
  request({ url: `${CONFIG_BASE}/access/add`, method: 'post', data });

// ===== AI 缺口批 Q8(2026-10-14)：厂商钥匙池（多把轮询+余额快照）=====
/** 钥匙列表（掩码+启停+主钥匙标记+余额快照，id 升序） */
export const listDcsAiProviderAccessKeys = (params: { tenantId: string; provider: string }): AxiosPromise<DcsAiProviderAccessVO[]> =>
  request({ url: `${CONFIG_BASE}/access/list`, method: 'get', params });

/** 钥匙启停（不能停最后一把启用中的钥匙） */
export const changeDcsAiKeyStatus = (id: number | string, enabled: '0' | '1') =>
  request({ url: `${CONFIG_BASE}/access/change-status`, method: 'post', params: { id, enabled } });

/** 删除钥匙（软删，不能删最后一把启用中的钥匙） */
export const deleteDcsAiKey = (id: number | string) => request({ url: `${CONFIG_BASE}/access/delete`, method: 'post', params: { id } });

/** 单钥匙余额刷新（实查厂商端点落快照） */
export const refreshDcsAiKeyBalance = (id: number | string): AxiosPromise<DcsAiProviderAccessVO> =>
  request({ url: `${CONFIG_BASE}/access/${id}/balance`, method: 'post' });

/** 整厂钥匙余额批量刷新（UNSUPPORTED 跳过零外呼） */
export const refreshAllDcsAiKeyBalances = (params: { tenantId: string; provider: string }): AxiosPromise<DcsAiProviderAccessVO[]> =>
  request({ url: `${CONFIG_BASE}/access/balance-all`, method: 'post', params });

/** CC Switch: 批量健康检查(逐个 testConnection)；业务批4 Q17-A：可带 configIds 子集，空=全部 */
export const healthCheckDcsAiConfigs = (configIds?: (number | string)[]): AxiosPromise<DcsAiTestResultVO[]> =>
  request({ url: `${CONFIG_BASE}/health-check`, method: 'post', data: configIds ?? [] });

/** AI 缺口批 Q9：本租户各配置最新巡检健康行（04:00 Job 落库，徽标只读） */
export interface AiModelHealthRow {
  configId: string | number;
  status: 'OK' | 'FAIL';
  latencyMs?: number | null;
  detail?: string;
  failStreak?: number;
  checkTime?: string;
}

export const getAiModelHealthLatest = (): AxiosPromise<Record<string, AiModelHealthRow>> =>
  request({ url: `${CONFIG_BASE}/health/latest`, method: 'get' });

export const generateDcsAiFields = (data: DcsAiFieldGenerateForm): AxiosPromise<DcsAiFieldGenerateVO> =>
  request({ url: '/dcs/ai-field/generate', method: 'post', data });

/** AI 用量台账分页查询 */
export const listDcsAiUsage = (params?: any): AxiosPromise<any> => request({ url: '/dcs/ai-usage/list', method: 'get', params });

/** AI 用量统计 */
export const getDcsAiUsageSummary = (): AxiosPromise<any> => request({ url: '/dcs/ai-usage/summary', method: 'get' });

/** AI 缺口批 Q5/Q6：用量分析（趋势/模型占比/Top10 用户含部门/部门占比/汇总） */
export const getDcsAiUsageAnalysis = (params?: { beginDate?: string; endDate?: string }): AxiosPromise<any> =>
  request({ url: '/dcs/ai-usage/analysis', method: 'get', params });

const PRICE_BASE = '/dcs/ai-model-price';

export const listDcsAiModelPrice = (query?: any): AxiosPromise<DcsAiModelPriceVO[]> =>
  request({ url: `${PRICE_BASE}/list`, method: 'get', params: query });

export const addDcsAiModelPrice = (data: DcsAiModelPriceForm) => request({ url: PRICE_BASE, method: 'post', data });

export const updateDcsAiModelPrice = (data: DcsAiModelPriceForm) => request({ url: `${PRICE_BASE}/edit`, method: 'post', data });

export const delDcsAiModelPrice = (priceIds: number | string | Array<number | string>) =>
  request({ url: `${PRICE_BASE}/${priceIds}`, method: 'post' });

/** Q6(2026-10-23) 从 models.dev 同步价格：手工覆盖行(manualOverride='1')跳过不覆盖 */
export const syncDcsAiModelPricesFromModelsDev = (): AxiosPromise<{
  source: string;
  inserted: number;
  updated: number;
  skipped: number;
  total: number;
}> => request({ url: `${PRICE_BASE}/sync-models-dev`, method: 'post' });

/** Q6(2026-10-23) JSON 导入价格：{ prices: [{provider,modelName,input,output,currency,manualOverride}] } */
export const importDcsAiModelPrices = (payload: { prices: Array<Record<string, any>> }): AxiosPromise<{
  inserted: number;
  updated: number;
  skipped: number;
  total: number;
}> => request({ url: `${PRICE_BASE}/import`, method: 'post', data: payload });
