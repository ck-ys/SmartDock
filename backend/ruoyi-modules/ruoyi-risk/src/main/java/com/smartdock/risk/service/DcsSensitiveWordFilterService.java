package com.smartdock.risk.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.risk.domain.DcsSensitiveWord;
import com.smartdock.risk.mapper.DcsSensitiveWordMapper;
import com.smartdock.risk.support.SensitiveWordMatcher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 敏感词实时过滤服务(消息发送/用户输入场景调用)。
 *
 * <p><b>职责</b>: 加载当前租户启用的敏感词规则 → 委托 {@link SensitiveWordMatcher} 做匹配 →
 * 返回 {@link SensitiveWordMatcher.FilterResult}(含命中位置/处理动作)。</p>
 *
 * <p><b>规则加载策略</b>:</p>
 * <ul>
 *   <li>系统兜底词(硬编码): 极端辱骂/政治敏感词, 所有租户共享, 无法在后台删除</li>
 *   <li>租户自定义词: 从 {@code dcs_sensitive_word} 表查 {@code tenantId=当前租户 或 000000(平台级)}
 *       且 enabled=1 的记录</li>
 *   <li>排序: action 降序(BLOCK 优先于 MASK), wordId 升序(稳定)</li>
 * </ul>
 *
 * <p><b>多租户说明</b>: 用 {@code TenantHelper.ignore} 查询, 因为要同时取当前租户和 000000 平台词库,
 * 默认的租户隔离会过滤掉 000000。</p>
 *
 * @author smartdock
 */
@Service
@RequiredArgsConstructor
public class DcsSensitiveWordFilterService {

    private static final String ENABLED = "1";

    private final DcsSensitiveWordMapper wordMapper;

    /** 全场景过滤(兼容历史调用与词库页命中自测; 业务链路请显式传场景用 {@link #filter(String, String)}) */
    public SensitiveWordMatcher.FilterResult filter(String content) {
        return filter(content, null);
    }

    /**
     * 场景过滤(2026-09-22 词库场景化)。
     * @param scene CHAT=聊天 / FORM=表单(含追加/在线批量/Excel) / FILE=文件; null 或空=不过滤场景(全部词)
     */
    public SensitiveWordMatcher.FilterResult filter(String content, String scene) {
        return new SensitiveWordMatcher(loadRules(scene)).filter(content);
    }

    /**
     * 命中自测(2026-08-24, 供敏感词管理页"命中测试"): 与 filter() 同一套规则加载(含内置基线),
     * 但不抛异常不改写文本, 只返回命中明细——纯只读验证, 不产生风险事件不记分。
     */
    public SensitiveWordMatcher.InspectionResult inspect(String content) {
        return inspect(content, null);
    }

    /** 场景版命中自测(2026-09-22): 词库管理页可按场景验证过滤效果 */
    public SensitiveWordMatcher.InspectionResult inspect(String content, String scene) {
        return new SensitiveWordMatcher(loadRules(scene)).inspect(content);
    }

    /**
     * 场景感知规则装载(2026-09-22 词库场景化)。
     *
     * <p><b>场景口径</b>: 系统兜底词(硬编码基线)钉死 CHAT 场景——FORM/FILE 场景整体剔除,
     * 防兜底词误伤表单填报(如思政类课程字段); DB 词按 scene 列(CSV)过滤, 存量行默认 CHAT。</p>
     */
    private List<SensitiveWordMatcher.WordRule> loadRules(String scene) {
        String tenantId = currentTenantId();
        List<SensitiveWordMatcher.WordRule> rules = new ArrayList<>();
        rules.add(new SensitiveWordMatcher.WordRule("傻逼", SensitiveWordMatcher.ACTION_MASK));
        rules.add(new SensitiveWordMatcher.WordRule("非法交易", SensitiveWordMatcher.ACTION_BLOCK));

        // 系统兜底词(上方两条硬编码基线)钉死 CHAT 场景: FORM/FILE 场景整体剔除(此刻 rules 仅含基线),
        // 防兜底词误伤表单填报主数据流(2026-09-22 场景化红线)
        if (StrUtil.isNotBlank(scene) && !"CHAT".equals(scene)) {
            rules.clear();
        }

        List<DcsSensitiveWord> rows = TenantHelper.ignore(() -> wordMapper.selectList(new LambdaQueryWrapper<DcsSensitiveWord>()
            .in(DcsSensitiveWord::getTenantId, tenantId, "000000")
            .eq(DcsSensitiveWord::getEnabled, ENABLED)
            .eq(DcsSensitiveWord::getDelFlag, "0")
            .apply(StrUtil.isNotBlank(scene), "scene LIKE '%' || {0} || '%'", scene)
            .orderByDesc(DcsSensitiveWord::getAction)
            .orderByAsc(DcsSensitiveWord::getWordId)));
        for (DcsSensitiveWord row : rows) {
            rules.add(new SensitiveWordMatcher.WordRule(row.getWordText(), row.getAction(),
                StrUtil.blankToDefault(row.getScene(), "CHAT")));
        }
        return rules;
    }

    private String currentTenantId() {
        String tenantId = LoginHelper.getTenantId();
        return StrUtil.blankToDefault(tenantId, "000000");
    }
}
