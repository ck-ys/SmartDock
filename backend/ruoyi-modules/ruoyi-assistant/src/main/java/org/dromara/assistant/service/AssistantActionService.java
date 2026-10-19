package org.dromara.assistant.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantAction;
import org.dromara.assistant.mapper.AssistantActionMapper;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 助手动作账本服务（批G G3，spec §8）：HITL 确认后写操作的全生命周期留痕。
 *
 * <p>幂等键 {@code assistant:{tenantId}:{runId}:{seq}}（seq=同一 run 内动作序，
 * P1 每轮最多一个 act 动作=恒 1）——唯一索引=双发防线（@RepeatSubmit 的 Reactor
 * 替代件）。REJECTED 精确追踪（resume 取消时刻）依赖 resume 载荷解析，P1 登记，
 * 由 INTERRUPTED run 状态提供未决留痕。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantActionService {

    public static final String STATUS_EXECUTING = "EXECUTING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    private final AssistantActionMapper actionMapper;

    /**
     * 账本落行（EXECUTING 起步）返回原行与是否本次新建：
     * 幂等键已存在→读回原行（created=false，调用方按状态判定回执复述或拒绝重复触发）；
     * 异主冲突→403。
     */
    public record OpenResult(DcsAssistantAction row, boolean created) {
    }

    public OpenResult openExecution(String tenantId, Long userId, String runId, String threadId,
                                     String toolName, String argsJson, String idempotencyKey) {
        DcsAssistantAction row = new DcsAssistantAction();
        row.setId(IdWorker.getId());
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setRunId(runId);
        row.setThreadId(threadId);
        row.setToolName(toolName);
        row.setArgsJson(argsJson);
        row.setStatus(STATUS_EXECUTING);
        row.setIdempotencyKey(idempotencyKey);
        int inserted = actionMapper.insertIgnoreKey(row);
        DcsAssistantAction bound = actionMapper.selectByKey(idempotencyKey);
        if (bound != null && !bound.getUserId().equals(userId)) {
            throw new ServiceException("请求不合法（动作账本归属校验失败）", 403);
        }
        return new OpenResult(bound, inserted > 0);
    }

    public void markDone(Long id, String summary) {
        actionMapper.mark(id, STATUS_DONE, truncate(summary));
    }

    public void markFailed(Long id, String summary) {
        actionMapper.mark(id, STATUS_FAILED, truncate(summary));
    }

    /** 按 run/thread 查账本（断线重连恢复数据源，spec §18） */
    public List<DcsAssistantAction> listForUser(String tenantId, Long userId, String runId, String threadId) {
        return actionMapper.selectForUser(tenantId, userId, runId, threadId);
    }

    /** 二站① AU-52：管理端运行记录分页（只读；argsJson 不出网——管理面只看动作与结果）
     * 2026-10-11 日常查询：keyword 模糊（动作/结果摘要/操作人）+ status + 时间范围，空串统一转 null */
    public List<org.dromara.assistant.domain.AssistantActionLogVo> listLogForTenant(
        String tenantId, int limit, int offset, String keyword, String status, String beginTime, String endTime) {
        return actionMapper.selectLogPage(tenantId, limit, offset,
            blankToNull(keyword), blankToNull(status), blankToNull(beginTime), blankToNull(endTime));
    }

    /** 二站① AU-52：运行记录总数（与分页同条件口径） */
    public long countLogForTenant(String tenantId, String keyword, String status, String beginTime, String endTime) {
        return actionMapper.countForTenant(tenantId,
            blankToNull(keyword), blankToNull(status), blankToNull(beginTime), blankToNull(endTime));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
