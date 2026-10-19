package com.smartdock.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.DcsAiProviderAccess;
import com.smartdock.domain.bo.DcsAiConfigBo;
import org.springframework.dao.DuplicateKeyException;
import com.smartdock.domain.bo.DcsAiProbeBo;
import com.smartdock.domain.vo.DcsAiBalanceVo;
import com.smartdock.domain.vo.DcsAiConfigVo;
import com.smartdock.domain.vo.DcsAiModelVo;
import com.smartdock.domain.vo.DcsAiProviderPresetVo;
import com.smartdock.domain.vo.DcsAiTestResultVo;
import com.smartdock.enums.DcsAiProvider;
import com.smartdock.mapper.AssistantBackendDefaultMapper;
import com.smartdock.mapper.DcsAiConfigMapper;
import com.smartdock.mapper.DcsAiProviderAccessMapper;
import com.smartdock.service.IDcsAiConfigService;
import com.smartdock.service.IDcsAiModelPriceService;
import com.smartdock.domain.DcsAiModelPrice;
import com.smartdock.service.support.DcsAiChatClientFactory;
import com.smartdock.service.support.DcsAiProviderProbeClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Objects;

/**
 * DCS AI 模型配置 Service 实现。
 *
 * @author smartdock
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DcsAiConfigServiceImpl implements IDcsAiConfigService {

    private static final String ENABLED = "1";
    private static final String DISABLED = "0";
    private static final String MASKED_KEY = "****";
    private static final String BILLING_MODE_OFFICIAL_TOKEN = "OFFICIAL_TOKEN";
    private static final String BILLING_MODE_FIXED_PER_CALL = "FIXED_PER_CALL";
    /** 业务批4 Q4：官方价＋每次服务费（与 AiBillingCalculator.BILLING_MODE_OFFICIAL_PLUS_FEE 同值，
     *  本类沿用本地常量风格不跨包引 static import） */
    private static final String BILLING_MODE_OFFICIAL_PLUS_FEE = "OFFICIAL_PLUS_FEE";

    /** AI 配置表 Mapper(模型/厂商/Key 管理) */
    private final DcsAiConfigMapper aiConfigMapper;
    /** 厂商接入表 Mapper(批B 钥匙单一真源, hydrate 两跳第一跳) */
    private final DcsAiProviderAccessMapper accessMapper;
    /** AI 聊天客户端工厂(测试连接/余额探测) */
    private final DcsAiChatClientFactory chatClientFactory;
    /** 厂商探测客户端(测速/可用性探测) */
    private final DcsAiProviderProbeClient providerProbeClient;
    /** 模型价格查询(单点解析器 CNY 闸用, J 批一期) */
    private final IDcsAiModelPriceService modelPriceService;
    /** 厂商钥匙池(AI 缺口批 Q8：hydrate 运行时取钥改轮询选路) */
    private final com.smartdock.service.support.ProviderKeyPool keyPool;

    /** 后台默认槽只读（dcs_assistant_model_setting 跨模块直查表，批1015c Q1） */
    private final AssistantBackendDefaultMapper backendDefaultMapper;

    /** 分页查询 AI 配置(忽略租户隔离, 列表自动 mask API Key) */
    @Override
    public TableDataInfo<DcsAiConfigVo> queryPageList(DcsAiConfigBo bo, PageQuery pageQuery) {
        Page<DcsAiConfigVo> page = TenantHelper.ignore(() ->
            aiConfigMapper.selectVoPage(pageQuery.build(), buildWrapper(bo)));
        enrichWithPrice(page.getRecords());
        hydrateVo(page.getRecords());
        page.getRecords().forEach(this::mask);
        return TableDataInfo.build(page);
    }

    /** 查询单条配置(校验跨租户访问权 + mask API Key) */
    @Override
    public DcsAiConfigVo queryById(Long configId) {
        DcsAiConfigVo vo = TenantHelper.ignore(() -> aiConfigMapper.selectVoById(configId));
        if (vo == null) {
            throw new ServiceException("AI配置不存在");
        }
        // F9: 校验当前用户能否访问该 config(防跨租户枚举读)
        ensureCanAccessAiConfig(LoginHelper.getTenantId(), vo.getTenantId(), LoginHelper.isSuperAdmin());
        enrichWithPrice(java.util.List.of(vo));
        hydrateVo(java.util.List.of(vo));
        mask(vo);
        return vo;
    }

    /**
     * 可用行+单价列表（批4 Q5/Q6）：助手管理页模型选择卡与用户选择器的数据源。
     * 批3 起可用行可多条共存；价格字段透出供选择器显示单价（token 模式查价格目录，
     * 按次模式用本行 unitPricePerCall）。管理页低频读取, 逐行 findEffectivePrice 即可。
     */
    @Override
    public List<DcsAiConfigVo> listUsableWithPrice(String tenantId, String useType) {
        List<DcsAiConfigVo> rows = TenantHelper.ignore(() -> aiConfigMapper.selectVoList(
            new LambdaQueryWrapper<DcsAiConfig>()
                .eq(DcsAiConfig::getTenantId, tenantId)
                .eq(DcsAiConfig::getEnabled, ENABLED)
                .eq(DcsAiConfig::getUseType, useType)
                // 批8-3 回修（glm53 P1-2）：PG 的 ORDER BY col DESC 默认 NULLS FIRST，而普通行
                // is_default=NULL——orderByDesc 会把★沉底，与「默认行排最前」语义相反；普通行不进
                // 索引取 NULL，须显式 NULLS LAST（常量串无注入面）
                .last("ORDER BY is_default DESC NULLS LAST, update_time DESC")));
        enrichWithPrice(rows);
        hydrateVo(rows);
        rows.forEach(this::mask);
        return rows;
    }

    /**
     * 助手用户侧可选行（超级 E2E 2026-10-10 修复）：再过 CNY 闸（与 enforceCnyGate 同语义）——
     * FIXED_PER_CALL 单价 0 豁免；其余行价格目录查不到/非 CNY 一律不进选项。
     * 价格目录闪断按「查不到」处理（与闸的降级拒绝口径一致），下轮请求自愈。
     */
    @Override
    public List<DcsAiConfigVo> listAssistantSelectableWithPrice(String tenantId, String useType) {
        List<DcsAiConfigVo> rows = listUsableWithPrice(tenantId, useType);
        return rows.stream()
            .filter(vo -> {
                boolean fixedFreeTier = BILLING_MODE_FIXED_PER_CALL.equalsIgnoreCase(
                    vo.getBillingMode() == null ? "" : vo.getBillingMode())
                    && vo.getUnitPricePerCall() != null && vo.getUnitPricePerCall().signum() == 0;
                if (fixedFreeTier) {
                    return true;
                }
                try {
                    DcsAiModelPrice price = TenantHelper.ignore(() -> modelPriceService.findEffectivePrice(
                        vo.getTenantId(), vo.getProvider(), vo.getModelName()));
                    return price != null && "CNY".equalsIgnoreCase(price.getCurrency());
                } catch (Exception e) {
                    return false;
                }
            })
            .collect(Collectors.toList());
    }

    /** 价格目录透出（批4）：token 计费行补 input/output 单价与币种；免 key 展示安全。
     *  业务批4 Q3：去掉 OFFICIAL_TOKEN 门槛——FIXED_PER_CALL 行查到价格目录 CNY 行也回填
     *  （OPENCODE 订阅 0 元行：不回填时用户选择器显示「价格未配置」误导，实际 0 元可换）；
     *  目录无行（真按次计费商户）price=null 不动，unitPricePerCall 展示链照旧 */
    private void enrichWithPrice(List<DcsAiConfigVo> rows) {
        for (DcsAiConfigVo vo : rows) {
            if (vo == null) {
                continue;
            }
            try {
                DcsAiModelPrice price = TenantHelper.ignore(() -> modelPriceService.findEffectivePrice(
                    vo.getTenantId(), vo.getProvider(), vo.getModelName()));
                if (price != null) {
                    vo.setInputPricePerMillion(price.getInputPricePerMillion());
                    vo.setOutputPricePerMillion(price.getOutputPricePerMillion());
                    vo.setCurrency(price.getCurrency());
                }
            } catch (Exception e) {
                // 价格目录读失败不阻断列表(单价是展示增强, 非可用性判据)
            }
        }
    }

    /** 新增 AI 配置(批B 多模型解禁: 唯一键=同厂同模型; 钥匙写入接入层, 模型行不再存钥匙) */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(DcsAiConfigBo bo) {
        normalize(bo, true);
        // 批B 三审 P0：新增入口补租户守卫（同 updateByBo 口径），防非超管携带他人 tenantId 建行
        ensureCanAccessAiConfig(LoginHelper.getTenantId(), bo.getTenantId(), LoginHelper.isSuperAdmin());
        TenantHelper.ignore(() -> {
            // 同厂多模型已解禁(uk 换 (tenant,provider,model_name)); 钥匙路由到接入层:
            // 填了新钥匙→upsert 接入行; 没填→该厂商必须已有接入(首次配置由「管理钥匙」或本表单给钥匙)
            upsertAccessFromConfigBo(bo, true);
            checkTenantProviderModelUnique(null, bo.getTenantId(), bo.getProvider(), bo.getModelName());
        });
        DcsAiConfig entity = MapstructUtils.convert(bo, DcsAiConfig.class);
        // 批3(Q4:A): enabled 语义=status 可用性,不再有「唯一启用」索引——配好即可用直接插入;
        // 默认星标与可用性解耦:新增行永不带星(is_default=NULL),换星走 activate 一步摘旧置新
        entity.setIsDefault(null);
        // 批B(Q5:A): 旧钥匙列停写——新模型行 inline 写 NULL, 钥匙/地址由接入层 hydrate
        entity.setApiKey(null);
        entity.setBaseUrl(null);
        try {
            TenantHelper.ignore(() -> aiConfigMapper.insert(entity));
        } catch (DuplicateKeyException e) {
            throw onConfigDuplicateKey(e);
        }
        return entity.getConfigId();
    }

    /** 更新 AI 配置(批B: 钥匙变更路由到接入层一处生效, 模型行旧列不再写) */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(DcsAiConfigBo bo) {
        normalize(bo, false);
        return TenantHelper.ignore(() -> {
            DcsAiConfig origin = aiConfigMapper.selectById(bo.getConfigId());
            if (origin == null) {
                throw new ServiceException("AI配置不存在");
            }
            // glm53 七审 P2-2：编辑入口补租户访问校验（同 queryById/testConnection 的 F9 口径，
            // 防越权编辑叠加守卫停用别租户的活跃行）
            ensureCanAccessAiConfig(LoginHelper.getTenantId(), origin.getTenantId(), LoginHelper.isSuperAdmin());
            // 批B 三审 P0：tenantId 不可改——表单租户与行租户不一致直接拒，且 patch 剥离租户列防行被搬走
            if (StrUtil.isNotBlank(bo.getTenantId()) && !StrUtil.equals(bo.getTenantId(), origin.getTenantId())) {
                throw new ServiceException("不允许修改配置所属租户");
            }
            checkTenantProviderModelUnique(bo.getConfigId(), bo.getTenantId(), bo.getProvider(), bo.getModelName());
            // 批B: 表单带了真钥匙(非空非****)→写接入层(整厂生效); 模型行 inline 列一律不动
            // （requireAccess=false：编辑存量行不强制厂接入已配——停启用/改名等无关钥匙的编辑不误伤）
            upsertAccessFromConfigBo(bo, false);
            DcsAiConfig patch = MapstructUtils.convert(bo, DcsAiConfig.class);
            patch.setTenantId(null);
            // 批3: 换星只走 activate(先摘旧星再置新星),通用编辑一律剥离 is_default——
            // 防并发编辑撞「唯一默认」部分索引(与 J 批同教训,星标语义下复用)
            patch.setIsDefault(null);
            patch.setApiKey(null);
            patch.setBaseUrl(null);
            boolean updated;
            try {
                updated = aiConfigMapper.updateById(patch) > 0;
            } catch (DuplicateKeyException e) {
                throw onConfigDuplicateKey(e);
            }
            // 批C 回修（glm53 P1-3 + dsflash P2-2）：「清空=回模型默认」落地——updateById 忽略
            // null，须显式置 NULL 才写得进去；判据用 clearMaxTokens 显式信号而非「BO 没带值」，
            // 免得未来的部分提交调用方（只传 enabled 之类）把存量值静默清掉
            if (updated && Boolean.TRUE.equals(bo.getClearMaxTokens()) && origin.getMaxTokens() != null) {
                aiConfigMapper.update(null, new LambdaUpdateWrapper<DcsAiConfig>()
                    .set(DcsAiConfig::getMaxTokens, null)
                    .eq(DcsAiConfig::getConfigId, bo.getConfigId()));
            }
            return updated;
        });
    }

    /** 配置行唯一键冲突转人话（批B 起 DuplicateKey 可能来自两个部分唯一索引，按索引名分流） */
    private ServiceException onConfigDuplicateKey(DuplicateKeyException e) {
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("uk_dcs_ai_config_tenant_model")) {
            return new ServiceException("该厂商下已存在同名模型，请直接编辑那一行或换个模型名");
        }
        if (msg.contains("uk_dcs_ai_config_tenant_default")) {
            // 批8-3 回修（dspro P2-2）：带★行被编辑改 use_type 到已有★的用途时撞部分唯一索引
            return new ServiceException("该用途已有默认模型，请先把原用途的默认星标切换走再改用途");
        }
        return new ServiceException("并发操作冲突，请刷新后重试");
    }

    /**
     * 批B 钥匙路由：配置表单带了真钥匙(非空非****)→upsert 接入层一行(整厂生效)；
     * 没带钥匙时按 requireAccess 决定是否强制厂接入已配——新增行必须配(strict=true，没接入=调不动)；
     * 编辑存量行不强制(false，停启用/改名等无关钥匙的编辑不误伤，无接入则维持行内旧列回退)。
     */
    private void upsertAccessFromConfigBo(DcsAiConfigBo bo, boolean requireAccess) {
        DcsAiProviderAccess access = accessMapper.selectOne(new LambdaQueryWrapper<DcsAiProviderAccess>()
            .eq(DcsAiProviderAccess::getTenantId, bo.getTenantId())
            .eq(DcsAiProviderAccess::getProvider, bo.getProvider())
            .orderByAsc(DcsAiProviderAccess::getId)
            .last("limit 1"));
        boolean realKey = StrUtil.isNotBlank(bo.getApiKey()) && !MASKED_KEY.equals(bo.getApiKey());
        if (access == null) {
            if (!realKey) {
                if (requireAccess) {
                    throw new ServiceException("该厂商首次配置，必须填写 API Key（保存后同厂加模型不用再填）");
                }
                return;
            }
            if (StrUtil.isBlank(bo.getBaseUrl())) {
                throw new ServiceException("该厂商首次配置，必须填写接口地址");
            }
            DcsAiProviderAccess entity = new DcsAiProviderAccess();
            entity.setTenantId(bo.getTenantId());
            entity.setProvider(bo.getProvider());
            entity.setBaseUrl(StrUtil.removeSuffix(StrUtil.trim(bo.getBaseUrl()), "/"));
            entity.setApiKey(StrUtil.trim(bo.getApiKey()));
            try {
                accessMapper.insert(entity);
            } catch (DuplicateKeyException e) {
                throw new ServiceException("并发冲突：该厂商接入刚被其他人创建，请刷新后重试");
            }
            return;
        }
        if (realKey) {
            DcsAiProviderAccess patch = new DcsAiProviderAccess();
            patch.setId(access.getId());
            patch.setApiKey(StrUtil.trim(bo.getApiKey()));
            if (StrUtil.isNotBlank(bo.getBaseUrl())) {
                patch.setBaseUrl(StrUtil.removeSuffix(StrUtil.trim(bo.getBaseUrl()), "/"));
            }
            accessMapper.updateById(patch);
        }
    }

    /**
     * 批B hydrate（两跳取钥第一跳）：接入表优先覆盖行内值，无接入行时保留旧行内列回退
     * （Q5:A 存量兜底——迁移前每厂必有回填接入行，此分支只护「接入行被误删」的极端态）。
     * ChatClientFactory/AssistantModelFactory 等下游继续读 cfg.getApiKey()，零改动。
     */
    private void hydrate(DcsAiConfig config) {
        if (config == null) {
            return;
        }
        // AI 缺口批 Q8(2026-10-14)：接入表取钥改走钥匙池——同厂多把钥匙轮询+冷却+
        // 零余额跳过；池不可用(查询失败/无 enabled 行)回退 id 最小行(与批B 单钥行为
        // 等价，升级零断档)。批B 三审 P1 的 ignore 口径保留(超管跨租户解析场景)。
        DcsAiProviderAccess access = TenantHelper.ignore(() -> {
            DcsAiProviderAccess picked = keyPool.pick(config.getTenantId(), config.getProvider());
            return picked != null ? picked : accessMapper.selectOne(
                new LambdaQueryWrapper<DcsAiProviderAccess>()
                    .eq(DcsAiProviderAccess::getTenantId, config.getTenantId())
                    .eq(DcsAiProviderAccess::getProvider, config.getProvider())
                    .orderByAsc(DcsAiProviderAccess::getId)
                    .last("limit 1"));
        });
        if (access != null) {
            config.setApiKey(access.getApiKey());
            config.setBaseUrl(access.getBaseUrl());
        }
    }

    /** Vo 路径 hydrate（批B）：baseUrl/掩码按接入层覆盖 + accessConfigured 标记（列表✓展示用） */
    private void hydrateVo(List<DcsAiConfigVo> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<String, DcsAiProviderAccess> byKey = new LinkedHashMap<>();
        for (DcsAiConfigVo vo : rows) {
            if (vo == null || vo.getTenantId() == null || vo.getProvider() == null) {
                continue;
            }
            String key = vo.getTenantId() + '|' + vo.getProvider();
            if (!byKey.containsKey(key)) {
                byKey.put(key, TenantHelper.ignore(() -> accessMapper.selectOne(
                    new LambdaQueryWrapper<DcsAiProviderAccess>()
                        .eq(DcsAiProviderAccess::getTenantId, vo.getTenantId())
                        .eq(DcsAiProviderAccess::getProvider, vo.getProvider())
                        .last("limit 1"))));
            }
            DcsAiProviderAccess access = byKey.get(key);
            if (access != null) {
                vo.setApiKey(access.getApiKey());
                vo.setBaseUrl(access.getBaseUrl());
                vo.setAccessConfigured(true);
            } else {
                vo.setAccessConfigured(false);
            }
        }
    }

    /** 批量删除 AI 配置。P2 修复（2026-10-09 Q4）：删除集含★默认行时 log.warn 留痕——
     * 此后解析链静默回退「任意可用行」，无此日志则事后无痕迹；不阻断删除
     * （与批3「停用星标行警告不阻断」同口径；前端删除确认框对★行另有前置提示）。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        TenantHelper.ignore(() -> {
            List<DcsAiConfig> stars = aiConfigMapper.selectList(
                new LambdaQueryWrapper<DcsAiConfig>()
                    .in(DcsAiConfig::getConfigId, ids)
                    .eq(DcsAiConfig::getIsDefault, "1"));
            if (stars != null && !stars.isEmpty()) {
                String names = stars.stream()
                    .map(r -> r.getProvider() + "/" + r.getModelName() + "(" + r.getUseType() + ")")
                    .collect(Collectors.joining("、"));
                log.warn("[AI配置] 删除了默认星标行，接口默认将静默回退到其它可用行：{}（操作人={}）",
                    names, LoginHelper.getUserId());
            }
        });
        return TenantHelper.ignore(() -> aiConfigMapper.deleteByIds(ids) > 0);
    }

    /** 列出支持的厂商预设清单 */
    @Override
    public List<DcsAiProviderPresetVo> presets() {
        return DcsAiProvider.presets();
    }

    /** 测试已保存配置的连通性(短问题探测, 返回成功标志+耗时) */
    @Override
    public DcsAiTestResultVo testConnection(Long configId) {
        DcsAiConfig config = TenantHelper.ignore(() -> aiConfigMapper.selectById(configId));
        if (config == null) {
            throw new ServiceException("AI配置不存在");
        }
        // F9: 校验当前用户能否访问该 config(testConnection 会消耗他人 quota)
        ensureCanAccessAiConfig(LoginHelper.getTenantId(), config.getTenantId(), LoginHelper.isSuperAdmin());
        hydrate(config);
        try {
            long begin = System.nanoTime();
            String content = chatClientFactory.call(config, "请只回复 JSON: {\"ok\":true}", true, "TEST_CONNECTION");
            long latencyMs = (System.nanoTime() - begin) / 1_000_000;
            return new DcsAiTestResultVo(true, StrUtil.blankToDefault(content, "连接成功"), latencyMs);
        } catch (Exception e) {
            return new DcsAiTestResultVo(false, StrUtil.blankToDefault(e.getMessage(), "连接失败"));
        }
    }

    /**
     * 巡检专用探活（AI 缺口批 Q9，2026-10-14）：与 {@link #testConnection} 同芯（hydrate +
     * 极小调用），差异两点——系统上下文无登录态故跳过 F9 访问校验；配置停用/不存在返回
     * null 由调用方跳过（巡检探停用行只会白花钱且误导徽标）。
     */
    @Override
    public DcsAiTestResultVo patrolProbe(Long configId) {
        DcsAiConfig config = TenantHelper.ignore(() -> aiConfigMapper.selectById(configId));
        if (config == null || !ENABLED.equals(config.getEnabled())) {
            return null;
        }
        hydrate(config);
        try {
            long begin = System.nanoTime();
            String content = chatClientFactory.call(config, "请只回复 JSON: {\"ok\":true}", true, "PATROL");
            long latencyMs = (System.nanoTime() - begin) / 1_000_000;
            return new DcsAiTestResultVo(true, StrUtil.blankToDefault(content, "连接成功"), latencyMs);
        } catch (Exception e) {
            return new DcsAiTestResultVo(false, StrUtil.blankToDefault(e.getMessage(), "连接失败"));
        }
    }

    /** 用临时输入探测可用模型清单(SSRF 白名单校验; 批B: 钥匙留空可由接入行补齐) */
    @Override
    public List<DcsAiModelVo> probeModels(DcsAiProbeBo bo) {
        normalizeProbe(bo);
        fillProbeKeyFromAccess(bo);
        assertBaseUrlAllowed(bo);
        return providerProbeClient.listModels(bo);
    }

    /** 用临时输入探测账户余额 */
    @Override
    public DcsAiBalanceVo probeBalance(DcsAiProbeBo bo) {
        normalizeProbe(bo);
        fillProbeKeyFromAccess(bo);
        assertBaseUrlAllowed(bo);
        return providerProbeClient.balance(bo);
    }

    /** 用临时输入探测响应速度 */
    @Override
    public DcsAiTestResultVo probeSpeed(DcsAiProbeBo bo) {
        normalizeProbe(bo);
        fillProbeKeyFromAccess(bo);
        assertBaseUrlAllowed(bo);
        return providerProbeClient.speed(bo);
    }

    /**
     * 批B 探测补钥：apiKey 留空且带 tenantId → 从厂商接入行取钥（同厂加模型不重填钥匙）。
     * 无接入行时保持空钥（探测端自然报鉴权失败，不静默串到别厂）。
     */
    private void fillProbeKeyFromAccess(DcsAiProbeBo bo) {
        if (StrUtil.isNotBlank(bo.getApiKey()) || StrUtil.isBlank(bo.getTenantId())) {
            return;
        }
        // 批B 三审 P0：补钥前校验当前用户对该租户的访问权（防携带他人 tenantId 免钥读余额/模型清单）
        ensureCanAccessAiConfig(LoginHelper.getTenantId(), bo.getTenantId(), LoginHelper.isSuperAdmin());
        // Q8：探测类端点固定主钥匙（enabled 按 id 升序第一行）——语义稳定可解释
        DcsAiProviderAccess access = TenantHelper.ignore(() ->
            keyPool.primaryKey(bo.getTenantId(), bo.getProvider()));
        if (access != null) {
            bo.setApiKey(access.getApiKey());
        }
    }

    /** 基于已保存配置探测模型(可被 overrideBo 覆盖部分字段) */
    @Override
    public List<DcsAiModelVo> probeSavedModels(Long configId, DcsAiProbeBo overrideBo) {
        DcsAiProbeBo bo = toProbeBoForSavedConfig(loadAccessibleConfig(configId), overrideBo);
        normalizeProbe(bo);
        assertBaseUrlAllowed(bo);
        return providerProbeClient.listModels(bo);
    }

    /** 基于已保存配置探测余额(配置关闭余额查询时返回不可用) */
    @Override
    public DcsAiBalanceVo probeSavedBalance(Long configId, DcsAiProbeBo overrideBo) {
        DcsAiConfig config = loadAccessibleConfig(configId);
        if (!ENABLED.equals(config.getBalanceQueryEnabled())) {
            return new DcsAiBalanceVo(false, "该配置已关闭余额查询", List.of());
        }
        DcsAiProbeBo bo = toProbeBoForSavedConfig(config, overrideBo);
        normalizeProbe(bo);
        assertBaseUrlAllowed(bo);
        return providerProbeClient.balance(bo);
    }

    /** 基于已保存配置探测响应速度 */
    @Override
    public DcsAiTestResultVo probeSavedSpeed(Long configId, DcsAiProbeBo overrideBo) {
        DcsAiProbeBo bo = toProbeBoForSavedConfig(loadAccessibleConfig(configId), overrideBo);
        normalizeProbe(bo);
        assertBaseUrlAllowed(bo);
        return providerProbeClient.speed(bo);
    }

    /**
     * 批量查询多个配置的余额(单条失败仅记录不中断)。
     * 业务批3-②(#13)：每 configId 结果 Redis 缓存 30s（轮询防抖，厂商限流保护）——
     * 前端轮询从 5s 放宽到 30s 后，多个管理员同时开着页面仍会叠加外呼，缓存兜底把
     * 同一 configId 的真实厂商外呼压到 ≥30s 一次；失败结果同样缓存（30s 内不重试，
     * 防失败风暴）。键不带租户前缀（configId 全局雪花唯一，TenantHelper.ignore 裸键）。
     */
    @Override
    public Map<Long, DcsAiBalanceVo> batchSavedBalances(Collection<Long> configIds) {
        Map<Long, DcsAiBalanceVo> result = new LinkedHashMap<>();
        if (configIds == null || configIds.isEmpty()) {
            return result;
        }
        for (Long configId : configIds) {
            // 四席终审 P1 回修(2026-10-12)：F9 校验须在缓存读写之前（防越权命中缓存+失败投毒），
            // 但校验失败改为单条失败 VO + continue——不改成异常上抛（一条已删/无权的 configId
            // 会穿透整批，前端把全部卡片误标"查询失败"；实测轮询载荷混有跨租户 id，非超管每轮必撞）。
            // 失败不写缓存（continue 在缓存写之前），保持 javadoc「单条失败仅记录不中断」语义。
            try {
                loadAccessibleConfig(configId);
            } catch (Exception e) {
                result.put(configId, new DcsAiBalanceVo(false,
                    StrUtil.blankToDefault(e.getMessage(), "无权访问或配置不存在"), List.of()));
                continue;
            }
            String cacheKey = "dcs:ai:balance:" + configId;
            DcsAiBalanceVo cached = null;
            // 缓存读/写异常不冲整批（终审 P2）：Redis 抖动降级为直查厂商
            try {
                DcsAiBalanceVo hit = TenantHelper.ignore(() ->
                    org.dromara.common.redis.utils.RedisUtils.getCacheObject(cacheKey));
                cached = hit;
            } catch (Exception cacheEx) {
                log.warn("[batchSavedBalances] 余额缓存读失败, 直查厂商: configId={}", configId, cacheEx);
            }
            if (cached != null) {
                result.put(configId, cached);
                continue;
            }
            DcsAiBalanceVo vo;
            try {
                vo = probeSavedBalance(configId, null);
            } catch (Exception e) {
                vo = new DcsAiBalanceVo(false, StrUtil.blankToDefault(e.getMessage(), "余额查询失败"), List.of());
            }
            final DcsAiBalanceVo toCache = vo;
            try {
                TenantHelper.ignore(() -> {
                    org.dromara.common.redis.utils.RedisUtils.setCacheObject(
                        cacheKey, toCache, java.time.Duration.ofSeconds(30));
                    return null;
                });
            } catch (Exception cacheEx) {
                log.warn("[batchSavedBalances] 余额缓存写失败(忽略): configId={}", configId, cacheEx);
            }
            result.put(configId, vo);
        }
        return result;
    }

    /** 取当前租户启用的 AI 配置(任意用途, 缺省行为) */
    @Override
    public DcsAiConfig currentTenantEnabledConfig() {
        return currentTenantEnabledConfig(null);
    }

    /** 取当前租户指定用途启用的 AI 配置(CHAT/EMBEDDING 隔离), 不存在抛业务异常 */
    @Override
    public DcsAiConfig currentTenantEnabledConfig(String useType) {
        // J 批一期(2026-10-08)：委托单点解析器——此前助手路按 configId 升序、单发路按
        // updateTime 倒序各查各的，同租户多条启用时两路选不同行（dev 000000 实锤 6 条启用）。
        // HTTP 路沿袭原语义抛 ServiceException（调用方 catch 该类型降级）
        return resolveEnabledConfig(LoginHelper.getTenantId(), useType);
    }

    /**
     * 单点解析器（J 批一期，2026-10-08；批3 星标化改造）：全系统唯一的「租户当前生效 AI 配置」口径。
     *
     * <p>批3(Q4:A) 起选择序=①★默认行（is_default='1' 且可用）→②回退任意可用行
     * （update_time DESC, config_id DESC——星空缺时管理员最近动过的可用行生效，兼容
     * 「从未点星」的存量租户）。enabled 已是纯状态位（可用性），不再是选择器。
     * 含 CNY 闸（拍板 A 上收）：钱包仅支持人民币计费，非 CNY 价格行的模型全系统统一拒
     * （助手路/单发路/嵌入路同闸）；唯一豁免=FIXED_PER_CALL 且按次单价显式为 0
     * （真免费模型，「免费」由单价 0 表达）。</p>
     *
     * <p>异常口径：ServiceException（HTTP 单发路调用方按该类型降级捕获）。助手路/
     * MQ 路由调用方自行转译为各自的专用异常。</p>
     */
    @Override
    public DcsAiConfig resolveEnabledConfig(String tenantId, String useType) {
        // ① ★默认行优先（partial 索引保证每租户每用途至多一颗星；del_flag 由 MP 逻辑删除自动追加）
        DcsAiConfig config = TenantHelper.ignore(() -> aiConfigMapper.selectOne(
            new LambdaQueryWrapper<DcsAiConfig>()
                .eq(DcsAiConfig::getTenantId, tenantId)
                .eq(DcsAiConfig::getEnabled, ENABLED)
                .eq(DcsAiConfig::getIsDefault, "1")
                // useType null=兼容旧 no-arg 语义（任意用途取一条）
                .eq(useType != null, DcsAiConfig::getUseType, useType)
                .last("limit 1")));
        if (config == null) {
            // ② 星空缺回退：任意可用行按最近动过排序（Q11 优先级链的接口默认兜底段）
            config = TenantHelper.ignore(() -> aiConfigMapper.selectOne(
                new LambdaQueryWrapper<DcsAiConfig>()
                    .eq(DcsAiConfig::getTenantId, tenantId)
                    .eq(DcsAiConfig::getEnabled, ENABLED)
                    .eq(useType != null, DcsAiConfig::getUseType, useType)
                    .orderByDesc(DcsAiConfig::getUpdateTime)
                    .orderByDesc(DcsAiConfig::getConfigId)
                    .last("limit 1")));
        }
        if (config == null) {
            throw new ServiceException(useType == null
                ? "租户 " + tenantId + " 未启用的 AI 模型配置"
                : "租户 " + tenantId + " 未启用该用途(" + useType + ")的 AI 模型配置");
        }
        enforceCnyGate(config);
        hydrate(config);
        return config;
    }

    /**
     * 后台链配置解析（业务反馈批1015c Q1 拍板A）：后台默认槽（dcs_assistant_model_setting.
     * backend_default_config_id，助手管理页「后台 AI 默认」下拉）→ resolveConfigById
     * （行存在/租户匹配/可用 + CNY 闸）；槽未设（null/0）或指向失效（停用/删行/价格缺失）
     * 静默回退 {@link #resolveEnabledConfig}（★默认→最近可用）——后台功能可用性优先于
     * 槽指向，坏槽不炸功能（warn 留痕可排查，三审 P2：debug 在生产日志级别下不可见）。
     * 存量租户槽空=行为与改造前完全一致。
     */
    @Override
    public DcsAiConfig resolveBackendConfig(String tenantId) {
        try {
            Long backendDefault = backendDefaultMapper.findBackendDefault(tenantId);
            if (backendDefault != null && backendDefault > 0) {
                return resolveConfigById(tenantId, backendDefault);
            }
        } catch (Exception e) {
            log.warn("[AiConfig] 后台默认槽失效, 回退接口默认 tenant={}: {}", tenantId, e.getMessage());
        }
        return resolveEnabledConfig(tenantId, "CHAT");
    }

    /** 同 {@link #resolveBackendConfig(String)}，当前登录租户（AiGateway HTTP 路用；TenantHelper 与 Q10 同日口径对齐） */
    @Override
    public DcsAiConfig resolveBackendConfig() {
        return resolveBackendConfig(TenantHelper.getTenantId());
    }

    /** 业务反馈批1015b Q2：最便宜启用行（追问侧车轻快模型）。null=无可用（调用方回退默认链），闸不过也返回 null 不抛 */
    @Override
    public DcsAiConfig resolveCheapestEnabledConfig(String tenantId, String useType) {
        DcsAiConfig config = TenantHelper.ignore(() -> aiConfigMapper.selectOne(
            new LambdaQueryWrapper<DcsAiConfig>()
                .eq(DcsAiConfig::getTenantId, tenantId)
                .eq(DcsAiConfig::getEnabled, ENABLED)
                .eq(useType != null, DcsAiConfig::getUseType, useType)
                // 倍率是项目自己的档位信号（免费×1/flash×2/旗舰×4~6）：倍率低=又轻又省，
                // NULL 视同 1 且 PG ASC 默认 NULLS LAST 天然排后；同倍率比按次单价，再比最近动过
                .orderByAsc(DcsAiConfig::getCreditMultiplier)
                .orderByAsc(DcsAiConfig::getUnitPricePerCall)
                .orderByDesc(DcsAiConfig::getUpdateTime)
                .orderByDesc(DcsAiConfig::getConfigId)
                .last("limit 1")));
        if (config == null) {
            return null;
        }
        try {
            enforceCnyGate(config);
        } catch (ServiceException e) {
            return null;
        }
        hydrate(config);
        return config;
    }

    /** 按主键解析指定配置行（批3 Q11 助手默认槽供值）：存在/租户/可用校验 + 同一 CNY 闸 */
    @Override
    public DcsAiConfig resolveConfigById(String tenantId, Long configId) {
        DcsAiConfig config = TenantHelper.ignore(() -> aiConfigMapper.selectById(configId));
        if (config == null || !config.getTenantId().equals(tenantId)) {
            throw new ServiceException("指定的 AI 模型配置不存在");
        }
        if (!ENABLED.equals(config.getEnabled())) {
            throw new ServiceException("指定的 AI 模型配置已停用");
        }
        enforceCnyGate(config);
        hydrate(config);
        return config;
    }

    /**
     * CNY 闸（拍板 A 全系统统一）：钱包仅支持人民币计费，非 CNY 价格行拒；
     * 唯一豁免=FIXED_PER_CALL 且按次单价显式为 0（真免费由单价 0 表达）。
     */
    private void enforceCnyGate(DcsAiConfig config) {
        // FIXED_PER_CALL 且单价 0 = 真免费豁免（与 AiBillingCalculator.fixed 同源语义）
        boolean fixedFreeTier = BILLING_MODE_FIXED_PER_CALL.equalsIgnoreCase(
            config.getBillingMode() == null ? "" : config.getBillingMode())
            && config.getUnitPricePerCall() != null && config.getUnitPricePerCall().signum() == 0;
        if (!fixedFreeTier) {
            DcsAiModelPrice price;
            try {
                price = TenantHelper.ignore(() -> modelPriceService.findEffectivePrice(
                    config.getTenantId(), config.getProvider(), config.getModelName()));
            } catch (Exception e) {
                // 批8-3 回修（m3 P1-3）：价格目录 PG 闪断时降级为人话拒绝（与 enrichWithPrice 的
                // 容忍口径不同——这是 AGUI 每请求必经的闸，裸 DataAccessException 会变 500）
                log.warn("[CnyGate] 价格查询失败, 降级拒绝 tenant={} model={}: {}",
                    config.getTenantId(), config.getModelName(), e.getMessage());
                throw new ServiceException("模型 " + config.getModelName() + " 价格暂时查不到，请稍后重试");
            }
            if (price == null || !"CNY".equalsIgnoreCase(price.getCurrency())) {
                throw new ServiceException("模型 " + config.getModelName()
                    + " 未配置人民币价格，暂不能使用（钱包仅支持人民币计费），请管理员在「AI 配置」里切换模型");
            }
        }
    }

    /** 设为默认(批3 换星语义): 同租户同 use_type 旧默认摘星, 目标行置星——不动 enabled(状态与默认正交) */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Boolean activate(Long configId) {
        try {
            return TenantHelper.ignore(() -> {
                DcsAiConfig target = aiConfigMapper.selectById(configId);
                if (target == null) {
                    throw new ServiceException("AI配置不存在");
                }
                // m3 七审 P1-7：去 TenantHelper 匹配（超管动态租户下 getTenantId 与行租户错位
                // =「配置不存在或租户不匹配」409 死按钮），改按行租户访问校验
                // （同 testConnection/updateByBo 的 ensureCanAccessAiConfig 口径：本租户或超管放行）
                ensureCanAccessAiConfig(LoginHelper.getTenantId(), target.getTenantId(), LoginHelper.isSuperAdmin());
                // 星标只指可用行（Review Focus ⑤）：停用行点星=「默认指向一个调不动的模型」，
                // 语义矛盾；resolver 虽有回退兜底，入口直接拒绝防呆
                if (!ENABLED.equals(target.getEnabled())) {
                    throw new ServiceException("该配置当前已停用，请先恢复可用再设为默认");
                }
                // 1. 同租户同 use_type 的其他配置全部摘星(按用途分组隔离, CHAT/EMBEDDING 互不影响)
                enforceUniqueDefault(target.getTenantId(), target.getUseType(), configId);
                // 2. 目标配置置星
                DcsAiConfig starPatch = new DcsAiConfig();
                starPatch.setConfigId(configId);
                starPatch.setIsDefault("1");
                aiConfigMapper.updateById(starPatch);
                return true;
            });
        } catch (DuplicateKeyException e) {
            // 并发换星撞 uk_dcs_ai_config_tenant_default 部分唯一索引（摘星与置星非原子窗），
            // 转人话让重试
            throw new ServiceException("并发操作冲突：另一管理员刚切换了同租户同用途的默认模型，请刷新列表后重试");
        }
    }

    /**
     * 唯一默认守卫（批3 星标化）：同租户同 use_type 除 keepConfigId 外全部摘星。
     * 置 NULL 必须走 LambdaUpdateWrapper.set——MP 对 update 实体 null 字段默认不进 SET。
     */
    private void enforceUniqueDefault(String tenantId, String useType, Long keepConfigId) {
        String effectiveUseType = useType != null ? useType : "CHAT";
        aiConfigMapper.update(null, new LambdaUpdateWrapper<DcsAiConfig>()
            .set(DcsAiConfig::getIsDefault, null)
            .eq(DcsAiConfig::getTenantId, tenantId)
            .eq(DcsAiConfig::getUseType, effectiveUseType)
            .eq(DcsAiConfig::getIsDefault, "1")
            .ne(DcsAiConfig::getConfigId, keepConfigId));
    }

    /** 批量健康检查某租户 AI 配置(单条失败仅记录不中断；业务批4 Q17-A：可选 configIds 子集，空=全部) */
    @Override
    public List<DcsAiTestResultVo> batchHealthCheck(String tenantId, List<Long> configIds) {
        if (StrUtil.isBlank(tenantId)) tenantId = LoginHelper.getTenantId();
        List<DcsAiConfig> configs = aiConfigMapper.selectList(new LambdaQueryWrapper<DcsAiConfig>()
            .eq(DcsAiConfig::getTenantId, tenantId)
            // 业务批4 Q17-A（2026-10-13 拍板）：按 configIds 子集体检（null/空集合=全部，兼容旧调用）
            .in(configIds != null && !configIds.isEmpty(), DcsAiConfig::getConfigId, configIds));
        List<DcsAiTestResultVo> results = new java.util.ArrayList<>();
        for (DcsAiConfig c : configs) {
            try {
                DcsAiTestResultVo r = testConnection(c.getConfigId());
                r.setConfigId(c.getConfigId());
                results.add(r);
            } catch (Exception e) {
                DcsAiTestResultVo fail = new DcsAiTestResultVo();
                fail.setConfigId(c.getConfigId());
                fail.setSuccess(false);
                fail.setMessage(c.getModelName() + ": " + e.getMessage());
                results.add(fail);
            }
        }
        return results;
    }

    /** 构造分页查询条件(按租户/厂商/启用/模型名过滤) */
    private LambdaQueryWrapper<DcsAiConfig> buildWrapper(DcsAiConfigBo bo) {
        LambdaQueryWrapper<DcsAiConfig> w = new LambdaQueryWrapper<>();
        w.eq(StrUtil.isNotBlank(bo.getTenantId()), DcsAiConfig::getTenantId, bo.getTenantId());
        w.eq(StrUtil.isNotBlank(bo.getProvider()), DcsAiConfig::getProvider, bo.getProvider());
        w.eq(StrUtil.isNotBlank(bo.getEnabled()), DcsAiConfig::getEnabled, bo.getEnabled());
        w.like(StrUtil.isNotBlank(bo.getModelName()), DcsAiConfig::getModelName, bo.getModelName());
        w.orderByAsc(DcsAiConfig::getTenantId).orderByAsc(DcsAiConfig::getProvider);
        return w;
    }

    /** 规范化配置 BO 并校验合法性(厂商/temperature/超时/上下文窗口/计费模式/API Key) */
    private void normalize(DcsAiConfigBo bo, boolean add) {
        if (!DcsAiProvider.supported(bo.getProvider())) {
            throw new ServiceException("暂不支持该模型厂商");
        }
        bo.setBaseUrl(StrUtil.removeSuffix(StrUtil.trim(bo.getBaseUrl()), "/"));
        bo.setModelName(StrUtil.trim(bo.getModelName()));
        // 批3(Q4:A): 新增缺省=可用（配好即用，不再有「唯一启用」约束）；默认星标与可用性解耦，
        // 新增行不带星（自动路径仍解析到既有★或最近可用行），换星走 activate。
        // 编辑未传 enabled 时保持原值（updateById 忽略 null）。
        bo.setEnabled(StrUtil.blankToDefault(bo.getEnabled(), add ? ENABLED : bo.getEnabled()));
        if (bo.getTemperature() == null) {
            bo.setTemperature(0.2D);
        }
        if (bo.getTimeoutSeconds() == null) {
            bo.setTimeoutSeconds(60);
        }
        if (bo.getContextWindow() == null) {
            bo.setContextWindow(128000);
        }
        if (bo.getTemperature() < 0 || bo.getTemperature() > 2) {
            throw new ServiceException("temperature 必须在 0 到 2 之间");
        }
        if (bo.getTimeoutSeconds() < 5 || bo.getTimeoutSeconds() > 300) {
            throw new ServiceException("超时时间必须在 5 到 300 秒之间");
        }
        if (bo.getContextWindow() < 1024 || bo.getContextWindow() > 2_000_000) {
            throw new ServiceException("上下文窗口必须在 1024 到 2000000 token 之间");
        }
        // 批C 回修（glm53 P1-1 双保险）：maxTokens 范围与 BO 注解同限——注解只盖
        // @Validated(Add/Edit) 端点，内部调用/历史调用方绕过控制器时这道兜底
        if (bo.getMaxTokens() != null && (bo.getMaxTokens() < 256 || bo.getMaxTokens() > 131072)) {
            throw new ServiceException("最大输出必须在 256 到 131072 token 之间");
        }
        bo.setBalanceQueryEnabled(StrUtil.blankToDefault(bo.getBalanceQueryEnabled(), ENABLED));
        bo.setBillingMode(StrUtil.blankToDefault(bo.getBillingMode(), BILLING_MODE_OFFICIAL_TOKEN));
        // 业务批4 Q4：白名单放行第三种收法 OFFICIAL_PLUS_FEE（官方 token 价＋每次固定服务费）
        if (!BILLING_MODE_OFFICIAL_TOKEN.equals(bo.getBillingMode())
            && !BILLING_MODE_FIXED_PER_CALL.equals(bo.getBillingMode())
            && !BILLING_MODE_OFFICIAL_PLUS_FEE.equals(bo.getBillingMode())) {
            // 批I 零技术词：旧文案英文枚举直出，业务人员看不懂该填什么
            throw new ServiceException("计费模式无效：只能选「按用量计费」「按次计费」或「官方价＋每次服务费」");
        }
        if (bo.getServiceFeeRate() == null) {
            bo.setServiceFeeRate(BigDecimal.ZERO);
        }
        if (bo.getServiceFeeRate().compareTo(BigDecimal.ZERO) < 0 || bo.getServiceFeeRate().compareTo(new BigDecimal("1000")) > 0) {
            throw new ServiceException("服务技术费率必须在 0 到 1000 之间");
        }
        // 批B: API Key 是否必填改由 upsertAccessFromConfigBo 判定——该厂商已有接入行则加模型免填,
        // 首次配置必须填(引导语指向「同厂加模型不用再填」)
    }

    /** 规范化临时探测 BO 字段并校验温度/超时合法性 */
    private void normalizeProbe(DcsAiProbeBo bo) {
        if (!DcsAiProvider.supported(bo.getProvider())) {
            throw new ServiceException("暂不支持该模型厂商");
        }
        bo.setBaseUrl(StrUtil.removeSuffix(StrUtil.trim(bo.getBaseUrl()), "/"));
        bo.setApiKey(StrUtil.trim(bo.getApiKey()));
        bo.setModelName(StrUtil.trim(bo.getModelName()));
        if (bo.getTimeoutSeconds() == null) {
            bo.setTimeoutSeconds(60);
        }
        if (bo.getTemperature() == null) {
            bo.setTemperature(0.2D);
        }
        if (bo.getTimeoutSeconds() < 5 || bo.getTimeoutSeconds() > 300) {
            throw new ServiceException("超时时间必须在 5 到 300 秒之间");
        }
        if (bo.getTemperature() < 0 || bo.getTemperature() > 2) {
            throw new ServiceException("temperature 必须在 0 到 2 之间");
        }
    }

    /**
     * SSRF 防御: probe 请求的 baseUrl 必须命中指定 provider 的预置域名白名单 (B1, 见
     * document/DCS_AUDIT_2026_06_22.md)。拒绝内网/环回/链路本地/非 https, 防止探测内网或
     * 通过 bearerAuth 投递 API Key 到攻击者服务器。
     */
    private void assertBaseUrlAllowed(DcsAiProbeBo bo) {
        DcsAiProvider provider;
        try {
            provider = DcsAiProvider.valueOf(bo.getProvider().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ServiceException("暂不支持该模型厂商");
        }
        if (!DcsAiProviderProbeClient.isBaseUrlAllowed(provider, bo.getBaseUrl())) {
            // 批I 零技术词：旧文案 baseUrl 技术词直出
            throw new ServiceException("接口地址不在该厂商允许的范围内（或指向内网地址），已拒绝自动探测");
        }
    }

    /** 校验同租户+厂商+模型名唯一(排除自身)。批B 多模型解禁: 同厂多行合法, 同厂同模型防重 */
    private void checkTenantProviderModelUnique(Long configId, String tenantId, String provider, String modelName) {
        DcsAiConfig exists = aiConfigMapper.selectOne(new LambdaQueryWrapper<DcsAiConfig>()
            .eq(DcsAiConfig::getTenantId, tenantId)
            .eq(DcsAiConfig::getProvider, provider)
            .eq(DcsAiConfig::getModelName, modelName), false);
        if (exists != null && !Objects.equals(exists.getConfigId(), configId)) {
            throw new ServiceException("该厂商下已存在同名模型 " + provider + "/" + modelName + "，请直接编辑那一行或换个模型名");
        }
    }

    /** mask 配置 VO 的 API Key 并补全 providerName */
    private void mask(DcsAiConfigVo vo) {
        vo.setApiKeyMask(maskApiKeyForDisplay(vo.getApiKey()));
        vo.setApiKey(MASKED_KEY);
        // Q7: 顺便填充 providerName, 前端免字典映射
        if (vo.getProvider() != null) {
            try {
                DcsAiProvider p = DcsAiProvider.valueOf(vo.getProvider());
                vo.setProviderName(p.getLabel());
            } catch (IllegalArgumentException e) {
                // 未知 provider(自定义/历史数据), 保留原 provider 字符串作为 name
                vo.setProviderName(vo.getProvider());
            }
        }
    }

    /** 加载并校验当前用户可访问的配置实体(跨租户拒绝; 批B 起出参已 hydrate 接入层钥匙) */
    private DcsAiConfig loadAccessibleConfig(Long configId) {
        DcsAiConfig config = TenantHelper.ignore(() -> aiConfigMapper.selectById(configId));
        if (config == null) {
            throw new ServiceException("AI配置不存在");
        }
        ensureCanAccessAiConfig(LoginHelper.getTenantId(), config.getTenantId(), LoginHelper.isSuperAdmin());
        hydrate(config);
        return config;
    }

    /** 把 API Key 脱敏为前 7 位 + **** + 后 3 位的形式(短 key 直接返回 ****) */
    public static String maskApiKeyForDisplay(String apiKey) {
        if (StrUtil.isBlank(apiKey)) {
            return MASKED_KEY;
        }
        String trimmed = StrUtil.trim(apiKey);
        if (trimmed.length() <= 10) {
            return MASKED_KEY;
        }
        return trimmed.substring(0, 7) + MASKED_KEY + trimmed.substring(trimmed.length() - 3);
    }

    /** 把已保存配置转换为探测 BO(无 override) */
    public static DcsAiProbeBo toProbeBoForSavedConfig(DcsAiConfig config) {
        return toProbeBoForSavedConfig(config, null);
    }

    /** 把已保存配置转换为探测 BO, 并用 overrideBo 覆盖非空字段 */
    public static DcsAiProbeBo toProbeBoForSavedConfig(DcsAiConfig config, DcsAiProbeBo overrideBo) {
        DcsAiProbeBo bo = new DcsAiProbeBo();
        bo.setProvider(config.getProvider());
        bo.setBaseUrl(StrUtil.removeSuffix(StrUtil.trim(config.getBaseUrl()), "/"));
        bo.setApiKey(StrUtil.trim(config.getApiKey()));
        bo.setModelName(StrUtil.trim(config.getModelName()));
        bo.setTemperature(config.getTemperature() == null ? 0.2D : config.getTemperature());
        bo.setTimeoutSeconds(config.getTimeoutSeconds() == null ? 60 : config.getTimeoutSeconds());
        if (overrideBo != null) {
            if (StrUtil.isNotBlank(overrideBo.getProvider())) {
                bo.setProvider(StrUtil.trim(overrideBo.getProvider()));
            }
            if (StrUtil.isNotBlank(overrideBo.getBaseUrl())) {
                bo.setBaseUrl(StrUtil.removeSuffix(StrUtil.trim(overrideBo.getBaseUrl()), "/"));
            }
            if (StrUtil.isNotBlank(overrideBo.getModelName())) {
                bo.setModelName(StrUtil.trim(overrideBo.getModelName()));
            }
            if (overrideBo.getTemperature() != null) {
                bo.setTemperature(overrideBo.getTemperature());
            }
            if (overrideBo.getTimeoutSeconds() != null) {
                bo.setTimeoutSeconds(overrideBo.getTimeoutSeconds());
            }
        }
        return bo;
    }

    /**
     * 校验当前用户能否访问指定 AI 配置 (F9, 见 document/DCS_AUDIT_2026_06_22.md)。
     *
     * <p>背景: queryById/testConnection 用 TenantHelper.ignore 跨租户读, 普通租户管理员
     * 可枚举 configId 读他人配置。修复: 抽纯函数, 在 service 入口校验。</p>
     *
     * <p>语义: 超管(superadmin) 可跨租户维护; 普通用户只能访问 tenantId 匹配自己的 config;
     * 当前用户无 tenantId 上下文 → 一律拒绝(防绕过)。</p>
     */
    public static void ensureCanAccessAiConfig(String myTenantId, String configTenantId, boolean isSuperAdmin) {
        if (isSuperAdmin) {
            return;
        }
        if (myTenantId == null) {
            throw new ServiceException("无租户上下文, 禁止访问 AI 配置");
        }
        if (configTenantId == null || !myTenantId.equals(configTenantId)) {
            throw new ServiceException("无权访问其他租户的 AI 配置");
        }
    }
}
