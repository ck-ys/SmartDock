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
    /** G1 乙类#2（框架 #3104 事件源）：用户在确认卡点了拒绝/取消——域枚举此前悬空
     *  （javadoc 登记的 P1），现补写入方；行=拒绝时刻留痕（非执行后回写） */
    public static final String STATUS_REJECTED = "REJECTED";
    /**
     * 三批④（2026-11-01）：已撤回——业务对象已删（记忆行/草稿任务）+ revoked_by/at 留痕，
     * 行不物理删（审计仍可见「谁撤的、何时撤的」）。
     */
    public static final String STATUS_REVOKED = "REVOKED";
    /**
     * 三批④：可撤回工具白名单（完成卡只给这两类出撤回按钮）。save_memory 走
     * {@link AssistantMemoryService#remove}（本模块直删，属主谓词 SQL）；dcs_create_task
     * 走 SPI {@code BizToolProvider.revoke}（DcsToolProvider override，DRAFT 条件删除 CAS）。
     * <b>双处清单</b>：新增可撤工具须同时实现 provider.revoke 并登记此处（DcsToolProvider
     * revoke 注释与此互相锚定防漂移）。
     */
    public static final java.util.Set<String> REVOCABLE_TOOLS = java.util.Set.of("save_memory", "dcs_create_task");
    /** 三批④：回执锚点尾段形态「（记录 #id）」（markDone/withRefTail 拼接口径） */
    private static final java.util.regex.Pattern REF_TAIL =
        java.util.regex.Pattern.compile("（记录 #(\\d+)）$");
    /** 三批③：审计 CSV 导出行数上限（照 AnalyticsService.exportSessions 口径——超限报错引导缩窗） */
    private static final int EXPORT_MAX_ROWS = 50_000;

    private final AssistantActionMapper actionMapper;
    private final org.dromara.assistant.mapper.AssistantRunMapper runMapper;
    /** 三批④：save_memory 撤回=记忆行属主删除（本模块内直达，不经 SPI） */
    private final AssistantMemoryService memoryService;
    /** 三批④：provider 工具撤回分派（按 tools() 含名找 provider，无 Spring 依赖收集） */
    private final org.dromara.assistant.core.ProviderToolRegistry providerRegistry;

    /**
     * 账本落行（EXECUTING 起步）返回原行与是否本次新建：
     * 幂等键已存在→读回原行（created=false，调用方按状态判定回执复述或拒绝重复触发）；
     * 异主冲突→403。
     */
    public record OpenResult(DcsAssistantAction row, boolean created) {
    }

    /**
     * 历史/测试路径（G3ActionTest/RedteamCore8Test 等旧调用面）：不带放行方式，
     * release_mode 落空。
     */
    public OpenResult openExecution(String tenantId, Long userId, String runId, String threadId,
                                     String toolName, String argsJson, String idempotencyKey) {
        return openExecution(tenantId, userId, runId, threadId, toolName, argsJson, idempotencyKey, null);
    }

    /**
     * 三批③（2026-11-01）：带放行方式的落行——写操作审计主入口（release_mode=
     * confirm/once/auto/allow，由 AssistantActionLedgerHook 按建时快照判定后传入）。
     */
    public OpenResult openExecution(String tenantId, Long userId, String runId, String threadId,
                                     String toolName, String argsJson, String idempotencyKey,
                                     String releaseMode) {
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
        row.setReleaseMode(releaseMode);
        int inserted = actionMapper.insertIgnoreKey(row);
        DcsAssistantAction bound = actionMapper.selectByKey(idempotencyKey);
        if (bound != null && !bound.getUserId().equals(userId)) {
            throw new ServiceException("请求不合法（动作账本归属校验失败）", 403);
        }
        return new OpenResult(bound, inserted > 0);
    }

    /** 旧签名（纯摘要收口，无回执锚点）——既有调用面不变 */
    public void markDone(Long id, String summary) {
        markDone(id, summary, null);
    }

    /** 旧签名（纯摘要收口）——既有调用面不变 */
    public void markFailed(Long id, String summary) {
        markFailed(id, summary, null);
    }

    /**
     * 三批③：带回执锚点的收口——refId≠null 时结果摘要钉「（记录 #id）」尾段
     * （④撤回按尾段直读账本行；先拼后截会截掉尾段，见 {@link #withRefTail}）。
     */
    public void markDone(Long id, String summary, Long refId) {
        actionMapper.mark(id, STATUS_DONE, withRefTail(summary, refId));
    }

    /** 三批③：失败收口（refId 实际恒 null，签名与 markDone 对称） */
    public void markFailed(Long id, String summary, Long refId) {
        actionMapper.mark(id, STATUS_FAILED, withRefTail(summary, refId));
    }

    /**
     * 尾段顺序钉死（纯函数，列宽 500，单测锚点）：先按 500−尾段长 截正文，再拼尾段——
     * 总长恒 ≤500 且尾段完整（「（记录 #123）」整体落在结尾，可被撤回端解析）。
     */
    public static String withRefTail(String summary, Long refId) {
        String body = truncate(summary);
        if (refId == null) {
            return body;
        }
        String tail = "（记录 #" + refId + "）";
        if (body == null) {
            return tail;
        }
        int room = 500 - tail.length();
        if (room <= 0) {
            // 尾段自身超宽（雪花 id 19 位尾段 ~27 字符，实际不可达）——保尾段弃正文
            return tail;
        }
        return (body.length() > room ? body.substring(0, room) : body) + tail;
    }

    /**
     * G1 乙类#2：拒绝留痕（新行，幂等键=rej 前缀+toolCallId 防事件双发）。
     * 归属由调用方从会话反解传入（enricher 框架流线程无登录上下文）。
     */
    public void recordRejected(String tenantId, Long userId, String runId, String threadId,
                               String toolName, String toolCallId, String reason) {
        DcsAssistantAction row = new DcsAssistantAction();
        row.setId(IdWorker.getId());
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setRunId(runId);
        row.setThreadId(threadId);
        row.setToolName(toolName);
        row.setStatus(STATUS_REJECTED);
        row.setResultSummary(truncate(reason));
        // 三审回修（P2）：键列宽 128，runId（客户端可控，最长 128）+toolCallId 拼接过长会
        // 插入报错被 enricher 吞成 warn=审计静默丢失——超长截断（实际 UUID 形态恒不触发）
        String key = "assistant:" + tenantId + ":" + runId + ":rej:" + toolCallId;
        row.setIdempotencyKey(key.length() > 128 ? key.substring(0, 128) : key);
        actionMapper.insertRejected(row);
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

    /**
     * 三批③（2026-11-01）：写操作审计分页——四维筛选（成员昵称 keyword / 工具名 /
     * 放行方式 / 时间闭区间）；args_json 不出网（管理面只看动作与结果）。
     */
    public List<org.dromara.assistant.domain.AssistantActionLogVo> listAuditForTenant(
        String tenantId, int limit, int offset, String keyword, String toolName,
        String releaseMode, String beginTime, String endTime) {
        return actionMapper.selectAuditPage(tenantId, limit, offset, blankToNull(keyword),
            blankToNull(toolName), blankToNull(releaseMode), blankToNull(beginTime), blankToNull(endTime));
    }

    /** 三批③：审计总数（与分页同条件口径，防筛选后 total 与行数对不上） */
    public long countAuditForTenant(String tenantId, String keyword, String toolName,
                                     String releaseMode, String beginTime, String endTime) {
        return actionMapper.countAuditForTenant(tenantId, blankToNull(keyword), blankToNull(toolName),
            blankToNull(releaseMode), blankToNull(beginTime), blankToNull(endTime));
    }

    /**
     * 三批③：审计 CSV 导出（与分页同筛选口径）：列=时间/成员/工具/放行方式/状态/结果摘要，
     * 无 args_json；BOM（Excel 兼容）+ csvCell 转义（照 AnalyticsService :1521-1540 口径）；
     * 5 万行上限，超限报错引导缩窗。
     */
    public byte[] exportAuditCsv(String tenantId, String keyword, String toolName,
                                 String releaseMode, String beginTime, String endTime) {
        List<org.dromara.assistant.domain.AssistantActionLogVo> rows = actionMapper.selectAuditPage(
            tenantId, EXPORT_MAX_ROWS + 1, 0, blankToNull(keyword), blankToNull(toolName),
            blankToNull(releaseMode), blankToNull(beginTime), blankToNull(endTime));
        if (rows.size() > EXPORT_MAX_ROWS) {
            throw new ServiceException("匹配审计行 " + countAuditForTenant(tenantId, keyword, toolName,
                releaseMode, beginTime, endTime) + " 条超过导出上限 " + EXPORT_MAX_ROWS
                + "，请缩小时间范围或筛选条件后再导出");
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        StringBuilder csv = new StringBuilder("\uFEFF"); // Excel 兼容 BOM
        csv.append("时间,成员,工具,放行方式,状态,结果摘要\r\n");
        for (org.dromara.assistant.domain.AssistantActionLogVo r : rows) {
            csv.append(csvCell(r.getCreateTime() == null ? "" : fmt.format(r.getCreateTime()))).append(',')
                .append(csvCell(r.getUserName() == null ? "" : r.getUserName())).append(',')
                .append(csvCell(r.getToolName() == null ? "" : r.getToolName())).append(',')
                .append(csvCell(org.dromara.assistant.core.AssistantReleaseMode.display(r.getReleaseMode()))).append(',')
                .append(csvCell(r.getStatus() == null ? "" : r.getStatus())).append(',')
                .append(csvCell(r.getResultSummary() == null ? "" : r.getResultSummary())).append("\r\n");
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** CSV 单元格转义（照 AnalyticsService.csvCell 同口径：公式注入防御 + 逗号/引号/换行包裹） */
    private static String csvCell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    /** 按 runId 反解归属（三审回修：G1 拒绝审计锚点——run 登记行全局唯一且首绑校验过归属；
     *  按 thread 裸查会话行在跨租户同 threadId 时会错挂租户） */
    public org.dromara.assistant.domain.DcsAssistantRun findRun(String runId) {
        return runMapper.selectByRunId(runId);
    }

    /**
     * 撤回资格判定（三批④，纯函数，单测锚点）：执行成功 + 有回执锚点 + 工具在可撤白名单。
     */
    public static boolean canRevoke(String status, Long refId, String toolName) {
        return STATUS_DONE.equals(status) && refId != null && REVOCABLE_TOOLS.contains(toolName);
    }

    /**
     * 账本 result_summary 尾段「（记录 #id）」直读 refId（纯函数，单测锚点）。
     * 尾段由 {@link #withRefTail} 钉死先截正文再拼尾段，恒完整落在结尾；无尾段/垃圾→null（不猜）。
     */
    public static Long parseRefId(String resultSummary) {
        if (resultSummary == null || resultSummary.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = REF_TAIL.matcher(resultSummary);
        try {
            return m.find() ? Long.parseLong(m.group(1)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 完成帧数据源（三批④）：按 run_id 全局直查可撤行——不带租户条件（三类租户来源
     * 错位免疫，见 AssistantActionMapper.selectRevocableByRun 注释）；enricher 流线程直调。
     */
    public List<DcsAssistantAction> listRevocableByRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        return actionMapper.selectRevocableByRun(runId, REVOCABLE_TOOLS);
    }

    /**
     * 撤回一次自动执行的操作（三批④，2026-11-01）。
     *
     * <p>租户源=登录快照（controller 传 {@code LoginHelper.getLoginUser().getTenantId()}，
     * 与账本执行行 tenant_id 同源——建行时即快照；不做任何动态租户解析：超管切租户态
     * 动态值≠快照会查不到行→403，可撤回功能失效）。行归属不符 fail-closed 403（不误删）。</p>
     *
     * <p>分流人话：REVOKED→已撤回过；FAILED/REJECTED→只有执行成功的能撤；无尾段锚点→
     * 没有可撤回的业务对象。业务动作（记忆行删/草稿删）与 markRevoked 同事务：业务失败
     * 抛出即回滚，REVOKED 不落。</p>
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public String revoke(String tenantId, Long userId, Long actionId) {
        DcsAssistantAction row = actionMapper.selectById(actionId);
        if (row == null || !tenantId.equals(row.getTenantId()) || !userId.equals(row.getUserId())) {
            throw new ServiceException("无权撤回该操作", 403);
        }
        if (STATUS_REVOKED.equals(row.getStatus())) {
            throw new ServiceException("该操作已撤回过");
        }
        Long refId = parseRefId(row.getResultSummary());
        if (!canRevoke(row.getStatus(), refId, row.getToolName())) {
            if (STATUS_FAILED.equals(row.getStatus()) || STATUS_REJECTED.equals(row.getStatus())) {
                throw new ServiceException("只有执行成功的操作能撤回");
            }
            throw new ServiceException("该操作没有可撤回的业务对象");
        }
        if ("save_memory".equals(row.getToolName())) {
            // 记忆行删除：属主谓词在 SQL（删别人=0 行）；0 行=已不在（记忆页手删过）
            if (!memoryService.remove(tenantId, userId, refId)) {
                throw new ServiceException("这条记忆已经不在了（可能已在记忆页手动删除）");
            }
        } else {
            com.smartdock.assistant.spi.BizToolProvider provider = findProvider(row.getToolName());
            if (provider == null) {
                throw new ServiceException("找不到该操作对应的功能模块，无法撤回");
            }
            com.smartdock.assistant.spi.BizToolCall call = new com.smartdock.assistant.spi.BizToolCall();
            call.setToolName(row.getToolName());
            call.setTenantId(tenantId);
            call.setUserId(userId);
            call.setThreadId(row.getThreadId());
            com.smartdock.assistant.spi.BizToolResult r = provider.revoke(row.getToolName(), refId, call);
            if (r == null || !r.isOk()) {
                throw new ServiceException(r == null || r.getMessage() == null ? "撤回失败，请稍后重试" : r.getMessage());
            }
        }
        actionMapper.markRevoked(row.getId(), userId);
        return "已撤回";
    }

    /** 按 tools() 含名找 provider（撞名让位口径与注册侧一致：先到先得） */
    private com.smartdock.assistant.spi.BizToolProvider findProvider(String toolName) {
        for (com.smartdock.assistant.spi.BizToolProvider p : providerRegistry.providers()) {
            try {
                if (p.tools() != null && p.tools().stream().anyMatch(s -> s != null && toolName.equals(s.getName()))) {
                    return p;
                }
            } catch (Exception ignored) {
                // 单 provider tools() 异常不让位拖垮查找（注册侧同口径只 warn）
            }
        }
        return null;
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
