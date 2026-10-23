package com.smartdock.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import com.smartdock.domain.DcsAiModelHealth;
import com.smartdock.domain.bo.DcsAiConfigBo;
import com.smartdock.domain.bo.DcsAiProbeBo;
import com.smartdock.domain.bo.DcsAiProviderAccessBo;
import com.smartdock.domain.bo.DetectPriceBo;
import com.smartdock.domain.vo.DcsAiBalanceVo;
import com.smartdock.domain.vo.DcsAiConfigVo;
import com.smartdock.domain.vo.DcsAiModelVo;
import com.smartdock.domain.vo.DcsAiProviderAccessVo;
import com.smartdock.domain.vo.DcsAiProviderPresetVo;
import com.smartdock.domain.vo.DcsAiProviderTemplateVo;
import com.smartdock.domain.vo.DcsAiTestResultVo;
import com.smartdock.enums.DcsAiApiFormat;
import com.smartdock.enums.DcsAiProviderTemplates;
import com.smartdock.mapper.DcsAiModelHealthMapper;
import com.smartdock.service.IDcsAiConfigService;
import com.smartdock.service.IDcsAiProviderAccessService;
import com.smartdock.service.support.AiPriceAutoDetectService;
import com.smartdock.domain.vo.AiPriceDetectResult;
import org.dromara.system.domain.bo.SysDictDataBo;
import org.dromara.system.domain.vo.SysDictDataVo;
import org.dromara.system.service.ISysDictDataService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DCS AI 模型配置 Controller。
 *
 * @author smartdock
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dcs/ai-config")
public class DcsAiConfigController extends BaseController {

    private final IDcsAiConfigService aiConfigService;
    private final ISysDictDataService dictDataService;
    private final AiPriceAutoDetectService priceAutoDetectService;
    /** 厂商接入（批B：钥匙单一真源） */
    private final IDcsAiProviderAccessService providerAccessService;

    /** AI 缺口批 Q9：巡检健康只读（模型列表徽标） */
    private final DcsAiModelHealthMapper modelHealthMapper;

    /**
     * 分页查询。
     *
     * @param bo         AI 配置查询条件
     * @param pageQuery 分页参数
     * @return 分页的 AI 配置列表
     */
    @SaCheckPermission("dcs:ai:list")
    @GetMapping("/list")
    public TableDataInfo<DcsAiConfigVo> list(DcsAiConfigBo bo, PageQuery pageQuery) {
        return aiConfigService.queryPageList(bo, pageQuery);
    }

    /**
     * 详情。
     *
     * @param configId AI 配置ID
     * @return AI 配置详情数据
     */
    @SaCheckPermission("dcs:ai:query")
    @GetMapping("/{configId:\\d+}")
    public R<DcsAiConfigVo> getInfo(@PathVariable Long configId) {
        return R.ok(aiConfigService.queryById(configId));
    }

    /**
     * 取内置厂商预设(含 baseUrl/默认参数, 供新增配置时下拉选择)。
     * 只读目录数据, 积分套餐页(租户管理员, 持 dcs:ai-config:query)也要拉取——
     * 2026-10-16 P0 修复: 原来只认 dcs:ai:list, 租户管理员打开积分套餐厂商卡静默降级成英文代码。
     */
    @SaCheckPermission(value = {"dcs:ai:list", "dcs:ai-config:query"}, mode = SaMode.OR)
    @GetMapping("/presets")
    public R<List<DcsAiProviderPresetVo>> presets() {
        return R.ok(aiConfigService.presets());
    }

    /**
     * Q5-A（2026-10-23 拍板）：20 家厂商模板库——选厂商自动预填地址/格式/模型清单。
     */
    @SaCheckPermission(value = {"dcs:ai:list", "dcs:ai-config:query"}, mode = SaMode.OR)
    @GetMapping("/templates")
    public R<List<DcsAiProviderTemplateVo>> templates() {
        return R.ok(DcsAiProviderTemplates.list());
    }

    /**
     * Q5-A：三种接口格式（value/label/path），前端下拉与请求体预览共用一份口径。
     */
    @SaCheckPermission(value = {"dcs:ai:list", "dcs:ai-config:query"}, mode = SaMode.OR)
    @GetMapping("/api-formats")
    public R<List<Map<String, String>>> apiFormats() {
        return R.ok(Arrays.stream(DcsAiApiFormat.values())
            .map(f -> Map.of("value", f.name(), "label", f.getLabel(), "path", f.getPath()))
            .toList());
    }

