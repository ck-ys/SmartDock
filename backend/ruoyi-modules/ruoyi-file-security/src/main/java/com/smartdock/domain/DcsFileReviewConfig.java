package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * 租户级文件审核配置 dcs_file_review_config（12b 文件审核中心 P1）
 *
 * <p>每租户一行（UNIQUE(tenant_id)）。语义：enabled != '1' 或【查无行】= 功能整体关闭——
 * 钩子不建单、读拦截直通、头像走原"立即生效"路径，全链路零行为变化（合同#14）。
 * 首行由设置页保存时 upsert（ON CONFLICT DO UPDATE）产生，代码不做隐式初始化。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_file_review_config")
public class DcsFileReviewConfig extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 配置ID（雪花） */
    @TableId(value = "config_id")
    private Long configId;

    /** 总开关：1=启用审核，0=全部直通（默认） */
    private String enabled;

    /** 启用审核的类别逗号串，如 'IMAGE,VIDEO'；未列出的类别上传直接通过不落数据 */
    private String categories;

    /**
     * file-guard 检测模式（批二，Q5=B 界面可切）：
     * OFF=全直通 / OBSERVE=只记日志不拦截（缺省，上线观察 Q6=B）/ ENFORCE=拒传+强制进审
     */
    private String guardMode;

    /** file-guard 扩展名白名单逗号串（小写不带点）；空=FileReviewCategory 七类并集默认 */
    private String guardWhitelist;

    /** file-guard 深扫大小上限（MB，超过只做白名单+魔数两层）；空/非正=50 */
    private Integer guardDeepScanMaxMb;
}
