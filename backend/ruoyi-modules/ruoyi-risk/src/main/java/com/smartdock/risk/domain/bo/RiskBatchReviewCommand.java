package com.smartdock.risk.domain.bo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量处置/复核请求参数。
 *
 * <p>设计意图：单条 review/release 接口已存在，批量场景下循环调用会产生 N 次网络往返；
 * 同时批量操作需要聚合"部分失败"的结果消息（如"3 条成功，1 条因状态已变更失败"），
 * 因此新增此 BO 配合批量接口一次性处理。
 *
 * @author smartdock
 */
@Data
public class RiskBatchReviewCommand {

    /** 目标 ID 列表：处置 ID（批量解除/复核）或复核 ID（画像批量复核） */
    @NotEmpty(message = "ids 不能为空")
    private List<Long> ids;

    /** 复核结论：{@code CONFIRMED} / {@code DISMISSED}。批量解除可不传（无 conclusion 语义）。 */
    private String conclusion;

    /** 操作理由（必填，留痕） */
    @NotNull(message = "操作理由不能为空")
    private String reason;
}
