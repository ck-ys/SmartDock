package com.smartdock.risk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import com.smartdock.risk.domain.DcsRiskRule;
import com.smartdock.risk.domain.bo.RiskRuleCommand;
import com.smartdock.risk.mapper.DcsRiskRuleMapper;
import com.smartdock.risk.service.RiskRulePolicy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 风控规则配置 Controller（从 DcsRiskController 拆出）。
 *
 * <p>职责：风控规则的 CRUD + 单字段 enabled 切换。
 *
 * <p>路由前缀 {@code /dcs/risk} 与拆分前保持一致，前端零改动。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/dcs/risk")
@RequiredArgsConstructor
public class DcsRiskRuleController extends BaseController {

    /** 风控规则表读写 Mapper */
    private final DcsRiskRuleMapper ruleMapper;
    /** 风控规则策略服务（创建/更新前置校验、GLOBAL 规则删除保护） */
    private final RiskRulePolicy riskRulePolicy;

    /**
     * 查询全部风控规则列表（不分页，规则数量有限）。
     *
     * @return 按 ruleId 升序的规则列表
     */
    @SaCheckPermission(value = {"dcs:risk:rule:list", "dcs:risk:config"}, mode = SaMode.OR)
    @GetMapping("/rules")
    public R<List<DcsRiskRule>> listRules() {
        return R.ok(ruleMapper.selectList(new LambdaQueryWrapper<DcsRiskRule>()
            .orderByAsc(DcsRiskRule::getRuleId)));
    }

    /**
     * 全量更新一条风控规则（需回填所有字段）。
     *
     * <p>仅用于"行内编辑保存按钮"。如果只想切换启用状态，前端请改用
     * {@link #toggleRuleEnabled(Long, String)} 单字段接口，避免回填全部阈值权重。
     *
     * @param command 规则字段（record，所有 NotNull/NotBlank 字段必须填）
     * @return 操作结果
     */
    @SaCheckPermission("dcs:risk:rule:edit")
    @Log(title = "风控规则", businessType = BusinessType.UPDATE)
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/rules/update")
    public R<Void> updateRule(@Valid @RequestBody RiskRuleCommand command) {
        DcsRiskRule rule = toRule(command);
        if (rule.getRuleId() == null) return R.fail("ruleId 不能为空");
        DcsRiskRule existing = ruleMapper.selectById(rule.getRuleId());
        riskRulePolicy.prepareUpdate(rule, existing);
        ruleMapper.updateById(rule);
        return R.ok();
    }

    /**
     * 新增风控规则（Req 7.2）。
     *
     * <p>eventType 在同租户内唯一；重复时抛 ServiceException。
     *
     * @param command 规则字段
     * @return 操作结果
     */
    @SaCheckPermission("dcs:risk:rule:edit")
    @Log(title = "风控规则", businessType = BusinessType.INSERT)
    @PostMapping("/rules")
    public R<Void> addRule(@Valid @RequestBody RiskRuleCommand command) {
        DcsRiskRule rule = toRule(command);
        if (StringUtils.isBlank(rule.getEventType())) {
            throw new ServiceException("事件类型不能为空");
        }
        riskRulePolicy.prepareCreate(rule);
        Long exists = ruleMapper.selectCount(new LambdaQueryWrapper<DcsRiskRule>()
            .eq(DcsRiskRule::getEventType, rule.getEventType()));
        if (exists != null && exists > 0) {
            throw new ServiceException("事件类型已存在: " + rule.getEventType());
        }
        ruleMapper.insert(rule);
        return R.ok();
    }

    /**
     * 删除风控规则（Req 7.2）。GLOBAL 规则不可删，由 {@link RiskRulePolicy#assertDeletable} 拦截。
     *
     * @param ruleId 规则 ID
     * @return 操作结果
     */
    @SaCheckPermission("dcs:risk:rule:edit")
    @Log(title = "风控规则", businessType = BusinessType.DELETE)
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/rules/{ruleId}")
    public R<Void> deleteRule(@PathVariable Long ruleId) {
        riskRulePolicy.assertDeletable(ruleMapper.selectById(ruleId));
        ruleMapper.deleteById(ruleId);
        return R.ok();
    }

    /**
     * 单字段切换风控规则启用状态（前端 el-switch change 回调用）。
     *
     * <p>设计意图：避免前端切换开关时被迫回填全部阈值字段（{@link #updateRule} 的 RiskRuleCommand 是 record，
     * 所有 NotNull/NotBlank 都会触发校验）。这里直接 updateById 单字段，零回填。
     *
     * @param ruleId  规则 ID
     * @param enabled 启用值：{@code "1"} 启用 / {@code "0"} 禁用
     * @return 操作结果
     */
    @SaCheckPermission("dcs:risk:rule:edit")
    @Log(title = "风控规则启停", businessType = BusinessType.UPDATE)
    @PostMapping("/rules/{ruleId}/enabled")
    public R<Void> toggleRuleEnabled(
        @PathVariable Long ruleId,
        @RequestParam @Pattern(regexp = "[01]", message = "enabled 必须是 0 或 1") String enabled) {
        int updated = ruleMapper.update(null, new LambdaUpdateWrapper<DcsRiskRule>()
            .eq(DcsRiskRule::getRuleId, ruleId)
            .set(DcsRiskRule::getEnabled, enabled));
        if (updated == 0) {
            throw new ServiceException("规则不存在或已被删除");
        }
        return R.ok();
    }

    // ============ 私有 helper ============

    private DcsRiskRule toRule(RiskRuleCommand command) {
        DcsRiskRule rule = new DcsRiskRule();
        rule.setRuleId(command.ruleId());
        rule.setEventType(command.eventType());
        rule.setRuleScope(command.ruleScope());
        rule.setWeight(command.weight());
        rule.setEnabled(command.enabled());
        rule.setThresholdL1(command.thresholdL1());
        rule.setThresholdL2(command.thresholdL2());
        rule.setThresholdL3(command.thresholdL3());
        rule.setThresholdL4(command.thresholdL4());
        rule.setWindowMinutes(command.windowMinutes());
        rule.setDescription(command.description());
        return rule;
    }
}
