import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import {
  AiConfigForm,
  AiConfigQuery,
  AiConfigVO,
  AiFieldGenerateForm,
  AiFieldGenerateVO,
  AiBalanceVO,
  AiBalanceMap,
  AiModelVO,
  AiProbeForm,
  AiProviderAccessForm,
  AiProviderAccessVO,
  AiProviderPresetVO,
  AiTestResultVO,
  AiModelPriceForm,
  AiModelPriceVO,
  AiApiFormatVO,
  AiProviderTemplateVO
} from './types';

/** 类型再导出——页面按名导入（AiProvider=厂商字典键别名） */
export type { AiConfigVO, AiProviderPresetVO, AiApiFormatVO, AiProviderTemplateVO } from './types';
export type { AiProvider } from './types';

const CONFIG_BASE = '/ai-config';

/** 能力目录匹配(批1/批5): 输入模型名自动解析思考档位/上下文窗口, 永不 404 */
export const matchAiCapability = (params: { provider: string; model: string }): AxiosPromise<any> =>
  request({ url: '/ai/capability/match', method: 'get', params });

/** 助手模型设置读(批3/批5): 助手默认指向+自选开关 */
export const getAssistantModelSetting = (): AxiosPromise<any> => request({ url: '/assistant/model-setting', method: 'get' });

/** 助手可选模型列表(批4/批5): 本租户可用 CHAT 行+单价, ★排最前 */
export const getAssistantModelOptions = (): AxiosPromise<AiConfigVO[]> => request({ url: '/assistant/model-setting/options', method: 'get' });

/** 保存助手模型设置(批3/批5): 助手默认指向+自选开关+范围; 批1015c Q1 增后台默认槽
 *  (backendDefaultConfigId: 0=显式清空回跟随接口默认, 不传=null 保持原值) */
export const saveAssistantModelSetting = (data: {
  assistantDefaultConfigId: number | string;
  allowUserSwitch?: string;
  switchScope?: string | null;
  backendDefaultConfigId?: number | string;
}) => request({ url: '/assistant/model-setting/edit', method: 'post', data });

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
}) => request({ url: '/assistant/model-setting', method: 'post', data });

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
}): AxiosPromise<any> => request({ url: '/ai/capability/override', method: 'post', data });

/** 恢复官方映射(批6/Q13): 删本租户 MANUAL 行回退 SEED */
export const resetAiCapability = (params: { provider: string; model: string }): AxiosPromise<void> =>
  request({ url: '/ai/capability/reset', method: 'post', params });

export const listAiConfig = (query?: AiConfigQuery): AxiosPromise<AiConfigVO[]> =>
  request({ url: `${CONFIG_BASE}/list`, method: 'get', params: query });

export const getAiConfig = (configId: number | string): AxiosPromise<AiConfigVO> =>
  request({ url: `${CONFIG_BASE}/${configId}`, method: 'get' });

export const listAiProviderPresets = (): AxiosPromise<AiProviderPresetVO[]> => request({ url: `${CONFIG_BASE}/presets`, method: 'get' });

/** Q5(2026-10-23) 厂商模板：选中即带出接口地址/格式/候选模型/控制台入口 */
export const listAiProviderTemplates = (): AxiosPromise<AiProviderTemplateVO[]> =>
  request({ url: `${CONFIG_BASE}/templates`, method: 'get' });

/** Q5(2026-10-23) 接口格式字典（含官方路径，用于表单只读预览） */
export const listAiApiFormats = (): AxiosPromise<AiApiFormatVO[]> =>
  request({ url: `${CONFIG_BASE}/api-formats`, method: 'get' });

/** AI 模型厂商字典(从 ai_provider 字典加载, 供厂商下拉) */
export const listAiProviderDicts = (): AxiosPromise<any[]> => request({ url: `${CONFIG_BASE}/provider-dicts`, method: 'get' });

/** 查询当前租户+厂商+模型的有效价格(切换模型时回填) */
export const getAiEffectivePrice = (params: { tenantId: string; provider: string; modelName: string }): AxiosPromise<any> =>
  request({ url: `/ai-price/effective`, method: 'get', params });

/** 保存/更新模型价格(有则更新无则新增) */
export const upsertAiModelPrice = (data: AiModelPriceForm): AxiosPromise<any> => request({ url: `/ai-price/upsert`, method: 'post', data });

/** 智能识别计费参数(混合: 正则+AI, 从官方文档 URL 提取) */
export const detectAiPrice = (data: { provider: string; modelName: string; sourceUrl: string }): AxiosPromise<any> =>
  request({ url: `${CONFIG_BASE}/detect-price`, method: 'post', data });

