package com.smartdock.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.DcsAiModelPrice;
import com.smartdock.domain.bo.DcsAiModelPriceBo;
import com.smartdock.domain.bo.DcsAiModelPriceQueryBo;
import com.smartdock.domain.vo.DcsAiModelPriceVo;
import com.smartdock.enums.DcsAiProvider;
import com.smartdock.mapper.DcsAiModelPriceMapper;
import com.smartdock.service.IDcsAiModelPriceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI 模型价格配置 Service 实现。
 *
 * @author smartdock
 */
@Service
@RequiredArgsConstructor
public class DcsAiModelPriceServiceImpl implements IDcsAiModelPriceService {

    private static final String GLOBAL_TENANT_ID = "000000";
    private static final String ENABLED = "1";
    /** Q6-A：models.dev 官方价格库（USD / 百万 token） */
    private static final String MODELS_DEV_API = "https://models.dev/api.json";
    private static final String SOURCE_MODELS_DEV = "MODELS_DEV";
    private static final String SOURCE_IMPORT = "IMPORT";
    private static final String SOURCE_MANUAL = "MANUAL";
    /** provider 枚举 → models.dev 的 provider key 候选（国内站优先） */
    private static final Map<String, List<String>> MODELS_DEV_PROVIDERS = Map.ofEntries(
        Map.entry("DEEPSEEK", List.of("deepseek")),
        Map.entry("OPENAI", List.of("openai")),
        Map.entry("QWEN", List.of("alibaba-cn", "alibaba")),
        Map.entry("ZHIPU", List.of("zhipuai", "zai")),
        Map.entry("KIMI", List.of("moonshotai-cn", "moonshotai")),
        Map.entry("MINIMAX", List.of("minimax-cn", "minimax")),
        Map.entry("SILICONFLOW", List.of("siliconflow-cn", "siliconflow")),
        Map.entry("STEPFUN", List.of("stepfun", "stepfun-ai")),
        Map.entry("OPENROUTER", List.of("openrouter")),
        Map.entry("OPENCODE", List.of("opencode", "opencode-go"))
    );

    private final DcsAiModelPriceMapper priceMapper;

    /** 分页查询 AI 模型价格(忽略租户隔离,超管可看全部) */
    @Override
    public TableDataInfo<DcsAiModelPriceVo> queryPageList(DcsAiModelPriceQueryBo bo, PageQuery pageQuery) {
        Page<DcsAiModelPriceVo> page = TenantHelper.ignore(() -> priceMapper.selectVoPage(pageQuery.build(), buildWrapper(bo)));
        return TableDataInfo.build(page);
    }

    /** 查询单条价格配置(不存在抛业务异常) */
    @Override
    public DcsAiModelPriceVo queryById(Long priceId) {
        DcsAiModelPriceVo vo = TenantHelper.ignore(() -> priceMapper.selectVoById(priceId));
        if (vo == null) {
            throw new ServiceException("AI模型价格不存在");
        }
        return vo;
    }

    /** 新增价格配置(规范化 + 厂商校验 + 唯一性校验) */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(DcsAiModelPriceBo bo) {
        normalize(bo);
        DcsAiModelPrice entity = MapstructUtils.convert(bo, DcsAiModelPrice.class);
        // Q6-A：人工维护的价格打来源标记；manualOverride 由表单决定（缺省锁定，防同步覆盖）
        entity.setPriceSource(SOURCE_MANUAL);
        entity.setManualOverride(StrUtil.blankToDefault(bo.getManualOverride(), "1"));
        TenantHelper.ignore(() -> {
            checkUnique(null, entity);
            priceMapper.insert(entity);
        });
        return entity.getPriceId();
    }

    /** 更新价格配置(校验存在 + 唯一性) */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(DcsAiModelPriceBo bo) {
        normalize(bo);
        return TenantHelper.ignore(() -> {
            DcsAiModelPrice origin = priceMapper.selectById(bo.getPriceId());
            if (origin == null) {
                throw new ServiceException("AI模型价格不存在");
            }
            DcsAiModelPrice entity = MapstructUtils.convert(bo, DcsAiModelPrice.class);
            entity.setPriceSource(SOURCE_MANUAL);
            entity.setManualOverride(StrUtil.blankToDefault(bo.getManualOverride(), "1"));
            checkUnique(entity.getPriceId(), entity);
            return priceMapper.updateById(entity) > 0;
        });
    }

