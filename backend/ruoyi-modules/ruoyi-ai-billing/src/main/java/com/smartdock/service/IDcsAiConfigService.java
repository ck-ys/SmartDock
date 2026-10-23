package com.smartdock.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.bo.DcsAiConfigBo;
import com.smartdock.domain.bo.DcsAiProbeBo;
import com.smartdock.domain.vo.DcsAiBalanceVo;
import com.smartdock.domain.vo.DcsAiConfigVo;
import com.smartdock.domain.vo.DcsAiModelVo;
import com.smartdock.domain.vo.DcsAiProviderPresetVo;
import com.smartdock.domain.vo.DcsAiTestResultVo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * DCS AI 模型配置 Service。
 *
 * @author smartdock
 */
public interface IDcsAiConfigService {

    /** 分页查询 */
    TableDataInfo<DcsAiConfigVo> queryPageList(DcsAiConfigBo bo, PageQuery pageQuery);

    /** 按ID查询 */
    DcsAiConfigVo queryById(Long configId);

    /** 新增 */
    Long insertByBo(DcsAiConfigBo bo);

    /** 修改 */
    Boolean updateByBo(DcsAiConfigBo bo);

    /** 批量删除 */
    Boolean deleteByIds(Collection<Long> ids);

    /** 内置厂商预设列表(便于前端表单选择) */
    List<DcsAiProviderPresetVo> presets();

    /** 测试已保存配置的连通性 */
    DcsAiTestResultVo testConnection(Long configId);

    /**
     * 巡检专用探活（AI 缺口批 Q9）：系统上下文（04:00 Job 无登录态），
     * 不做 F9 访问校验、不改配置，仅 hydrate 后发一次极小调用。
     * 配置不存在/已停用返回 null（调用方跳过）。
     */
    DcsAiTestResultVo patrolProbe(Long configId);

    /** 用临时入参探测可用模型列表 */
    List<DcsAiModelVo> probeModels(DcsAiProbeBo bo);

    /** 用临时入参探测账户余额 */
    DcsAiBalanceVo probeBalance(DcsAiProbeBo bo);

    /** 用临时入参探测响应速度(延迟) */
    DcsAiTestResultVo probeSpeed(DcsAiProbeBo bo);

    /** 对已保存配置探测可用模型列表(可叠加临时参数) */
    List<DcsAiModelVo> probeSavedModels(Long configId, DcsAiProbeBo bo);

    /** 对已保存配置探测账户余额 */
    DcsAiBalanceVo probeSavedBalance(Long configId, DcsAiProbeBo bo);

    /** 对已保存配置探测响应速度 */
    DcsAiTestResultVo probeSavedSpeed(Long configId, DcsAiProbeBo bo);

    /** 批量探测多个已保存配置的余额 */
    Map<Long, DcsAiBalanceVo> batchSavedBalances(Collection<Long> configIds);

    /** 取当前租户启用的默认 AI 配置(任一 use_type) */
    DcsAiConfig currentTenantEnabledConfig();

    /**
     * 按用途取当前租户启用的 AI 配置(Phase 4 引入, 让 CHAT 和 EMBEDDING 配置互不干扰)。
     *
     * @param useType 用途类型 CHAT=AI 调用, EMBEDDING=向量化; 传 null 等价于 {@link #currentTenantEnabledConfig()}
     */
    DcsAiConfig currentTenantEnabledConfig(String useType);

    /**
     * 单点解析器(J 批一期, 2026-10-08): 全系统唯一的「租户当前生效 AI 配置」口径。
     * 排序=update_time DESC, config_id DESC; 含 CNY 闸(FIXED_PER_CALL 单价 0 豁免,
     * 其余必须有 CNY 价格行, 拍板 A 全系统统一拒美元模型)。
     *
     * @throws ServiceException 无启用行或 CNY 闸不过(HTTP 单发路沿袭的异常类型)
     */
    DcsAiConfig resolveEnabledConfig(String tenantId, String useType);