export const addAiConfig = (data: AiConfigForm) => request({ url: CONFIG_BASE, method: 'post', data });

export const updateAiConfig = (data: AiConfigForm) => request({ url: `${CONFIG_BASE}/edit`, method: 'post', data });

export const delAiConfig = (configIds: number | string | Array<number | string>) =>
  request({ url: `${CONFIG_BASE}/${configIds}`, method: 'post' });

export const testAiConfig = (configId: number | string): AxiosPromise<AiTestResultVO> =>
  request({ url: `${CONFIG_BASE}/${configId}/test`, method: 'post' });

export const probeAiModels = (data: AiProbeForm): AxiosPromise<AiModelVO[]> =>
  request({ url: `${CONFIG_BASE}/probe/models`, method: 'post', data });

export const probeAiBalance = (data: AiProbeForm): AxiosPromise<AiBalanceVO> =>
  request({ url: `${CONFIG_BASE}/probe/balance`, method: 'post', data });

export const probeAiSpeed = (data: AiProbeForm): AxiosPromise<AiTestResultVO> =>
  request({ url: `${CONFIG_BASE}/probe/speed`, method: 'post', data });

export const probeSavedAiModels = (configId: number | string, data?: AiProbeForm): AxiosPromise<AiModelVO[]> =>
  request({ url: `${CONFIG_BASE}/${configId}/probe/models`, method: 'post', data });

export const probeSavedAiBalance = (configId: number | string, data?: AiProbeForm): AxiosPromise<AiBalanceVO> =>
  request({ url: `${CONFIG_BASE}/${configId}/probe/balance`, method: 'post', data });

export const probeSavedAiSpeed = (configId: number | string, data?: AiProbeForm): AxiosPromise<AiTestResultVO> =>
  request({ url: `${CONFIG_BASE}/${configId}/probe/speed`, method: 'post', data });

/** 批量查余额(业务批3-②): silent=true 走 request 静默通道(轮询场景不弹错不弹重登框);
 *  repeatSubmit:false——只读轮询 POST 免重复提交拦截(与用户手动查询 500ms 内同刻撞车会被误拦) */
export const getAiBalances = (configIds: Array<number | string>, silent = false): AxiosPromise<AiBalanceMap> =>
  request({
    url: `${CONFIG_BASE}/balances`,
    method: 'post',
    data: configIds,
    headers: { repeatSubmit: false },
    ...(silent ? { custom: { silent: true } } : {})
  });

/** CC Switch: 一键切换活跃配置(同租户仅一条 enabled='1') */
export const activateAiConfig = (configId: number | string) => request({ url: `${CONFIG_BASE}/${configId}/activate`, method: 'post' });

/** 批B 厂商接入查询: 某租户某厂商的钥匙/接口地址状态(null=从未配置, 表单切「首次配置需填Key」形态) */
export const getAiProviderAccess = (params: { tenantId: string; provider: string }): AxiosPromise<AiProviderAccessVO | null> =>
  request({ url: `${CONFIG_BASE}/access`, method: 'get', params });

/** 批B 厂商接入保存(钥匙单一真源, 一处改全厂生效); apiKey 留空=沿用原密钥。
 *  2026-10-16 PUT→POST：客户网络 WAF 只放行 GET/POST，PUT 一发即"后端接口连接异常"（铁律#21） */
export const updateAiProviderAccess = (data: AiProviderAccessForm): AxiosPromise<AiProviderAccessVO> =>
  request({ url: `${CONFIG_BASE}/access`, method: 'post', data });

/** Q8 补遗(2026-10-15)：显式新增一把钥匙——PUT 无 id=编辑主钥匙的兼容语义插不进第二把 */
export const addAiProviderAccessKey = (data: AiProviderAccessForm): AxiosPromise<AiProviderAccessVO> =>
  request({ url: `${CONFIG_BASE}/access/add`, method: 'post', data });

// ===== AI 缺口批 Q8(2026-10-14)：厂商钥匙池（多把轮询+余额快照）=====
/** 钥匙列表（掩码+启停+主钥匙标记+余额快照，id 升序） */
export const listAiProviderAccessKeys = (params: { tenantId: string; provider: string }): AxiosPromise<AiProviderAccessVO[]> =>
  request({ url: `${CONFIG_BASE}/access/list`, method: 'get', params });

/** 钥匙启停（不能停最后一把启用中的钥匙） */
export const changeAiKeyStatus = (id: number | string, enabled: '0' | '1') =>
  request({ url: `${CONFIG_BASE}/access/change-status`, method: 'post', params: { id, enabled } });

