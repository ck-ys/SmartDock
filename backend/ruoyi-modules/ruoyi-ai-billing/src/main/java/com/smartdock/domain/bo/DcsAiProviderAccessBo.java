package com.smartdock.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import com.smartdock.domain.DcsAiProviderAccess;

/**
 * 厂商接入业务对象（批B，2026-10-09）。
 *
 * <p>编辑语义：apiKey 留空/传 **** = 沿用原密钥（与模型行编辑同约定）；baseUrl 必传
 * （整厂一份接口地址，改它即全厂生效）。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = DcsAiProviderAccess.class, reverseConvertGenerate = false)
public class DcsAiProviderAccessBo extends BaseEntity {

    /** 主键（已存在接入时必传；新建由服务端判空生成） */
    private Long id;

    /** 租户编号（必填） */
    @NotBlank(message = "租户编号不能为空", groups = {EditGroup.class})
    private String tenantId;

    /** 模型厂商（必填，唯一键 (tenant_id,provider) 的一半） */
    @NotBlank(message = "模型厂商不能为空", groups = {EditGroup.class})
    private String provider;

    /** OpenAI 兼容接口地址（必填，整厂一份） */
    @NotBlank(message = "接口地址不能为空", groups = {EditGroup.class})
    private String baseUrl;

    /** Q5-A：接口格式（三选一，空=Chat Completions） */
    private String apiFormat;

    /** API Key（编辑留空/****=沿用原密钥；新建必填由服务端校验——首个厂商接入必须给钥匙） */
    private String apiKey;

    /** 厂商控制台直达链接（可选） */
    private String consoleUrl;

    /** 备注 */
    private String remark;

    /** 钥匙标签（AI 缺口批 Q8：人话区分多把钥匙；空=自动生成「钥匙 N」） */
    private String label;

    /** 钥匙启停（"1"=参与轮询 "0"=停用；空=新建默认启用/编辑不改） */
    private String enabled;
}
