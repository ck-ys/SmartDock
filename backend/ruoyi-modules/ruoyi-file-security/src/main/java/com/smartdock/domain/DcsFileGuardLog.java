package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
/**
 * 文件安全检测命中记录 dcs_file_guard_log（弹窗改版 Q4=A，计划 GUARD_CONFIG_UI_REDESIGN_20260918）
 *
 * <p>观察期运营闭环的数据底账：只记"命中"（未命中量太大不落库）。同一命中行同时承载三种处置
 * DENIED（ENFORCE 拒传，对象未落 MinIO）/ REVIEWED（ENFORCE 强制进审）/ OBSERVED（观察态放行留痕）。
 * 落库失败不阻断上传主流程（try-catch 吞掉只记错误日志）。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_file_guard_log")
public class DcsFileGuardLog extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 记录ID（雪花） */
    @TableId(value = "log_id")
    private Long logId;

    /** 上传人 userId（无登录上下文时为空） */
    private Long userId;

    /** 上传人姓名（冗余，列表展示免联查；取不到为空串） */
    private String userName;

    /** 文件名（上传时的原始名） */
    private String fileName;

    /** 文件字节数 */
    private Long fileSize;

    /** 命中规则码：EXTENSION_WHITELIST / MAGIC_MISMATCH / OFFICE_MACRO / SVG_XSS / PDF_JAVASCRIPT / PARSE_ANOMALY */
    private String ruleCode;

    /** 命中详情（RiskFinding.message 人话说明，列表直接展示） */
    private String detail;

    /** 命中时的检测模式：OFF / OBSERVE / ENFORCE */
    private String mode;

    /** 处置结果（命中时刻判定意向，非上传终态）：DENIED 已拒传 / REVIEWED 强制进审 / OBSERVED 观察放行 */
    private String disposition;
}
