package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.encrypt.annotation.EncryptField;
import org.dromara.common.encrypt.enumd.AlgorithmType;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 厂商接入层（批B，2026-10-09 多模型改造）：每租户每厂商一行管 API Key/接口地址。
 *
 * <p>与 dcs_ai_config 的关系：模型行不再写钥匙（旧列停写保留作回退兜底），解析链两跳
 * =接入表优先 → 旧行内列回退。换钥匙只改本表一行，同厂全部模型即时生效（一处生效）。
 * ZCode 接入层同构（钥匙只在 provider 层，模型条目无 key 字段）。</p>
 *
 * <p>该表由超管跨租户维护，因此显式保存 tenantId 但不继承 TenantEntity。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_ai_provider_access")
public class DcsAiProviderAccess extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id")
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 模型厂商（与 dcs_ai_config.provider 同域） */
    private String provider;

    /** OpenAI 兼容接口地址（整厂一份，换地址一处生效） */
    private String baseUrl;

    /** Q5-A：接口格式（空=兼容存量按 Chat Completions） */
    private String apiFormat;

    /** API Key，加密链与 dcs_ai_config.api_key 同源（显式 AES + 同一专用密钥，存量密文可直接迁移） */
    @EncryptField(algorithm = AlgorithmType.AES, password = "DcsAiCfgEncKey16")
    private String apiKey;

    /** 厂商控制台直达链接（可选，ZCode apiKeyManagementUrl 同款） */
    private String consoleUrl;

    /** 备注 */
    private String remark;

    /** 钥匙标签（人话区分多把钥匙，如「主号/备1」；AI 缺口批 Q8 2026-10-14） */
    private String label;

    /**
     * 钥匙启停（1=参与轮询 0=停用；禁用≠删除，保留历史与余额快照）。
     * null 视同 '1'（迁移前存量行语义）。
     */
    private String enabled;

    /** 厂商侧账户余额快照（查得非算得；探测时更新，选路参考不参与扣费） */
    private java.math.BigDecimal balanceAmount;

    /** 余额币种（CNY/USD，与厂商账户一致） */
    private String balanceCurrency;

    /** 余额快照时间（手动/每日 04:30 定时刷新） */
    private java.util.Date balanceCheckedAt;

    /** 余额探测状态（OK/UNSUPPORTED=厂商不支持此后零外呼/FAILED=最近失败保留旧值/null=未探测） */
    private String balanceStatus;

    /** 删除标志 */
    @TableLogic
    private String delFlag;
}
