package com.smartdock.mission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.mission.domain.Notice;
import com.smartdock.mission.domain.Task;
import com.smartdock.mission.domain.TaskField;
import com.smartdock.mission.domain.bo.TaskBo;
import com.smartdock.mission.mapper.TaskFieldMapper;
import com.smartdock.mission.domain.Submission;
import com.smartdock.mission.domain.TodoPolicy;
import com.smartdock.mission.mapper.SubmissionMapper;
import com.smartdock.mission.mapper.TaskMapper;
import com.smartdock.mission.statemachine.TaskStateMachine;
import com.smartdock.mission.support.Jsons;
import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.monitor.SelfMonitorEvents;
import com.smartdock.profile.event.ProfileEventBo;
import com.smartdock.profile.ProfileUids;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务服务: 建/改(DRAFT 期)/发布/关闭/查询。
 *
 * <p>事件翻译点 2 个(design-mini-core §4): publish→task.published、close→task.closed。
 * 发布即通知全部目标成员。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskMapper taskMapper;
    private final TaskFieldMapper fieldMapper;
    private final SubmissionMapper submissionMapper;
    private final com.smartdock.mission.mapper.UserLookupMapper userLookupMapper;
    private final NoticeService noticeService;
    private final ProfileEventPublisher eventPublisher;
    private final com.smartdock.mission.support.SubjectNameResolver subjectNameResolver;

    /** 新建任务(DRAFT + 字段定义落库) */
    @Transactional(rollbackFor = Exception.class)
    public Task create(TaskBo bo, Long userId) {
        if (StringUtils.isBlank(bo.getTitle()) || bo.getTitle().length() > 128) {
            throw new ServiceException("任务标题必填(≤128 字)");
        }
        Task t = new Task();
        t.setId(IdWorker.getId());
        t.setTenantId(currentTenant());
        t.setTitle(bo.getTitle());
        t.setDescription(bo.getDescription());
        t.setDeadline(bo.getDeadline());
        t.setPublisherUserId(userId);
        t.setStatus(Task.STATUS_DRAFT);
        t.setAssigneeUserIds(jsonOfIds(bo.getAssigneeUserIds()));
        t.setAuditMode(StringUtils.isBlank(bo.getAuditMode()) ? Task.AUDIT_PUBLISHER : bo.getAuditMode());
        t.setAuditorUserIds(jsonOfIds(bo.getAuditorUserIds()));
        t.setCreateBy(userId);
        requireDescription(bo.getDescription());
        requireAssigneesExist(t.getAssigneeUserIds());
        requireAuditorsExist(t);
        taskMapper.insertJsonb(t);
        saveFields(t.getId(), t.getTenantId(), bo.getFields());
        return t;
    }

    /** DRAFT 期编辑(发布后拒绝——改已发布任务会破坏提交数据一致性) */
    @Transactional(rollbackFor = Exception.class)
    public void update(TaskBo bo, Long userId) {
        if (bo.getId() == null) {
            throw new ServiceException("任务 id 必填");
        }
        Task exist = requireOwned(bo.getId(), userId);
        if (!Task.STATUS_DRAFT.equals(exist.getStatus())) {
            throw new ServiceException("仅 DRAFT 任务可编辑");
        }
        if (StringUtils.isBlank(bo.getTitle()) || bo.getTitle().length() > 128) {
            throw new ServiceException("任务标题必填(≤128 字)");
        }
        Task t = new Task();
        t.setId(exist.getId());
        t.setTitle(bo.getTitle());
        t.setDescription(bo.getDescription());
        t.setDeadline(bo.getDeadline());
        t.setAssigneeUserIds(jsonOfIds(bo.getAssigneeUserIds()));
        t.setAuditMode(StringUtils.isBlank(bo.getAuditMode()) ? Task.AUDIT_PUBLISHER : bo.getAuditMode());
        t.setAuditorUserIds(jsonOfIds(bo.getAuditorUserIds()));
        t.setUpdateBy(userId);
        if (bo.getFields() == null) {
            // 三审 dspro P2-7: PUT 是全量替换语义——fields 缺席不得静默清空字段定义
            throw new ServiceException("fields 必填(编辑为全量提交, 空字段请传 [])");
        }
        requireDescription(bo.getDescription());
        requireAssigneesExist(t.getAssigneeUserIds());
        requireAuditorsExist(t);
        if (taskMapper.updateDraftJsonb(t) == 0) {
            throw new ServiceException("任务已发布或状态已变化, 请刷新重试");
        }
        fieldMapper.deleteByTask(exist.getId());
        saveFields(exist.getId(), exist.getTenantId(), bo.getFields());
    }

    /** 发布: 校验(≥1 字段 + ≥1 成员 + ASSIGNED 需审核人) → CAS 迁移 → 事件 + 通知
     *  (三审 dspro P2-1: 加事务——CAS 与通知/事件同生共死, 不再"已发布却报错") */
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long taskId, Long userId) {
        Task t = requireOwned(taskId, userId);
        TaskStateMachine.transition(t.getStatus(), Task.STATUS_COLLECTING);
        if (fieldsOf(taskId).isEmpty()) {
            throw new ServiceException("发布前至少定义 1 个字段");
        }
        if (t.assigneeList().isEmpty()) {
            throw new ServiceException("发布前至少指定 1 名目标成员");
        }
        if (Task.AUDIT_ASSIGNED.equals(t.getAuditMode()) && t.auditorList().isEmpty()) {
            throw new ServiceException("指定审核人模式下审核人不可为空");
        }
        // 全审回修(dspro P1): 建档后审核人可能被删——发布前复查存在性, 防止 ASSIGNED 任务进无出口终局
        if (Task.AUDIT_ASSIGNED.equals(t.getAuditMode())) {
            requireAssigneesExist(t.getAuditorUserIds());
        }
        if (taskMapper.transitionStatus(taskId, t.getStatus(), Task.STATUS_COLLECTING) == 0) {
            throw new ServiceException("任务状态已变化, 请刷新重试");
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("taskId", taskId);
        props.put("title", safe(t.getTitle()));
        props.put("deadline", t.getDeadline() == null ? "" : String.valueOf(t.getDeadline().getTime()));
        props.put("assigneeCount", t.assigneeList().size());
        publishEvent(SelfMonitorEvents.TASK_PUBLISHED, ProfileUids.uid("task", "published", taskId), taskId, props, userId);
        for (Long uid : t.assigneeList()) {
            noticeService.notify(uid, Notice.TYPE_TASK_PUBLISHED,
                "新任务: " + t.getTitle(), "你有一个新的填报任务, 截止 " + safe(t.getDeadline()), taskId);
        }
    }

    /** 三批④（2026-11-01）：AI 建的草稿任务撤回（对话内完成卡撤回按钮）。
     *  条件删除 CAS：仅 DRAFT 且本人发布才物理删（AI 草稿无业务数据，物理删=撤回语义，
     *  与 update() 重存字段行的物理删同口径）；发布竞态下 CAS 0 行=人话拒绝、事务回滚，
     *  已发布任务在任何时序下不会被误删；字段行仅在任务行 CAS 成功后同事务清理。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long taskId, Long userId) {
        requireOwned(taskId, userId);
        if (taskMapper.deleteDraftOwned(taskId, userId) == 0) {
            throw new ServiceException("任务已发布或已推进，不能撤回；请到任务管理页手动处理");
        }
        fieldMapper.deleteByTask(taskId);
    }

    /** 关闭(DRAFT/COLLECTING → CLOSED): 停止收集, 已有提交留痕可查。
     *  三审 dspro P1-4: 在途 PENDING_AUDIT 拦截——关了任务待审就永远无入口, 先审完再关 */
    @Transactional(rollbackFor = Exception.class)
    public void close(Long taskId, Long userId) {
        Task t = requireOwned(taskId, userId);
        TaskStateMachine.transition(t.getStatus(), Task.STATUS_CLOSED);
        if (Task.STATUS_COLLECTING.equals(t.getStatus())) {
            Long pending = submissionCountPending(taskId);
            if (pending > 0) {
                throw new ServiceException("仍有 " + pending + " 条待审提交, 请先审核完成再关闭");
            }
        }
        if (taskMapper.transitionStatus(taskId, t.getStatus(), Task.STATUS_CLOSED) == 0) {
            throw new ServiceException("任务状态已变化, 请刷新重试");
        }
        publishEvent(SelfMonitorEvents.TASK_CLOSED, ProfileUids.uid("task", "closed", taskId), taskId,
            Map.of("taskId", taskId), userId);
    }

    /** 任务说明长度护栏(全审 dspro P2): varchar(1000) 溢出会 500 而非友好话术 */
    private void requireDescription(String description) {
        if (description != null && description.length() > 1000) {
            throw new ServiceException("任务说明不可超过 1000 字");
        }
    }

    /** 审核人校验(全审 dspro P1): ASSIGNED 模式非空 + 全员在册(复用成员存在性逻辑) */
    private void requireAuditorsExist(Task t) {
        if (!Task.AUDIT_ASSIGNED.equals(t.getAuditMode())) {
            return;
        }
        List<Long> auditors = com.smartdock.mission.support.Jsons.longList(t.getAuditorUserIds());
        if (auditors.isEmpty()) {
            return; // 发布时另有非空拦截; DRAFT 期允许先留空
        }
        requireAssigneesExist(t.getAuditorUserIds());
    }

    /** 2026-10-30 题12：详情页把目标成员回填成姓名（成员被删时兜底「已注销用户」），
     *  列表人数与详情名单同源——不再出现「列表 3 人 / 详情 —」。 */
    public Task withAssigneeNames(Task task) {
        java.util.List<Long> ids = task.assigneeList();
        if (ids.isEmpty()) {
            task.setAssigneeNames(java.util.List.of());
            return task;
        }
        java.util.Map<Long, String> names = subjectNameResolver.resolveBatch(ids);
        java.util.List<String> out = new java.util.ArrayList<>(ids.size());
        for (Long id : ids) {
            out.add(names.getOrDefault(id, "已注销用户"));
        }
        task.setAssigneeNames(out);
        return task;
    }

    /** 目标成员存在性校验(三审 glm53 P2-11): 跨租户/幽灵 userId 在建档时即拒,
     *  不等发布后通知静默落空; 租户过滤由拦截器保证"存在"即"同租户"。 */
    void requireAssigneesExist(String assigneeUserIdsJson) {
        java.util.List<Long> ids = com.smartdock.mission.support.Jsons.longList(assigneeUserIdsJson);
        if (ids.isEmpty()) {
            return;
        }
        java.util.Set<Long> found = userLookupMapper.selectBriefsByIds(ids).stream()
            .map(m -> ((Number) m.get("user_id")).longValue())
            .collect(java.util.stream.Collectors.toSet());
        java.util.List<Long> missing = ids.stream().filter(i -> !found.contains(i)).toList();
        if (!missing.isEmpty()) {
            throw new ServiceException("目标成员不存在或不属于本单位: " + missing);
        }
    }

    /** 在途待审数(close 拦截用) */
    private Long submissionCountPending(Long taskId) {
        return submissionMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Submission>()
            .eq(Submission::getTaskId, taskId)
            .eq(Submission::getStatus, Submission.STATUS_PENDING_AUDIT));
    }

    /** 发布人任务管理页 */
    public TableDataInfo<Task> page(String title, String status, PageQuery pq, Long userId) {
        Page<Task> page = taskMapper.selectPage(pq.build(), new LambdaQueryWrapper<Task>()
            .eq(Task::getPublisherUserId, userId)
            .like(StringUtils.isNotBlank(title), Task::getTitle, title)
            .eq(StringUtils.isNotBlank(status), Task::getStatus, status)
            .orderByDesc(Task::getId));
        return TableDataInfo.build(page);
    }

    /** 成员: 我的任务(被指派且在收集中的)。 */
    public TableDataInfo<Task> myTasks(PageQuery pq, Long userId) {
        return myTasks(pq, userId, false);
    }

    /** 成员: 我的任务(被指派且在收集中的)。
     *  全审回修(UI 席 P1): 回填 mySubmissionStatus(本人最新提交态), 前端待办页不再逐任务拉详情。
     *  todoOnly=true 按待办口径(未提交或被退回)服务端过滤, 行列表与 total 同口径——
     *  U-03 修复: 前端滤行而计数不滤, 会出「KPI/共 N 条却说暂无待办」的自相矛盾 */
    public TableDataInfo<Task> myTasks(PageQuery pq, Long userId, boolean todoOnly) {
        LambdaQueryWrapper<Task> wrapper = collectedByMe(userId);
        if (todoOnly) {
            Set<Long> notTodo = TodoPolicy.notTodoTaskIds(submissionMapper.selectList(
                new LambdaQueryWrapper<Submission>().eq(Submission::getSubmitterUserId, userId)));
            if (!notTodo.isEmpty()) {
                wrapper.notIn(Task::getId, notTodo);
            }
        }
        Page<Task> page = taskMapper.selectPage(pq.build(), wrapper
            .orderByDesc(Task::getId));
        if (!page.getRecords().isEmpty()) {
            List<Long> taskIds = page.getRecords().stream().map(Task::getId).toList();
            Map<Long, Submission> latest = new java.util.HashMap<>();
            for (Submission s : submissionMapper.selectList(new LambdaQueryWrapper<Submission>()
                    .eq(Submission::getSubmitterUserId, userId)
                    .in(Submission::getTaskId, taskIds)
                    .orderByDesc(Submission::getVersion))) {
                latest.putIfAbsent(s.getTaskId(), s);
            }
            page.getRecords().forEach(t -> {
                Submission s = latest.get(t.getId());
                t.setMySubmissionStatus(s == null ? null : s.getStatus());
            });
        }
        return TableDataInfo.build(page);
    }

    /** 助手 @ 候选: 我相关(发布或被指派)的收集态任务, 标题模糊 */
    public List<Task> mentionCandidates(String keyword, int limit, Long me) {
        return taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getStatus, Task.STATUS_COLLECTING)
                .and(w -> w.eq(Task::getPublisherUserId, me)
                    .or().apply("assignee_user_ids @> ({0}::jsonb)", Jsons.toJson(List.of(me))))
                .like(StringUtils.isNotBlank(keyword), Task::getTitle, keyword)
                .orderByDesc(Task::getId)
                .last("LIMIT " + Math.min(Math.max(1, limit), 50)));
    }

    /** 我被指派的收集态任务条件(工具 my_todos 复用) */
    public LambdaQueryWrapper<Task> collectedByMe(Long userId) {
        return new LambdaQueryWrapper<Task>()
            .eq(Task::getStatus, Task.STATUS_COLLECTING)
            .apply("assignee_user_ids @> ({0}::jsonb)", Jsons.toJson(List.of(userId)));
    }

    /** 加载并校验归属(发布人) */
    public Task requireOwned(Long taskId, Long userId) {
        Task t = taskMapper.selectById(taskId);
        if (t == null) {
            throw new ServiceException("任务不存在");
        }
        if (!userId.equals(t.getPublisherUserId())) {
            throw new ServiceException("仅任务发布人可操作");
        }
        return t;
    }

    /** 加载并校验可见(发布人/成员/审核人) */
    public Task requireVisible(Long taskId, Long userId) {
        Task t = taskMapper.selectById(taskId);
        if (t == null) {
            throw new ServiceException("任务不存在");
        }
        if (!userId.equals(t.getPublisherUserId()) && !t.assignedTo(userId) && !t.auditorIs(userId)) {
            throw new ServiceException("无权查看该任务");
        }
        return t;
    }

    public List<TaskField> fieldsOf(Long taskId) {
        return fieldMapper.selectList(new LambdaQueryWrapper<TaskField>()
            .eq(TaskField::getTaskId, taskId)
            .orderByAsc(TaskField::getOrderNum));
    }

    // ------------------------------------------------------------ 内部

    private void saveFields(Long taskId, String tenantId, List<TaskBo.FieldBo> fields) {
        if (fields == null) {
            return;
        }
        // 三审 glm53 P2-9: fieldCode 空/重复/超长在定义期友好校验, 不等 uk 索引炸 500
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (TaskBo.FieldBo f : fields) {
            if (StringUtils.isBlank(f.getFieldCode()) || f.getFieldCode().length() > 64
                || !f.getFieldCode().matches("[a-zA-Z0-9_]+")) {
                throw new ServiceException("字段编码必填(≤64 位字母数字下划线): " + f.getFieldCode());
            }
            if (!seen.add(f.getFieldCode())) {
                throw new ServiceException("字段编码重复: " + f.getFieldCode());
            }
            if (StringUtils.isBlank(f.getFieldName()) || f.getFieldName().length() > 64) {
                throw new ServiceException("字段名称必填(≤64 字): " + f.getFieldCode());
            }
            if (!TaskField.TYPE_TEXT.equals(f.getFieldType())
                && !TaskField.TYPE_NUMBER.equals(f.getFieldType())
                && !TaskField.TYPE_DATE.equals(f.getFieldType())
                && !TaskField.TYPE_SELECT.equals(f.getFieldType())
                && !TaskField.TYPE_MULTI_SELECT.equals(f.getFieldType())
                && !TaskField.TYPE_ATTACHMENT.equals(f.getFieldType())) {
                throw new ServiceException("字段类型只认 text/number/date/select/multi_select/attachment: " + f.getFieldType());
            }
        }
        int order = 0;
        for (TaskBo.FieldBo f : fields) {
            TaskField e = new TaskField();
            e.setId(IdWorker.getId());
            e.setTenantId(tenantId);
            e.setTaskId(taskId);
            e.setFieldCode(f.getFieldCode());
            e.setFieldName(f.getFieldName());
            e.setFieldType(StringUtils.isBlank(f.getFieldType()) ? TaskField.TYPE_TEXT : f.getFieldType());
            e.setRequired("1".equals(f.getRequired()) ? "1" : "0");
            e.setOptions(f.getOptions() == null ? "[]" : Jsons.toJson(f.getOptions()));
            e.setOrderNum(f.getOrderNum() == null ? order : f.getOrderNum());
            fieldMapper.insertJsonb(e);
            order++;
        }
    }

    private String jsonOfIds(List<Long> ids) {
        return ids == null ? "[]" : Jsons.toJson(ids);
    }

    /**
     * 事件翻译出口: 失败仅 warn 不阻断业务——事件是画像的原料不是业务事务的一部分,
     * event_uid 幂等键保证事后补发可去重。
     */
    private void publishEvent(String eventType, String eventUid, Long taskId, Map<String, Object> props,
                              Long subjectUserId) {
        ProfileEventBo e = new ProfileEventBo();
        e.setSource(com.smartdock.profile.event.ProfileSources.UNIFIED);
        e.setEventType(eventType);
        e.setEventUid(eventUid);
        e.setSubjectType("user");
        e.setSubjectId(String.valueOf(subjectUserId));
        e.setSubjectName(subjectNameResolver.resolve(subjectUserId));
        e.setOccurredTime(new Date());
        e.setProps(new LinkedHashMap<>(props));
        try {
            // 单源重构 C 片: 本产品站内操作属自监控白名单——静默发布, 不逐条触发打分/汇总
            eventPublisher.publishQuiet(e);
        } catch (Exception ex) {
            log.warn("[Dcs] profile event failed type={} uid={} task={}: {}",
                eventType, eventUid, taskId, ex.getMessage());
        }
    }

    private String currentTenant() {
        // 工具线程无 Sa-Token 上下文: TenantHelper(含 dynamic)优先, 兜底缺省租户
        try {
            String t = org.dromara.common.tenant.helper.TenantHelper.getTenantId();
            if (t != null && !t.isBlank()) {
                return t;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        String login = LoginHelper.getTenantId();
        // 批11 回修(终审 P1): LoginHelper 不抛异常只回 null——判空后 fail-fast, 不再静默默认租户
        if (login != null && !login.isBlank()) {
            return login;
        }
        return com.smartdock.profile.engine.ProfileTenantContext.require();
    }

    private static String safe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
