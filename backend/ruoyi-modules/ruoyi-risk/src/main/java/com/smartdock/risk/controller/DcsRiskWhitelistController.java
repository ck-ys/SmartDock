package com.smartdock.risk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import com.smartdock.risk.domain.DcsRiskWhitelist;
import com.smartdock.risk.domain.bo.RiskWhitelistCommand;
import com.smartdock.risk.domain.bo.RiskWhitelistUpdateCommand;
import com.smartdock.risk.mapper.DcsRiskWhitelistMapper;
import com.smartdock.risk.service.RiskWhitelistPolicy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 风控白名单 Controller（从 DcsRiskController 拆出）。
 *
 * <p>职责：白名单（IP / USER 类型）的增删改查。命中白名单的事件不会进入风险评分。
 *
 * <p>路由前缀 {@code /dcs/risk} 与拆分前保持一致，前端零改动。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/dcs/risk")
@RequiredArgsConstructor
public class DcsRiskWhitelistController extends BaseController {

    /** 风控白名单表读写 Mapper（IP/USER 类型） */
    private final DcsRiskWhitelistMapper whitelistMapper;
    /** 风控白名单策略服务（创建前置校验） */
    private final RiskWhitelistPolicy riskWhitelistPolicy;

    /**
     * 分页查询白名单。
     *
     * @param listType  类型筛选：{@code IP} / {@code USER}，可选；为空则全部
     * @param pageQuery 分页参数
     * @return 分页的白名单列表
     */
    @SaCheckPermission(value = {"dcs:risk:whitelist:list", "dcs:risk:config"}, mode = SaMode.OR)
    @GetMapping("/whitelist")
    public TableDataInfo<DcsRiskWhitelist> whitelist(
        @RequestParam(required = false) String listType,
        PageQuery pageQuery
    ) {
        LambdaQueryWrapper<DcsRiskWhitelist> qw = new LambdaQueryWrapper<>();
        if (listType != null && !listType.isBlank()) qw.eq(DcsRiskWhitelist::getListType, listType);
        qw.orderByDesc(DcsRiskWhitelist::getCreateTime);
        return TableDataInfo.build(whitelistMapper.selectPage(pageQuery.build(), qw));
    }

    /**
     * 新增白名单项。
     *
     * <p>同一 (listType, value) 组合在租户内唯一；重复时抛 ServiceException。
     *
     * @param command 白名单字段（listType / value / reason / enabled）
     * @return 操作结果
     */
    @SaCheckPermission("dcs:risk:whitelist:edit")
    @Log(title = "风控白名单", businessType = BusinessType.INSERT)
    @PostMapping("/whitelist")
    public R<Void> addWhitelist(@Valid @RequestBody RiskWhitelistCommand command) {
        DcsRiskWhitelist entry = toWhitelist(command);
        riskWhitelistPolicy.prepareCreate(entry);
        Long exists = whitelistMapper.selectCount(new LambdaQueryWrapper<DcsRiskWhitelist>()
            .eq(DcsRiskWhitelist::getListType, entry.getListType())
            .eq(DcsRiskWhitelist::getValue, entry.getValue()));
        if (exists != null && exists > 0) {
            throw new ServiceException("白名单项已存在");
        }
        whitelistMapper.insert(entry);
        return R.ok();
    }

    /**
     * 更新白名单项（轻量更新，只改 reason / enabled，不改 listType/value）。
     *
     * <p>设计意图：白名单的 listType/value 在创建后语义不变（IP 还是 IP、值还是那个值），
     * 管理员日常维护主要改原因说明或启停。前端 el-switch 切换 enabled 时调用本接口，
     * 比删后重建体验更好。
     *
     * @param id      白名单 ID
     * @param command 可变字段（reason / enabled），null 字段不更新
     * @return 操作结果
     */
    @SaCheckPermission("dcs:risk:whitelist:edit")
    @Log(title = "风控白名单", businessType = BusinessType.UPDATE)
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/whitelist/{id}")
    public R<Void> updateWhitelist(@PathVariable Long id, @Valid @RequestBody RiskWhitelistUpdateCommand command) {
        DcsRiskWhitelist existing = whitelistMapper.selectById(id);
        if (existing == null) {
            throw new ServiceException("白名单项不存在");
        }
        LambdaUpdateWrapper<DcsRiskWhitelist> update = new LambdaUpdateWrapper<DcsRiskWhitelist>()
            .eq(DcsRiskWhitelist::getWhitelistId, id);
        if (command.reason() != null) {
            update.set(DcsRiskWhitelist::getReason, command.reason());
        }
        if (command.enabled() != null) {
            update.set(DcsRiskWhitelist::getEnabled, command.enabled());
        }
        int updated = whitelistMapper.update(null, update);
        if (updated == 0) {
            throw new ServiceException("白名单项不存在或已被删除");
        }
        return R.ok();
    }

    /**
     * 删除白名单项。
     *
     * @param id 白名单 ID
     * @return 操作结果
     */
    @SaCheckPermission("dcs:risk:whitelist:edit")
    @Log(title = "风控白名单", businessType = BusinessType.DELETE)
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/whitelist/{id}/delete")
    public R<Void> deleteWhitelist(@PathVariable Long id) {
        if (whitelistMapper.deleteById(id) == 0) {
            throw new ServiceException("白名单项不存在");
        }
        return R.ok();
    }

    // ============ 私有 helper ============

    private DcsRiskWhitelist toWhitelist(RiskWhitelistCommand command) {
        DcsRiskWhitelist entry = new DcsRiskWhitelist();
        entry.setListType(command.listType());
        entry.setValue(command.value());
        entry.setReason(command.reason());
        entry.setEnabled(command.enabled());
        return entry;
    }
}
