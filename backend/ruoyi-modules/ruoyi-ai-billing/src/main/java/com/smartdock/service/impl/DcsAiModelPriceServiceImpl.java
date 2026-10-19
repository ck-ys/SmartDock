package com.smartdock.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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

    /** 规范化实体字段默认值(租户/币种/启用状态等) */
    private void normalizeEntity(DcsAiModelPrice entity) {
        entity.setTenantId(StrUtil.blankToDefault(entity.getTenantId(), GLOBAL_TENANT_ID));
        entity.setProvider(StrUtil.trim(entity.getProvider()));
        entity.setModelName(StrUtil.trim(entity.getModelName()));
        entity.setCurrency(StrUtil.blankToDefault(StrUtil.trim(entity.getCurrency()), "USD").toUpperCase());
        entity.setEnabled(StrUtil.blankToDefault(entity.getEnabled(), ENABLED));
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
