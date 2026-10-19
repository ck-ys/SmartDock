package com.smartdock.dcs.tool;

import com.smartdock.assistant.spi.BizParamSpec;
import com.smartdock.assistant.spi.BizToolCall;
import com.smartdock.assistant.spi.BizToolProvider;
import com.smartdock.assistant.spi.BizToolResult;
import com.smartdock.assistant.spi.BizToolSpec;
import com.smartdock.dcs.domain.DcsSubmission;
import com.smartdock.dcs.domain.DcsTask;
import com.smartdock.dcs.domain.bo.DcsTaskBo;
import com.smartdock.dcs.service.DcsAuditService;
import com.smartdock.dcs.service.DcsNoticeService;
import com.smartdock.dcs.service.DcsSubmissionService;
import com.smartdock.dcs.service.DcsTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微核心的助手工具提供者(design-mini-core §5): 4 工具经 SPI 进 AI 助手。
 *
 * <p>工具在 agentscope 的 boundedElastic 线程执行——无 Sa-Token 上下文,
 * 身份全部来自 {@link BizToolCall}, 租户用 TenantHelper.dynamic 包裹
 * (TenantLine 拦截器读 dynamic 租户, 防跨租户泄漏)。服务层方法均为显式 userId。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DcsToolProvider implements BizToolProvider {

    /** 角色档: 0=成员 1=发布人(与 ToolIdentity 档位约定一致, spi 侧只认 int) */
    private static final int LEVEL_MEMBER = 0;
    private static final int LEVEL_PUBLISHER = 1;

    private final DcsTaskService taskService;
    private final DcsSubmissionService submissionService;
    private final DcsAuditService auditService;
    private final DcsNoticeService noticeService;
    private final com.smartdock.dcs.mapper.DcsUserLookupMapper userLookupMapper;

    @Override
    public String namespace() {
        return "dcs";
    }

    @Override
    public List<BizToolSpec> tools() {
        List<BizToolSpec> out = new ArrayList<>();

        BizToolSpec queryTasks = BizToolSpec.readOnly("dcs_query_tasks",
            "查询任务", "查与我相关的收集中的任务(我发布的或指派给我的), 标题可模糊");
        queryTasks.getParams().add(BizParamSpec.string("keyword", "标题关键词(可空)", false));
        out.add(queryTasks);

        BizToolSpec myTodos = BizToolSpec.readOnly("dcs_query_my_todos",
            "我的待办", "我还未完成填报的任务(未提交或被退回)");
        out.add(myTodos);

        BizToolSpec querySubmissions = BizToolSpec.readOnly("dcs_query_submissions",
            "待审列表", "等我去审核的提交(仅审核人/发布人)");
        querySubmissions.setMinRoleLevel(LEVEL_PUBLISHER);
        querySubmissions.setRequiredPermission("dcs:audit:audit");
        out.add(querySubmissions);

        BizToolSpec createTask = BizToolSpec.write("dcs_create_task",
            "建任务", "创建一个草稿任务(自动带一个必填文本字段「填报内容」、发布人直审模式; "
                + "建好后需到任务管理页发布, 目标成员须是本单位的真实 userId)",
            "确认要创建该任务吗");
        createTask.setMinRoleLevel(LEVEL_PUBLISHER);
        createTask.setRequiredPermission("dcs:task:add");
        createTask.getParams().add(BizParamSpec.string("title", "任务标题", true));
        createTask.getParams().add(BizParamSpec.string("description", "任务说明", false));
        createTask.getParams().add(BizParamSpec.string("deadline", "截止日期 yyyy-MM-dd", false));
        createTask.getParams().add(BizParamSpec.string("assigneeUserIds",
            "目标成员 userId 列表, 逗号分隔", true));
        out.add(createTask);
        return out;
    }

    @Override
    public BizToolResult execute(BizToolCall call) {
        try {
            return withTenant(call, () -> dispatch(call));
        } catch (Exception e) {
            // 三审 5.3fh P2-3: 异常原文(SQL/内部细节)不进对话上下文, 统一话术+日志留痕
            log.warn("[DcsTool] execute failed tool={} : {}", call.getToolName(), e.toString());
            return BizToolResult.fail("工具执行失败, 请稍后重试或到页面操作");
        }
    }

    private BizToolResult dispatch(BizToolCall call) {
        Long me = call.getUserId();
        return switch (call.getToolName()) {
            case "dcs_query_tasks" -> queryTasks(call, me);
            case "dcs_query_my_todos" -> myTodos(me);
            case "dcs_query_submissions" -> pendingAudit(me);
            case "dcs_create_task" -> createTask(call, me);
            default -> BizToolResult.fail("未知工具: " + call.getToolName());
        };
    }

    private BizToolResult queryTasks(BizToolCall call, Long me) {
        String keyword = str(call.getArgs().get("keyword"));
        List<Map<String, Object>> rows = taskService
            .mentionCandidates(StringUtils.isBlank(keyword) ? null : keyword, 20, me)
            .stream().map(t -> taskRow(t, me)).toList();
        return BizToolResult.ok(Map.of("count", rows.size(), "returned", rows.size(), "tasks", rows));
    }

    private BizToolResult myTodos(Long me) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DcsTask t : taskService.myTasks(new PageQuery(20, 1), me).getRows()) {
            DcsSubmission mine = submissionService.latestOf(t.getId(), me);
            if (mine != null && (DcsSubmission.STATUS_PENDING_AUDIT.equals(mine.getStatus())
                || DcsSubmission.STATUS_APPROVED.equals(mine.getStatus()))) {
                continue;
            }
            Map<String, Object> row = taskRow(t, me);
            row.put("myStatus", mine == null ? "未提交" : "被退回(可重提)");
            rows.add(row);
        }
        return BizToolResult.ok(Map.of("count", rows.size(), "todos", rows));
    }

    private BizToolResult pendingAudit(Long me) {
        List<Map<String, Object>> rows = auditService.pending(new PageQuery(20, 1), me).getRows()
            .stream().map(s -> {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("submissionId", s.getId());
                row.put("taskId", s.getTaskId());
                row.put("submitterUserId", s.getSubmitterUserId());
                row.put("version", s.getVersion());
                row.put("submittedAt", s.getSubmittedAt() == null ? null : String.valueOf(s.getSubmittedAt()));
                return row;
            }).toList();
        return BizToolResult.ok(Map.of("count", rows.size(), "pending", rows));
    }

    private BizToolResult createTask(BizToolCall call, Long me) {
        String title = str(call.getArgs().get("title"));
        if (StringUtils.isBlank(title)) {
            return BizToolResult.fail("title 必填");
        }
        List<Long> assignees = new ArrayList<>();
        for (String s : str(call.getArgs().get("assigneeUserIds")).split("[,，\\s]+")) {
            if (StringUtils.isNotBlank(s)) {
                try {
                    assignees.add(Long.parseLong(s.trim()));
                } catch (NumberFormatException e) {
                    return BizToolResult.fail("目标成员含非数字 userId: " + s);
                }
            }
        }
        if (assignees.isEmpty()) {
            return BizToolResult.fail("assigneeUserIds 必填(逗号分隔)");
        }
        DcsTaskBo bo = new DcsTaskBo();
        bo.setTitle(title);
        bo.setDescription(str(call.getArgs().get("description")));
        if (StringUtils.isNotBlank(str(call.getArgs().get("deadline")))) {
            try {
                bo.setDeadline(java.sql.Date.valueOf(str(call.getArgs().get("deadline"))));
            } catch (IllegalArgumentException e) {
                return BizToolResult.fail("deadline 需为 yyyy-MM-dd");
            }
        }
        bo.setAssigneeUserIds(assignees);
        bo.setAuditMode(DcsTask.AUDIT_PUBLISHER);
        DcsTaskBo.DcsFieldBo field = new DcsTaskBo.DcsFieldBo();
        field.setFieldCode("content");
        field.setFieldName("填报内容");
        field.setFieldType("text");
        field.setRequired("1");
        bo.setFields(List.of(field));
        DcsTask t = taskService.create(bo, me);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("taskId", t.getId());
        out.put("status", t.getStatus());
        out.put("assignees", resolveNames(assignees));
        out.put("hint", "已创建草稿(附 1 个必填文本字段「填报内容」, 发布人直审), 请到任务管理页核对并发布");
        return BizToolResult.ok(out);
    }

    private Map<String, Object> taskRow(DcsTask t, Long me) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("taskId", t.getId());
        row.put("title", t.getTitle());
        row.put("deadline", t.getDeadline() == null ? null : String.valueOf(t.getDeadline()));
        row.put("iAmPublisher", me != null && me.equals(t.getPublisherUserId()));
        return row;
    }

    /** 工具线程无请求上下文: dynamic 租户包裹(TenantLine 读 dynamic)。
     *  三审 dspro P2-4: 空租户 fail-closed——无租户上下文的身份本就不该存在,
     *  直执行=以"无租户"跑全量查询, 属越权防线开口。 */
    private BizToolResult withTenant(BizToolCall call, java.util.function.Supplier<BizToolResult> body) {
        String tenantId = call.getTenantId();
        if (StringUtils.isBlank(tenantId)) {
            return BizToolResult.fail("缺少租户上下文, 无法执行业务工具");
        }
        return TenantHelper.dynamic(tenantId, body::get);
    }

    /** userId → 姓名(缺档回落数字 id)——确认卡/结果可人工核对, 防模型编造 id 无从发现 */
    private List<String> resolveNames(List<Long> ids) {
        Map<Long, String> byId = new LinkedHashMap<>();
        for (Map<String, Object> m : userLookupMapper.selectBriefsByIds(ids)) {
            Object name = m.get("nick_name") == null ? m.get("user_name") : m.get("nick_name");
            byId.put(((Number) m.get("user_id")).longValue(), String.valueOf(name));
        }
        List<String> out = new ArrayList<>();
        for (Long id : ids) {
            out.add(byId.getOrDefault(id, String.valueOf(id)));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