    /**
     * 后台链配置解析·当前登录租户（业务反馈批1015c Q1 拍板A）：画像诊断/字段生成/评分/
     * 规则推荐等后台功能的默认模型口径——后台默认槽（dcs_assistant_model_setting.
     * backend_default_config_id，助手管理页设置）→ 接口默认★ → 最近可用行（含 CNY 闸）。
     * 槽未设或指向失效（停用/删行/价格缺失）静默回退接口默认，坏槽不炸功能。
     *
     * @throws ServiceException 无启用行或 CNY 闸不过（与 resolveEnabledConfig 同语义）
     */
    DcsAiConfig resolveBackendConfig();

    /**
     * 后台链配置解析·显式租户（MQ/异步路同口径，tenantId 由调用方给）。
     *
     * @throws ServiceException 同 {@link #resolveBackendConfig()}
     */
    DcsAiConfig resolveBackendConfig(String tenantId);

    /**
     * 最便宜启用行(业务反馈批1015b Q2, 2026-10-15): 追问侧车"轻快模型"供值——
     * 该租户该用途启用行中按 积分倍率 asc(NULL 视 1, PG ASC 天然 NULLS LAST)→按次单价 asc→
     * 最近更新 兜底 取第一条。找不到/闸不过返回 null(调用方回退默认链), 不抛错。
     */
    DcsAiConfig resolveCheapestEnabledConfig(String tenantId, String useType);

    /**
     * 按主键解析指定配置行(批3, 2026-10-09, Q11 助手默认槽供值): 校验行存在/租户匹配/
     * 可用(enabled='1')后过同一 CNY 闸——助手默认/接口默认两路同闸同口径。
     *
     * @throws ServiceException 行不存在/跨租户/已停用/CNY 闸不过
     */
    DcsAiConfig resolveConfigById(String tenantId, Long configId);

    /**
     * 可用行+单价列表(批4, 2026-10-09, Q5/Q6): 助手管理页模型选择卡与用户选择器数据源。
     * 只返回 enabled='1' 行(停用行天然过滤), token 计费行透出价格目录 input/output 单价。
     *
     * @param tenantId 租户编号
     * @param useType  用途(选择器场景固定 CHAT)
     * @return 可用行(★默认行排最前, 同级按 update_time DESC), Key 已 mask
     */
    List<DcsAiConfigVo> listUsableWithPrice(String tenantId, String useType);

    /**
     * 助手用户侧可选行（超级 E2E 2026-10-10 修复）：listUsableWithPrice 基础上再过 CNY 闸——
     * 与工厂 resolveConfigWithUserChoice 同一裁决，避免「选项里能选、实际静默回退默认模型」
     * （E2E 实证：deepseek-chat 价格目录为 USD 行，用户 pin 后 pill 显示与实际计费模型不符）。
     * 管理端 /options 刻意不过闸（管理员要看到全量行去改价格），两侧口径不同是设计。
     *
     * @param tenantId 租户编号
     * @param useType  用途(选择器场景固定 CHAT)
     * @return 可用且过闸行(★默认行排最前), Key 已 mask
     */
    List<DcsAiConfigVo> listAssistantSelectableWithPrice(String tenantId, String useType);

    /**
     * 设为默认(批3 星标化): 同租户同 use_type 旧默认摘星, 目标行置星(is_default='1'),
     * 不再改 enabled——状态(可用性)与默认(星标)正交。
     * 注意: 按用途分组隔离, CHAT 和 EMBEDDING 各自独立换星, 互不影响;
     * 目标行须可用(停用行点星 409 防呆)。
     *
     * @param configId 要激活的配置ID
     */
    Boolean activate(Long configId);

    /**
     * 批量健康检查(对指定租户的配置逐个 testConnection)。
     *
     * @param tenantId  租户ID(null=当前租户)
     * @param configIds 可选的配置ID子集(业务批4 Q17-A；null/空=全部，向后兼容)
     * @return 每个配置的检测结果
     */
    List<DcsAiTestResultVo> batchHealthCheck(String tenantId, List<Long> configIds);

    /**
     * Q3-A（2026-10-23 拍板）：租户最近一次成功调用过的配置 ID。
     * 默认模型缺失/被关时，用户侧据此沿用上次选择；从未用过返回 null。
     *
     * @param tenantId 租户ID(null=当前租户)
     * @return 最近使用的 configId；无记录返回 null
     */
    Long latestUsedConfigId(String tenantId);
}
