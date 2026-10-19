package com.smartdock.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 钱包对账巡检基线配置(批1 2026-09-25, 拍板 Q1:A"环境各管各的账")。
 *
 * <p>基线从代码常量迁到配置的动机: 静态白名单钉的是<b>开发库</b>的历史存量
 * (0.0001 种子差额/两历史断链/1306 条空值), 交付环境数据形态不同导致首夜误报。
 * 迁配置后: 开发配置(application-dev.yml)写现值照旧; 交付配置(docker profile)
 * <b>不写=空基线</b>, 客户环境从零对账——空值记录一出现(0变1)立刻报警, 语义完全正确。</p>
 *
 * <p>默认全空即"零基线"语义: 非零差额全部上报 / 所有链断裂上报 / 空值计数与 0 比对。</p>
 *
 * @author smartdock
 */
@Data
@Component
@ConfigurationProperties(prefix = "dcs.wallet.reconcile")
public class WalletReconcileProperties {

    /** 已知余额差额基线(钱包ID→差额): 差额与基线一致静默, 漂移(含方向)上报; 未登记钱包非零差额即上报 */
    private Map<Long, BigDecimal> balanceDiffs = new LinkedHashMap<>();

    /** 已知链断裂流水ID白名单: 白名单内静默(历史登记防复活), 新断裂全报 */
    private Set<Long> chainBreakLedgerIds = new LinkedHashSet<>();

    /** DEDUCT 空 biz_id 计数基线(租户→条数): 与基线一致静默, 增减都算新异常; 基线租户从结果集消失=一次性清零反向兜底 */
    private Map<String, Long> deductNullBaseline = new LinkedHashMap<>();
}