    /** 批量删除价格配置(物理上标记 delFlag) */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        return TenantHelper.ignore(() -> priceMapper.deleteByIds(ids) > 0);
    }

    /** 解析生效价格:租户级 > 全局,精确模型 > 通配 "*" */
    @Override
    public DcsAiModelPrice findEffectivePrice(String tenantId, String provider, String modelName) {
        String resolvedTenantId = StrUtil.blankToDefault(tenantId, GLOBAL_TENANT_ID);
        String resolvedModel = StrUtil.blankToDefault(StrUtil.trim(modelName), "*");
        List<DcsAiModelPrice> rows = TenantHelper.ignore(() -> priceMapper.selectList(new LambdaQueryWrapper<DcsAiModelPrice>()
            .in(DcsAiModelPrice::getTenantId, resolvedTenantId, GLOBAL_TENANT_ID)
            .eq(DcsAiModelPrice::getProvider, StrUtil.trim(provider))
            .and(w -> w.eq(DcsAiModelPrice::getModelName, resolvedModel).or().eq(DcsAiModelPrice::getModelName, "*"))
            .eq(DcsAiModelPrice::getEnabled, ENABLED)
            .eq(DcsAiModelPrice::getDelFlag, "0")));
        return rows.stream()
            .min(Comparator
                .comparing((DcsAiModelPrice item) -> Objects.equals(item.getTenantId(), resolvedTenantId) ? 0 : 1)
                .thenComparing(item -> Objects.equals(item.getModelName(), resolvedModel) ? 0 : 1))
            .orElse(null);
    }

    /** 直接持久化插入实体(供初始化/迁移脚本使用,跳过 BO 校验) */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void directInsert(DcsAiModelPrice entity) {
        normalizeEntity(entity);
        TenantHelper.ignore(() -> priceMapper.insert(entity));
    }

    /** 直接持久化更新实体(供初始化/迁移脚本使用) */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void directUpdate(DcsAiModelPrice entity) {
        normalizeEntity(entity);
        TenantHelper.ignore(() -> priceMapper.updateById(entity));
    }

    // ==================== Q6-A：models.dev 同步 / JSON 导入 ====================

    @Override
    public Map<String, Object> syncFromModelsDev(String tenantId) {
        String tid = StrUtil.blankToDefault(tenantId, LoginHelper.getTenantId());
        if (StrUtil.isBlank(tid)) {
            tid = GLOBAL_TENANT_ID;
        }
        String body;
        try (HttpResponse response = HttpRequest.get(MODELS_DEV_API)
            .timeout(20_000)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (compatible; SmartDock-PriceSync/1.0)")
            .execute()) {
            if (!response.isOk()) {
                throw new ServiceException("models.dev 接口异常（HTTP " + response.getStatus() + "）");
            }
            body = response.body();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("models.dev 请求失败: " + e.getMessage());
        }
        Map<String, Object> root = JsonUtils.parseMap(body);
        if (root == null || root.isEmpty()) {
            throw new ServiceException("models.dev 返回为空");
        }
        // 审查 P2：HTTP 抓取（最长 20s）不放进事务，避免占着连接等外网；
        // 落库部分走代理事务（同类调用需 getAopProxy 否则 @Transactional 静默失效）
        return org.dromara.common.core.utils.SpringUtils.getAopProxy(this).applyModelsDevRoot(tid, root);
    }

    /** models.dev 解析结果的落库段（事务边界；只由 {@link #syncFromModelsDev} 经代理调用） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> applyModelsDevRoot(String tid, Map<String, Object> root) {
        int[] counts = new int[3];
        for (Map.Entry<String, List<String>> entry : MODELS_DEV_PROVIDERS.entrySet()) {
            Map<String, Object> models = firstModels(root, entry.getValue());
            if (models == null || models.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Object> modelEntry : models.entrySet()) {
                if (!(modelEntry.getValue() instanceof Map<?, ?> model)
                    || !(model.get("cost") instanceof Map<?, ?> cost)) {
                    continue;
                }
                BigDecimal input = toDecimal(cost.get("input"));
                BigDecimal output = toDecimal(cost.get("output"));
                if (input == null || output == null) {
                    continue;
                }
                upsertSynced(tid, entry.getKey(), modelEntry.getKey(), input,
                    toDecimal(cost.get("cache_read")), output, counts);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "models.dev");
        out.put("inserted", counts[0]);
        out.put("updated", counts[1]);
        out.put("skipped", counts[2]);
        out.put("total", counts[0] + counts[1] + counts[2]);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importJson(String tenantId, Map<String, Object> payload) {
        if (payload == null || !(payload.get("prices") instanceof List<?> list)) {
            throw new ServiceException("导入格式错误：需要 {\"prices\":[{provider,modelName,inputPricePerMillion,outputPricePerMillion}]}");
        }
        String tid = StrUtil.blankToDefault(tenantId, LoginHelper.getTenantId());
        if (StrUtil.isBlank(tid)) {
            tid = GLOBAL_TENANT_ID;
        }
        int[] counts = new int[3];
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                counts[2]++;
                continue;
            }
            String provider = StrUtil.trim(text(row.get("provider")));
            String modelName = text(firstNonNull(row.get("modelName"), row.get("model_name"), row.get("model")));
            BigDecimal input = toDecimal(firstNonNull(row.get("inputPricePerMillion"), row.get("input")));
            BigDecimal output = toDecimal(firstNonNull(row.get("outputPricePerMillion"), row.get("output")));
            BigDecimal cached = toDecimal(firstNonNull(
                row.get("cachedInputPricePerMillion"), row.get("cachedInput"), row.get("cache_read")));
            if (StrUtil.isNotBlank(provider)) {
                provider = provider.toUpperCase();
            }
            if (!DcsAiProvider.supported(provider)
                || StrUtil.isBlank(modelName) || modelName.length() > 128
                || input == null || output == null
                || input.signum() < 0 || output.signum() < 0
                || (cached != null && cached.signum() < 0)) {
                // 坏行计入 skipped 而不是整批失败：手抄价格表难免个别行不合规
                counts[2]++;
                continue;
            }
            String currency = StrUtil.blankToDefault(text(row.get("currency")), "USD").toUpperCase();
            boolean manual = "1".equals(text(row.get("manualOverride"))) || Boolean.TRUE.equals(row.get("manualOverride"));
            upsertImported(tid, provider, modelName.trim(), currency, input, cached, output, manual, counts);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "import");
        out.put("inserted", counts[0]);
        out.put("updated", counts[1]);
        out.put("skipped", counts[2]);
        out.put("total", counts[0] + counts[1] + counts[2]);
        return out;
    }

    private Map<String, Object> firstModels(Map<String, Object> root, List<String> aliases) {
        for (String alias : aliases) {
            if (!(root.get(alias) instanceof Map<?, ?> provider)) {
                continue;
            }
            if (provider.get("models") instanceof Map<?, ?> models && !models.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) models;
                return typed;
            }
        }
        return null;
    }

    private void upsertSynced(String tenantId, String provider, String modelName, BigDecimal input,
                              BigDecimal cached, BigDecimal output, int[] counts) {
        DcsAiModelPrice existing = findRow(tenantId, provider, modelName);
        if (existing != null && "1".equals(existing.getManualOverride())) {
            counts[2]++;
            return;
        }
        Date now = new Date();
        if (existing == null) {
            DcsAiModelPrice row = new DcsAiModelPrice();
            row.setPriceId(IdWorker.getId());
            row.setTenantId(tenantId);
            row.setProvider(provider);
            row.setModelName(modelName);
            row.setCurrency("USD");
            row.setInputPricePerMillion(input);
            row.setCachedInputPricePerMillion(cached);
            row.setOutputPricePerMillion(output);
            row.setSourceUrl(MODELS_DEV_API);
            row.setEnabled(ENABLED);
            row.setPriceSource(SOURCE_MODELS_DEV);
            row.setManualOverride("0");
            row.setLastSyncedAt(now);
            row.setDelFlag("0");
            TenantHelper.ignore(() -> priceMapper.insert(row));
            counts[0]++;
            return;
        }
        // 条件更新：只覆盖仍未锁定的行——同步耗时可达数十秒，读到未锁、写前被人工锁定
        // 的窗口用 WHERE manual_override='0' 兜住（命中 0 行即计入跳过）
        int updated = TenantHelper.ignore(() -> priceMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DcsAiModelPrice>()
                .set(DcsAiModelPrice::getCurrency, "USD")
                .set(DcsAiModelPrice::getInputPricePerMillion, input)
                .set(DcsAiModelPrice::getCachedInputPricePerMillion, cached)
                .set(DcsAiModelPrice::getOutputPricePerMillion, output)
                .set(DcsAiModelPrice::getSourceUrl, MODELS_DEV_API)
                .set(DcsAiModelPrice::getPriceSource, SOURCE_MODELS_DEV)
                .set(DcsAiModelPrice::getManualOverride, "0")
                .set(DcsAiModelPrice::getLastSyncedAt, now)
                .eq(DcsAiModelPrice::getPriceId, existing.getPriceId())
                .eq(DcsAiModelPrice::getManualOverride, "0")));
        counts[updated > 0 ? 1 : 2]++;
    }

    private void upsertImported(String tenantId, String provider, String modelName, String currency,
                                BigDecimal input, BigDecimal cached, BigDecimal output, boolean manual, int[] counts) {
        DcsAiModelPrice existing = findRow(tenantId, provider, modelName);
        if (existing != null && "1".equals(existing.getManualOverride()) && !manual) {
            counts[2]++;
            return;
        }
        Date now = new Date();
        if (existing == null) {
            DcsAiModelPrice row = new DcsAiModelPrice();
            row.setPriceId(IdWorker.getId());
            row.setTenantId(tenantId);
            row.setProvider(provider);
            row.setModelName(modelName);
            row.setCurrency(currency);
            row.setInputPricePerMillion(input);
            row.setCachedInputPricePerMillion(cached);
            row.setOutputPricePerMillion(output);
            row.setEnabled(ENABLED);
            row.setPriceSource(SOURCE_IMPORT);
            row.setManualOverride(manual ? "1" : "0");
            row.setLastSyncedAt(now);
            row.setDelFlag("0");
            TenantHelper.ignore(() -> priceMapper.insert(row));
            counts[0]++;
            return;
        }
        existing.setCurrency(currency);
        existing.setInputPricePerMillion(input);
        existing.setCachedInputPricePerMillion(cached);
        existing.setOutputPricePerMillion(output);
        existing.setPriceSource(SOURCE_IMPORT);
        existing.setManualOverride(manual ? "1" : "0");
        existing.setLastSyncedAt(now);
        TenantHelper.ignore(() -> priceMapper.updateById(existing));
        counts[1]++;
    }

    private DcsAiModelPrice findRow(String tenantId, String provider, String modelName) {
        return TenantHelper.ignore(() -> priceMapper.selectOne(new LambdaQueryWrapper<DcsAiModelPrice>()
            .eq(DcsAiModelPrice::getTenantId, tenantId)
            .eq(DcsAiModelPrice::getProvider, provider)
            .eq(DcsAiModelPrice::getModelName, modelName)
            .eq(DcsAiModelPrice::getDelFlag, "0")
            .last("LIMIT 1")));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static Object firstNonNull(Object... values) {
        for (Object v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 规范化实体字段默认值(租户/币种/启用状态等) */
    private void normalizeEntity(DcsAiModelPrice entity) {
        entity.setTenantId(StrUtil.blankToDefault(entity.getTenantId(), GLOBAL_TENANT_ID));
        entity.setProvider(StrUtil.trim(entity.getProvider()));
        entity.setModelName(StrUtil.trim(entity.getModelName()));
        entity.setCurrency(StrUtil.blankToDefault(StrUtil.trim(entity.getCurrency()), "USD").toUpperCase());
        entity.setEnabled(StrUtil.blankToDefault(entity.getEnabled(), ENABLED));
        entity.setPriceSource(StrUtil.blankToDefault(entity.getPriceSource(), SOURCE_MANUAL));
        entity.setManualOverride(StrUtil.blankToDefault(entity.getManualOverride(), "1"));
    }

    /** 构造分页查询条件(非超管仅看本租户+全局,超管可按租户筛选) */
    private LambdaQueryWrapper<DcsAiModelPrice> buildWrapper(DcsAiModelPriceQueryBo bo) {
        DcsAiModelPriceQueryBo query = bo == null ? new DcsAiModelPriceQueryBo() : bo;
        LambdaQueryWrapper<DcsAiModelPrice> w = new LambdaQueryWrapper<>();
        if (!LoginHelper.isSuperAdmin()) {
            w.in(DcsAiModelPrice::getTenantId, currentTenantId(), GLOBAL_TENANT_ID);
        } else if (StrUtil.isNotBlank(query.getTenantId())) {
            w.eq(DcsAiModelPrice::getTenantId, query.getTenantId());
        }
        w.eq(StrUtil.isNotBlank(query.getProvider()), DcsAiModelPrice::getProvider, query.getProvider());
        w.like(StrUtil.isNotBlank(query.getModelName()), DcsAiModelPrice::getModelName, query.getModelName());
        w.eq(StrUtil.isNotBlank(query.getEnabled()), DcsAiModelPrice::getEnabled, query.getEnabled());
        w.eq(DcsAiModelPrice::getDelFlag, "0");
        w.orderByAsc(DcsAiModelPrice::getProvider).orderByAsc(DcsAiModelPrice::getModelName);
        return w;
    }

    /** 规范化 BO 并校验厂商、单价合法性 */
    private void normalize(DcsAiModelPriceBo bo) {
        bo.setTenantId(StrUtil.blankToDefault(bo.getTenantId(), currentTenantId()));
        bo.setProvider(StrUtil.trim(bo.getProvider()));
        bo.setModelName(StrUtil.trim(bo.getModelName()));
        bo.setCurrency(StrUtil.blankToDefault(StrUtil.trim(bo.getCurrency()), "USD").toUpperCase());
        bo.setEnabled(StrUtil.blankToDefault(bo.getEnabled(), ENABLED));
        if (!DcsAiProvider.supported(bo.getProvider())) {
            throw new ServiceException("暂不支持该模型厂商");
        }
        validatePrice("输入单价", bo.getInputPricePerMillion());
        validatePrice("缓存输入单价", bo.getCachedInputPricePerMillion());
        validatePrice("输出单价", bo.getOutputPricePerMillion());
    }

    /** 校验单价字段非负 */
    private void validatePrice(String label, BigDecimal value) {
        if (value != null && value.compareTo(BigDecimal.ZERO) < 0) {
            throw new ServiceException(label + "不能小于0");
        }
    }

    /** 校验同租户+厂商+模型名组合的唯一性(排除自身) */
    private void checkUnique(Long priceId, DcsAiModelPrice entity) {
        List<DcsAiModelPrice> rows = priceMapper.selectList(new LambdaQueryWrapper<DcsAiModelPrice>()
            .eq(DcsAiModelPrice::getTenantId, entity.getTenantId())
            .eq(DcsAiModelPrice::getProvider, entity.getProvider())
            .eq(DcsAiModelPrice::getModelName, entity.getModelName())
            .eq(DcsAiModelPrice::getDelFlag, "0"));
        boolean duplicated = rows.stream().anyMatch(item -> !Objects.equals(item.getPriceId(), priceId));
        if (duplicated) {
            throw new ServiceException("当前租户下该厂商模型价格已存在");
        }
    }

    /** 取当前租户 ID,空值回退到全局租户 */
    private String currentTenantId() {
        String tenantId = LoginHelper.getTenantId();
        return StrUtil.blankToDefault(tenantId, GLOBAL_TENANT_ID);
    }
}
