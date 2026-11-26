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
import java.math.BigDecimal;
import java.util.Date;

/**
 * 联网搜索服务（额度三级管控批 Q16=1，2026-11-26）。
 *
 * <p><b>为什么单独一张表、不并进 ai_provider_access</b>（板上逐条核过，四条都是硬耦合）：</p>
 * <ol>
 *   <li>ai_provider_access.api_format 有 CHECK，只认三种<b>对话</b>格式；</li>
 *   <li>厂商是枚举 {@code AiProvider}（14 家），里面没有博查；</li>
 *   <li>探测前要过 {@code isBaseUrlAllowed} 域名白名单；</li>
 *   <li>最致命——钥匙管理的厂商下拉是从 ai_config 模型列表生成的，搜索没有模型，进不了下拉。</li>
 * </ol>
 *
 * <p><b>两套余量口径不能混</b>（板上点名的坑①）：账户余额（元）和资源包（次数）可以同时成立，
 * 所以分两列显示；资源包次数厂商没有查询接口，只能「管理员填总量 + 系统每搜一次减一」估算，
 * 界面必须标「估算」并以厂商后台为准。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_search_service")
public class AiSearchService extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private String tenantId;

    /** 搜索厂商（当前只有 BOCHA；将来加别家只多一行） */
    private String provider;

    /** 显示名（空=按 provider 显示） */
    private String label;

    private String enabled;

    /** API Key——与 ai_config.api_key 同一把加密钥匙（SMARTDOCK_AI_CONFIG_KEY） */
    @EncryptField(algorithm = AlgorithmType.AES, password = "${smartdock.encrypt.ai-config-key}")
    private String apiKey;

    /** 厂商控制台地址（「去控制台」按钮用） */
    private String consoleUrl;

    /** 账户余额（元）——官方 GET /v1/fund/remaining 自动查 */
    private BigDecimal balanceAmount;

    private String balanceCurrency;

    private Date balanceCheckedAt;

    /** OK / UNSUPPORTED / ERROR */
    private String balanceStatus;

    /** 资源包总次数（管理员填；NULL=没买包） */
    private Integer packTotal;

    /** 估算：系统累计调用次数（厂商无接口，以厂商后台为准） */
    private Integer packUsed;

    /** 资源包到期日 */
    private Date packExpireAt;

    private String remark;

    @TableLogic
    private String delFlag;
}
