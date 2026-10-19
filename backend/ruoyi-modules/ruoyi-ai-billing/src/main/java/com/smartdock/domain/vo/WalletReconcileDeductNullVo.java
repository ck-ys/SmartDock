package com.smartdock.domain.vo;

import lombok.Data;

/**
 * 钱包对账-DEDUCT 空 biz_id 计数行(批C 2026-09-24, WalletReconcileJob 专用)。
 *
 * <p>biz_id 接线修复(2026-09-24 钱包批)前的历史存量允许存在; 计数相对基线漂移(增/减)即新异常。</p>
 *
 * @author smartdock
 */
@Data
public class WalletReconcileDeductNullVo {

    /** 租户ID */
    private String tenantId;

    /** DEDUCT 且 biz_id 为空的流水条数 */
    private Long nullBizCount;
}