    /**
     * AI 模型厂商字典(从 sys_dict_data dcs_ai_provider 加载, 供前端下拉)。
     *
     * @return 厂商字典数据列表
     */
    @SaCheckPermission("dcs:ai:list")
    @GetMapping("/provider-dicts")
    public R<List<SysDictDataVo>> providerDicts() {
        SysDictDataBo query = new SysDictDataBo();
        query.setDictType("dcs_ai_provider");
        return R.ok(dictDataService.selectDictDataList(query));
    }

    /**
     * 智能识别计费参数(从官方文档 URL 抓取 + 正则/AI 提取)。
     *
     * @param bo 计费参数识别请求(厂商/模型名/文档地址)
     * @return 计费参数识别结果
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI配置-识别计费", businessType = BusinessType.OTHER)
    @PostMapping("/detect-price")
    public R<AiPriceDetectResult> detectPrice(@RequestBody DetectPriceBo bo) {
        return R.ok(priceAutoDetectService.detect(bo.getProvider(), bo.getModelName(), bo.getSourceUrl()));
    }

    /**
     * 新增。
     *
     * @param bo AI 配置业务对象
     * @return 新建记录的ID
     */
    @SaCheckPermission("dcs:ai:add")
    @Log(title = "DCS AI配置", businessType = BusinessType.INSERT, excludeParamNames = "apiKey")
    @RepeatSubmit
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody DcsAiConfigBo bo) {
        return R.ok(aiConfigService.insertByBo(bo));
    }

    /**
     * 修改。
     *
     * @param bo AI 配置业务对象
     * @return 操作结果
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI配置", businessType = BusinessType.UPDATE, excludeParamNames = "apiKey")
    @RepeatSubmit
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/edit")
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody DcsAiConfigBo bo) {
        return toAjax(aiConfigService.updateByBo(bo));
    }

    /**
     * 测试已存配置连通性(实际调用一次模型, 返回延迟/成功标志)。
     *
     * @param configId AI 配置ID
     * @return 连通性测试结果
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI配置-连接测试", businessType = BusinessType.OTHER)
    @PostMapping("/{configId:\\d+}/test")
    public R<DcsAiTestResultVo> test(@PathVariable Long configId) {
        return R.ok(aiConfigService.testConnection(configId));
    }

    /**
     * 按临时参数探测可用模型列表(未保存前自测, 调用厂商 /models 接口)。
     *
     * @param bo AI 探测临时参数
     * @return 可用模型列表
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI配置-探测模型", businessType = BusinessType.OTHER, excludeParamNames = "apiKey")
    @PostMapping("/probe/models")
    public R<List<DcsAiModelVo>> probeModels(@Validated @RequestBody DcsAiProbeBo bo) {
        return R.ok(aiConfigService.probeModels(bo));
    }

    /**
     * 按临时参数探测账户余额(未保存前自测)。
     *
     * @param bo AI 探测临时参数
     * @return 账户余额探测结果
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI配置-探测余额", businessType = BusinessType.OTHER, excludeParamNames = "apiKey")
    @PostMapping("/probe/balance")
    public R<DcsAiBalanceVo> probeBalance(@Validated @RequestBody DcsAiProbeBo bo) {
        return R.ok(aiConfigService.probeBalance(bo));
    }

    /**
     * 按临时参数探测响应速度(TPS/首字延迟, 未保存前自测)。
     *
     * @param bo AI 探测临时参数
     * @return 响应速度探测结果
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI配置-探测速度", businessType = BusinessType.OTHER, excludeParamNames = "apiKey")
    @PostMapping("/probe/speed")
    public R<DcsAiTestResultVo> probeSpeed(@Validated @RequestBody DcsAiProbeBo bo) {
        return R.ok(aiConfigService.probeSpeed(bo));
    }

    /**
     * 用已存配置(apiKey 走密文)探测可用模型列表。
     *
     * @param configId AI 配置ID
     * @param bo       可选的探测覆盖参数
     * @return 可用模型列表
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI配置-探测模型(已存)", businessType = BusinessType.OTHER, excludeParamNames = "apiKey")
    @PostMapping("/{configId:\\d+}/probe/models")
    public R<List<DcsAiModelVo>> probeSavedModels(@PathVariable Long configId, @RequestBody(required = false) DcsAiProbeBo bo) {
        return R.ok(aiConfigService.probeSavedModels(configId, bo));
    }

    /**
     * 用已存配置探测账户余额。
     *
     * @param configId AI 配置ID
     * @param bo       可选的探测覆盖参数
     * @return 账户余额探测结果
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI配置-探测余额(已存)", businessType = BusinessType.OTHER, excludeParamNames = "apiKey")
    @PostMapping("/{configId:\\d+}/probe/balance")
    public R<DcsAiBalanceVo> probeSavedBalance(@PathVariable Long configId, @RequestBody(required = false) DcsAiProbeBo bo) {
        return R.ok(aiConfigService.probeSavedBalance(configId, bo));
    }

    /**
     * 用已存配置探测响应速度(TPS/首字延迟)。
     *
     * @param configId AI 配置ID
     * @param bo       可选的探测覆盖参数
     * @return 响应速度探测结果
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI配置-探测速度(已存)", businessType = BusinessType.OTHER, excludeParamNames = "apiKey")
    @PostMapping("/{configId:\\d+}/probe/speed")
    public R<DcsAiTestResultVo> probeSavedSpeed(@PathVariable Long configId, @RequestBody(required = false) DcsAiProbeBo bo) {
        return R.ok(aiConfigService.probeSavedSpeed(configId, bo));
    }

    /**
     * 批量查询已存配置的余额(列表页展示用, 按 configId 映射)。
     *
     * @param configIds AI 配置ID列表
     * @return 配置ID到余额信息的映射
     */
    @SaCheckPermission("dcs:ai:list")
    @PostMapping("/balances")
    public R<Map<Long, DcsAiBalanceVo>> balances(@RequestBody List<Long> configIds) {
        return R.ok(aiConfigService.batchSavedBalances(configIds));
    }

    /**
     * 一键切换活跃配置(CC Switch 核心功能)。
     * 同租户仅一条 enabled='1', 其余自动停用。
     *
     * @param configId AI 配置ID
     * @return 操作结果
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI配置-切换活跃", businessType = BusinessType.UPDATE)
    @PostMapping("/{configId:\\d+}/activate")
    public R<Void> activate(@PathVariable Long configId) {
        // 七审 m3 P1-7：去 TenantHelper 传参匹配（超管动态租户下 getTenantId 与行租户错位
        // =OpenCode 铺开实锤 409 死按钮），服务层按行租户 ensureCanAccessAiConfig 校验
        return toAjax(aiConfigService.activate(configId));
    }

    /**
     * 批量健康检查(对当前租户配置逐个 testConnection)。
     * 业务批4 Q17-A（2026-10-13 拍板）：body 可带 configIds 子集（null/空数组=全部）。
     *
     * @param configIds 可选的配置ID数组
     * @return 健康检查结果列表
     */
    @SaCheckPermission("dcs:ai:list")
    @PostMapping("/health-check")
    public R<List<DcsAiTestResultVo>> healthCheck(@RequestBody(required = false) List<Long> configIds) {
        return R.ok(aiConfigService.batchHealthCheck(null, configIds));
    }

    /**
     * AI 缺口批 Q9（2026-10-14）：本租户各配置最新巡检健康行（模型列表徽标数据源）。
     * 04:00 巡检落库，此处只读——无行=从未巡检（徽标显示未知）。
     */
    @SaCheckPermission("dcs:ai:list")
    @GetMapping("/health/latest")
    public R<Map<Long, DcsAiModelHealth>> latestHealth() {
        String tenantId = LoginHelper.getTenantId();
        Map<Long, DcsAiModelHealth> byConfig = new LinkedHashMap<>();
        for (DcsAiModelHealth row : modelHealthMapper.selectList(
            new LambdaQueryWrapper<DcsAiModelHealth>()
                .eq(DcsAiModelHealth::getTenantId, tenantId)
                .orderByDesc(DcsAiModelHealth::getCheckTime))) {
            byConfig.putIfAbsent(row.getConfigId(), row);
        }
        return R.ok(byConfig);
    }

    /**
     * 厂商接入查询（批B 多模型改造）：某租户某厂商的钥匙/接口地址状态。
     * 返回 null=该厂商从未配置接入（前端切「首次配置需填 Key」形态）。
     *
     * @param tenantId 租户编号
     * @param provider 模型厂商
     * @return 接入信息（掩码出参）
     */
    @SaCheckPermission("dcs:ai:query")
    @GetMapping("/access")
    public R<DcsAiProviderAccessVo> getAccess(@RequestParam String tenantId, @RequestParam String provider) {
        return R.ok(providerAccessService.getAccess(tenantId, provider));
    }

    /**
     * 厂商接入新增/编辑（批B）：钥匙单一真源，一处改全厂生效。
     * apiKey 留空/****=沿用原密钥；返回 affectedModelCount=本次变更已覆盖的模型行数。
     *
     * @param bo 接入业务对象
     * @return 更新后的接入（掩码出参）
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI配置-厂商接入", businessType = BusinessType.UPDATE, excludeParamNames = "apiKey")
    @RepeatSubmit
    @PostMapping("/access")  // 2026-10-16 PUT→POST（铁律#21）：客户校园网 WAF 只放行 GET/POST，PUT 一发即被拦"后端接口连接异常"
    public R<DcsAiProviderAccessVo> updateAccess(@Validated(EditGroup.class) @RequestBody DcsAiProviderAccessBo bo) {
        return R.ok(providerAccessService.updateAccess(bo));
    }

    /**
     * AI 缺口批 Q8（2026-10-14）：某租户某厂商的钥匙列表（掩码+启停+主钥匙标记+余额快照）。
     */
    @SaCheckPermission("dcs:ai:query")
    @GetMapping("/access/list")
    public R<List<DcsAiProviderAccessVo>> listAccessKeys(@RequestParam String tenantId,
                                                         @RequestParam String provider) {
        return R.ok(providerAccessService.listKeys(tenantId, provider));
    }

    /**
     * Q8 补遗（2026-10-15）：显式新增一把钥匙。PUT /access 的「无 id=编辑主钥匙」批B 兼容
     * 语义让 UI「添加新钥匙」永远编辑首行插不进第二把——新增语义独立成端（POST，铁律#21）。
     */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI配置-钥匙新增", businessType = BusinessType.INSERT, excludeParamNames = "apiKey")
    @RepeatSubmit
    @PostMapping("/access/add")
    public R<DcsAiProviderAccessVo> addAccessKey(@RequestBody DcsAiProviderAccessBo bo) {
        return R.ok(providerAccessService.addKey(bo));
    }

    /** Q8：钥匙启停（不能停最后一把启用中的钥匙）。 */
    @SaCheckPermission("dcs:ai:edit")
    @Log(title = "DCS AI配置-钥匙启停", businessType = BusinessType.UPDATE)
    @PostMapping("/access/change-status")
    public R<Void> changeKeyStatus(@RequestParam Long id, @RequestParam String enabled) {
        providerAccessService.changeKeyStatus(id, enabled);
        return R.ok();
    }

    /** Q8：删除钥匙（软删，不能删最后一把启用中的钥匙）。 */
    @SaCheckPermission("dcs:ai:remove")
    @Log(title = "DCS AI配置-钥匙删除", businessType = BusinessType.DELETE)
    @PostMapping("/access/delete")
    public R<Void> deleteKey(@RequestParam Long id) {
        providerAccessService.deleteKey(id);
        return R.ok();
    }

    /**
     * Q8：单钥匙余额刷新（实查厂商端点，快照落钥匙行；UNSUPPORTED/FAILED 状态语义见服务层）。
     */
    @SaCheckPermission("dcs:ai:query")
    @PostMapping("/access/{id:\\d+}/balance")
    public R<DcsAiProviderAccessVo> refreshKeyBalance(@PathVariable Long id) {
        return R.ok(providerAccessService.refreshBalance(LoginHelper.getTenantId(), id));
    }

    /** Q8：整厂钥匙余额批量刷新（手动「全部刷新」，UNSUPPORTED 跳过零外呼）。 */
    @SaCheckPermission("dcs:ai:query")
    @PostMapping("/access/balance-all")
    public R<List<DcsAiProviderAccessVo>> refreshAllKeyBalances(@RequestParam String tenantId,
                                                                @RequestParam String provider) {
        return R.ok(providerAccessService.refreshAllBalances(tenantId, provider));
    }

    /**
     * 删除。
     *
     * @param configIds AI 配置ID数组
     * @return 操作结果
     */
    @SaCheckPermission("dcs:ai:remove")
    @Log(title = "DCS AI配置", businessType = BusinessType.DELETE)
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/{configIds}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] configIds) {
        return toAjax(aiConfigService.deleteByIds(List.of(configIds)));
    }
}
