package com.smartdock.dcs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.dcs.domain.DcsNotice;
import com.smartdock.dcs.domain.DcsTask;
import com.smartdock.dcs.domain.DcsTaskField;
import com.smartdock.dcs.domain.bo.DcsTaskBo;
import com.smartdock.dcs.mapper.DcsTaskFieldMapper;
import com.smartdock.dcs.domain.DcsSubmission;
import com.smartdock.dcs.domain.DcsTodoPolicy;
import com.smartdock.dcs.mapper.DcsSubmissionMapper;
import com.smartdock.dcs.mapper.DcsTaskMapper;
import com.smartdock.dcs.statemachine.DcsTaskStateMachine;
import com.smartdock.dcs.support.DcsJsons;
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
public class DcsTaskService {

    private final DcsTaskMapper taskMapper;
    private final DcsTaskFieldMapper fieldMapper;
    private final DcsSubmissionMapper submissionMapper;
    private final com.smartdock.dcs.mapper.DcsUserLookupMapper userLookupMapper;
    private final DcsNoticeService noticeService;
    private final ProfileEventPublisher eventPublisher;
    private final com.smartdock.dcs.support.DcsSubjectNameResolver subjectNameResolver;

    /** 新建任务(DRAFT + 字段定义落库) */
    @Transactional(rollbackFor = Exception.class)
    public DcsTask create(DcsTaskBo bo, Long userId) {
        if (StringUtils.isBlank(bo.getTitle()) || bo.getTitle().length() > 128) {
            throw new ServiceException("任务标题必填(≤128 字)");
        }
        DcsTask t = new DcsTask();
        t.setId(IdWorker.getId());
        t.setTenantId(currentTenant());
        t.setTitle(bo.getTitle());
        t.setDescription(bo.getDescription());
        t.setDeadline(bo.getDeadline());
        t.setPublisherUserId(userId);
        t.setStatus(DcsTask.STATUS_DRAFT);
        t.setAssigneeUserIds(jsonOfIds(bo.getAssigneeUserIds()));
        t.setAuditMode(StringUtils.isBlank(bo.getAuditMode()) ? DcsTask.AUDIT_PUBLISHER : bo.getAuditMode());
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
    public void update(DcsTaskBo bo, Long userId) {
        if (bo.getId() == null) {
            throw new ServiceException("任务 id 必填");
        }
        DcsTask exist = requireOwned(bo.getId(), userId);
        if (!DcsTask.STATUS_DRAFT.equals(exist.getStatus())) {
            throw new ServiceException("仅 DRAFT 任务可编辑");
        }
        if (StringUtils.isBlank(bo.getTitle()) || bo.getTitle().length() > 128) {
            throw new ServiceException("任务标题必填(≤128 字)");
        }
        DcsTask t = new DcsTask();
        t.setId(exist.getId());
        t.setTitle(bo.getTitle());
        t.setDescription(bo.getDescription());
        t.setDeadline(bo.getDeadline());
        t.setAssigneeUserIds(jsonOfIds(bo.getAssigneeUserIds()));
        t.setAuditMode(StringUtils.isBlank(bo.getAuditMode()) ? DcsTask.AUDIT_PUBLISHER : bo.getAuditMode());
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
        DcsTask t = requireOwned(taskId, userId);
        DcsTaskStateMachine.transition(t.getStatus(), DcsTask.STATUS_COLLECTING);
        if (fieldsOf(taskId).isEmpty()) {
            throw new ServiceException("发布前至少定义 1 个字段");
        }
        if (t.assigneeList().isEmpty()) {
            throw new ServiceException("发布前至少指定 1 名目标成员");
        }
        if (DcsTask.AUDIT_ASSIGNED.equals(t.getAuditMode()) && t.auditorList().isEmpty()) {
            throw new ServiceException("指定审核人模式下审核人不可为空");
        }
        // 全审回修(dspro P1): 建档后审核人可能被删——发布前复查存在性, 防止 ASSIGNED 任务进无出口终局
        if (DcsTask.AUDIT_ASSIGNED.equals(t.getAuditMode())) {
            requireAssigneesExist(t.getAuditorUserIds());
        }
        if (taskMapper.transitionStatus(taskId, t.getStatus(), DcsTask.STATUS_COLLECTING) == 0) {
            throw new ServiceException("任务状态已变化, 请刷新重试");
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("taskId", taskId);
        props.put("title", safe(t.getTitle()));
        props.put("deadline", t.getDeadline() == null ? "" : String.valueOf(t.getDeadline().getTime()));
        props.put("assigneeCount", t.assigneeList().size());
        publishEvent(SelfMonitorEvents.TASK_PUBLISHED, ProfileUids.uid("task", "published", taskId), taskId, props, userId);
        for (Long uid : t.assigneeList()) {
            noticeService.notify(uid, DcsNotice.TYPE_TASK_PUBLISHED,
                "新任务: " + t.getTitle(), "你有一个新的填报任务, 截止 " + safe(t.getDeadline()), taskId);
        }
    }

    /** 关闭(DRAFT/COLLECTING → CLOSED): 停止收集, 已有提交留痕可查。
     *  三审 dspro P1-4: 在途 PENDING_AUDIT 拦截——关了任务待审就永远无入口, 先审完再关 */
    @Transactional(rollbackFor = Exception.class)
    public void close(Long taskId, Long userId) {
        DcsTask t = requireOwned(taskId, userId);
        DcsTaskStateMachine.transition(t.getStatus(), DcsTask.STATUS_CLOSED);
        if (DcsTask.STATUS_COLLECTING.equals(t.getStatus())) {
            Long pending = submissionCountPending(taskId);
            if (pending > 0) {
                throw new ServiceException("仍有 " + pending + " 条待审提交, 请先审核完成再关闭");
            }
        }
        if (taskMapper.transitionStatus(taskId, t.getStatus(), DcsTask.STATUS_CLOSED) == 0) {
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
    private void requireAuditorsExist(DcsTask t) {
        if (!DcsTask.AUDIT_ASSIGNED.equals(t.getAuditMode())) {
            return;
        }
        List<Long> auditors = com.smartdock.dcs.support.DcsJsons.longList(t.getAuditorUserIds());
        if (auditors.isEmpty()) {
            return; // 发布时另有非空拦截; DRAFT 期允许先留空
        }
        requireAssigneesExist(t.getAuditorUserIds());
    }

    /** 目标成员存在性校验(三审 glm53 P2-11): 跨租户/幽灵 userId 在建档时即拒,
     *  不等发布后通知静默落空; 租户过滤由拦截器保证"存在"即"同租户"。 */
    void requireAssigneesExist(String assigneeUserIdsJson) {
        java.util.List<Long> ids = com.smartdock.dcs.support.DcsJsons.longList(assigneeUserIdsJson);
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
        return submissionMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DcsSubmission>()
            .eq(DcsSubmission::getTaskId, taskId)
            .eq(DcsSubmission::getStatus, DcsSubmission.STATUS_PENDING_AUDIT));
    }

    /** 发布人任务管理页 */
    public TableDataInfo<DcsTask> page(String title, String status, PageQuery pq, Long userId) {
        Page<DcsTask> page = taskMapper.selectPage(pq.build(), new LambdaQueryWrapper<DcsTask>()
            .eq(DcsTask::getPublisherUserId, userId)
            .like(StringUtils.isNotBlank(title), DcsTask::getTitle, title)
            .eq(StringUtils.isNotBlank(status), DcsTask::getStatus, status)
            .orderByDesc(DcsTask::getId));
        return TableDataInfo.build(page);
    }

    /** 成员: 我的任务(被指派且在收集中的)。 */
    public TableDataInfo<DcsTask> myTasks(PageQuery pq, Long userId) {
        return myTasks(pq, userId, false);
    }

    /** 成员: 我的任务(被指派且在收集中的)。
     *  全审回修(UI 席 P1): 回填 mySubmissionStatus(本人最新提交态), 前端待办页不再逐任务拉详情。
     *  todoOnly=true 按待办口径(未提交或被退回)服务端过滤, 行列表与 total 同口径——
     *  U-03 修复: 前端滤行而计数不滤, 会出「KPI/共 N 条却说暂无待办」的自相矛盾 */
    public TableDataInfo<DcsTask> myTasks(PageQuery pq, Long userId, boolean todoOnly) {
        LambdaQueryWrapper<DcsTask> wrapper = collectedByMe(userId);
        if (todoOnly) {
            Set<Long> notTodo = DcsTodoPolicy.notTodoTaskIds(submissionMapper.selectList(
                new LambdaQueryWrapper<DcsSubmission>().eq(DcsSubmission::getSubmitterUserId, userId)));
            if (!notTodo.isEmpty()) {
                wrapper.notIn(DcsTask::getId, notTodo);
            }
        }
        Page<DcsTask> page = taskMapper.selectPage(pq.build(), wrapper
            .orderByDesc(DcsTask::getId));
        if (!page.getRecords().isEmpty()) {
            List<Long> taskIds = page.getRecords().stream().map(DcsTask::getId).toList();
            Map<Long, DcsSubmission> latest = new java.util.HashMap<>();
            for (DcsSubmission s : submissionMapper.selectList(new LambdaQueryWrapper<DcsSubmission>()
                    .eq(DcsSubmission::getSubmitterUserId, userId)
                    .in(DcsSubmission::getTaskId, taskIds)
                    .orderByDesc(DcsSubmission::getVersion))) {
                latest.putIfAbsent(s.getTaskId(), s);
            }
            page.getRecords().forEach(t -> {
                DcsSubmission s = latest.get(t.getId());
                t.setMySubmissionStatus(s == null ? null : s.getStatus());
            });
        }
        return TableDataInfo.build(page);
    }

    /** 助手 @ 候选: 我相关(发布或被指派)的收集态任务, 标题模糊 */
    public List<DcsTask> mentionCandidates(String keyword, int limit, Long me) {
        return taskMapper.selectList(new LambdaQueryWrapper<DcsTask>()
                .eq(DcsTask::getStatus, DcsTask.STATUS_COLLECTING)
                .and(w -> w.eq(DcsTask::getPublisherUserId, me)
                    .or().apply("assignee_user_ids @> ({0}::jsonb)", DcsJsons.toJson(List.of(me))))
                .like(StringUtils.isNotBlank(keyword), DcsTask::getTitle, keyword)
                .orderByDesc(DcsTask::getId)
                .last("LIMIT " + Math.min(Math.max(1, limit), 50)));
    }

    /** 我被指派的收集态任务条件(工具 my_todos 复用) */
    public LambdaQueryWrapper<DcsTask> collectedByMe(Long userId) {
        return new LambdaQueryWrapper<DcsTask>()
            .eq(DcsTask::getStatus, DcsTask.STATUS_COLLECTING)
            .apply("assignee_user_ids @> ({0}::jsonb)", DcsJsons.toJson(List.of(userId)));
    }

    /** 加载并校验归属(发布人) */
    public DcsTask requireOwned(Long taskId, Long userId) {
        DcsTask t = taskMapper.selectById(taskId);
        if (t == null) {
            throw new ServiceException("任务不存在");
        }
        if (!userId.equals(t.getPublisherUserId())) {
            throw new ServiceException("仅任务发布人可操作");
        }
        return t;
    }

    /** 加载并校验可见(发布人/成员/审核人) */
    public DcsTask requireVisible(Long taskId, Long userId) {
        DcsTask t = taskMapper.selectById(taskId);
        if (t == null) {
            throw new ServiceException("任务不存在");
        }
        if (!userId.equals(t.getPublisherUserId()) && !t.assignedTo(userId) && !t.auditorIs(userId)) {
            throw new ServiceException("无权查看该任务");
        }
        return t;
    }

    public List<DcsTaskField> fieldsOf(Long taskId) {
        return fieldMapper.selectList(new LambdaQueryWrapper<DcsTaskField>()
            .eq(DcsTaskField::getTaskId, taskId)
            .orderByAsc(DcsTaskField::getOrderNum));
    }

    // ------------------------------------------------------------ 内部

    private void saveFields(Long taskId, String tenantId, List<DcsTaskBo.DcsFieldBo> fields) {
        if (fields == null) {
            return;
        }
        // 三审 glm53 P2-9: fieldCode 空/重复/超长在定义期友好校验, 不等 uk 索引炸 500
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (DcsTaskBo.DcsFieldBo f : fields) {
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
            if (!DcsTaskField.TYPE_TEXT.equals(f.getFieldType())
                && !DcsTaskField.TYPE_NUMBER.equals(f.getFieldType())
                && !DcsTaskField.TYPE_DATE.equals(f.getFieldType())
                && !DcsTaskField.TYPE_SELECT.equals(f.getFieldType())
                && !DcsTaskField.TYPE_MULTI_SELECT.equals(f.getFieldType())
                && !DcsTaskField.TYPE_ATTACHMENT.equals(f.getFieldType())) {
                throw new ServiceException("字段类型只认 text/number/date/select/multi_select/attachment: " + f.getFieldType());
            }
        }
        int order = 0;
        for (DcsTaskBo.DcsFieldBo f : fields) {
            DcsTaskField e = new DcsTaskField();
            e.setId(IdWorker.getId());
            e.setTenantId(tenantId);
            e.setTaskId(taskId);
            e.setFieldCode(f.getFieldCode());
            e.setFieldName(f.getFieldName());
            e.setFieldType(StringUtils.isBlank(f.getFieldType()) ? DcsTaskField.TYPE_TEXT : f.getFieldType());
            e.setRequired("1".equals(f.getRequired()) ? "1" : "0");
            e.setOptions(f.getOptions() == null ? "[]" : DcsJsons.toJson(f.getOptions()));
            e.setOrderNum(f.getOrderNum() == null ? order : f.getOrderNum());
            fieldMapper.insertJsonb(e);
            order++;
        }
    }

    private String jsonOfIds(List<Long> ids) {
        return ids == null ? "[]" : DcsJsons.toJson(ids);
    }

    /**
     * 事件翻译出口: 失败仅 warn 不阻断业务——事件是画像的原料不是业务事务的一部分,
     * event_uid 幂等键保证事后补发可去重。
     */
    private void publishEvent(String eventType, String eventUid, Long taskId, Map<String, Object> props,
                              Long subjectUserId) {
        ProfileEventBo e = new ProfileEventBo();
        e.setSource("dcs");
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