/** 删除钥匙（软删，不能删最后一把启用中的钥匙） */
export const deleteAiKey = (id: number | string) => request({ url: `${CONFIG_BASE}/access/delete`, method: 'post', params: { id } });

/** 单钥匙余额刷新（实查厂商端点落快照） */
export const refreshAiKeyBalance = (id: number | string): AxiosPromise<AiProviderAccessVO> =>
  request({ url: `${CONFIG_BASE}/access/${id}/balance`, method: 'post' });

/** 整厂钥匙余额批量刷新（UNSUPPORTED 跳过零外呼） */
export const refreshAllAiKeyBalances = (params: { tenantId: string; provider: string }): AxiosPromise<AiProviderAccessVO[]> =>
  request({ url: `${CONFIG_BASE}/access/balance-all`, method: 'post', params });

/** CC Switch: 批量健康检查(逐个 testConnection)；业务批4 Q17-A：可带 configIds 子集，空=全部 */
export const healthCheckAiConfigs = (configIds?: (number | string)[]): AxiosPromise<AiTestResultVO[]> =>
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

export const generateAiFields = (data: AiFieldGenerateForm): AxiosPromise<AiFieldGenerateVO> =>
  request({ url: '/ai-field/generate', method: 'post', data });

/** AI 用量台账分页查询 */
export const listAiUsage = (params?: any): AxiosPromise<any> => request({ url: '/ai-usage/list', method: 'get', params });

/** AI 用量统计 */
export const getAiUsageSummary = (): AxiosPromise<any> => request({ url: '/ai-usage/summary', method: 'get' });

/** AI 缺口批 Q5/Q6：用量分析（趋势/模型占比/Top10 用户含部门/部门占比/汇总） */
export const getAiUsageAnalysis = (params?: { beginDate?: string; endDate?: string }): AxiosPromise<any> =>
  request({ url: '/ai-usage/analysis', method: 'get', params });

const PRICE_BASE = '/ai-model-price';

export const listAiModelPrice = (query?: any): AxiosPromise<AiModelPriceVO[]> =>
  request({ url: `${PRICE_BASE}/list`, method: 'get', params: query });

export const addAiModelPrice = (data: AiModelPriceForm) => request({ url: PRICE_BASE, method: 'post', data });

export const updateAiModelPrice = (data: AiModelPriceForm) => request({ url: `${PRICE_BASE}/edit`, method: 'post', data });

export const delAiModelPrice = (priceIds: number | string | Array<number | string>) =>
  request({ url: `${PRICE_BASE}/${priceIds}`, method: 'post' });

/** Q6(2026-10-23) 从 models.dev 同步价格：手工覆盖行(manualOverride='1')跳过不覆盖 */
export const syncAiModelPricesFromModelsDev = (): AxiosPromise<{
  source: string;
  inserted: number;
  updated: number;
  skipped: number;
  total: number;
}> => request({ url: `${PRICE_BASE}/sync-models-dev`, method: 'post' });

/** Q6(2026-10-23) JSON 导入价格：{ prices: [{provider,modelName,input,output,currency,manualOverride}] } */
export const importAiModelPrices = (payload: { prices: Array<Record<string, any>> }): AxiosPromise<{
  inserted: number;
  updated: number;
  skipped: number;
  total: number;
}> => request({ url: `${PRICE_BASE}/import`, method: 'post', data: payload });

// ===== 额度三级管控批（Q16=1）：联网搜索服务（余额 + 资源包估算）=====
/** 搜索服务列表（只回掩码，不回明文 key） */
export const listAiSearchServices = (): AxiosPromise<Array<Record<string, any>>> =>
  request({ url: '/ai-search/list', method: 'get' });

/** 新增/更新（按租户+厂商幂等；apiKey 留空=不改） */
export const saveAiSearchService = (data: Record<string, any>): AxiosPromise<Record<string, any>> =>
  request({ url: '/ai-search/save', method: 'post', data });

/** 启停 */
export const changeAiSearchStatus = (id: number | string, enabled: '0' | '1') =>
  request({ url: `/ai-search/${id}/status`, method: 'post', params: { enabled } });

/** 删除（逻辑删） */
export const removeAiSearchService = (id: number | string) => request({ url: `/ai-search/${id}`, method: 'delete' });

/** 查一次余额（查不到回 status=UNSUPPORTED/ERROR，不拿旧值冒充） */
export const refreshAiSearchBalance = (id: number | string): AxiosPromise<Record<string, any>> =>
  request({ url: `/ai-search/${id}/refresh-balance`, method: 'post' });
