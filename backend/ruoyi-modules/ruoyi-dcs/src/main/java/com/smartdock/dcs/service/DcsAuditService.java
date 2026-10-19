package com.smartdock.dcs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.dcs.domain.DcsAudit;
import com.smartdock.dcs.domain.DcsNotice;
import com.smartdock.dcs.domain.DcsSubmission;
import com.smartdock.dcs.domain.DcsTask;
import com.smartdock.dcs.mapper.DcsAuditMapper;
import com.smartdock.dcs.mapper.DcsSubmissionMapper;
import com.smartdock.dcs.mapper.DcsTaskMapper;
import com.smartdock.dcs.statemachine.DcsSubmissionStateMachine;
import com.smartdock.dcs.support.DcsJsons;
import com.smartdock.profile.ProfileEventPublisher;
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

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 审核服务: 待审列表 + 通过/退回。
 *
 * <p>事件翻译点③④: decide(PASS)→data.audit_passed、decide(REJECT)→data.audit_rejected。
 * 审核主体(subjectId)=提交人——画像维度按提交人口径聚合。审核落状态 CAS 防并发双审。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DcsAuditService {

    private final DcsAuditMapper auditMapper;
    private final DcsSubmissionMapper submissionMapper;
    private final DcsTaskMapper taskMapper;
    private final DcsNoticeService noticeService;
    private final ProfileEventPublisher eventPublisher;
    private final com.smartdock.dcs.support.DcsSubjectNameResolver subjectNameResolver;

    /** 待审列表: 我有权审的任务下的 PENDING_AUDIT 提交。
     *  三审 dspro P2-9: 按 audit_mode 分支——PUBLISHER 只认我发布的, ASSIGNED 只认审核人含我
     *  (原 or 条件让直审发布人看见 ASSIGNED 任务却无权审, 点审核必报错) */
    public TableDataInfo<DcsSubmission> pending(PageQuery pq, Long me) {
        List<Long> taskIds = taskMapper.selectList(new LambdaQueryWrapper<DcsTask>()
                // 全审回修: 含 CLOSED——关闭后仍要能看到存量待审清尾巴(decide 已放行)
                .in(DcsTask::getStatus, DcsTask.STATUS_COLLECTING, DcsTask.STATUS_CLOSED)
                .and(w -> w
                    // PUBLISHER 直审: 我发布的且未指定审核人
                    .and(x -> x.eq(DcsTask::getPublisherUserId, me)
                        .eq(DcsTask::getAuditMode, DcsTask.AUDIT_PUBLISHER))
                    // ASSIGNED 指定审核: 模式吻合且审核人列表含我
                    // (全审 dspro P2: 原分支缺 audit_mode 谓词, PUBLISHER 任务带上历史 auditor 列表即污染队列)
                    // 注意写法: 必须 .or(consumer)——E2E 实测 .or().and(consumer) 生成的是 AND 连接,
                    // 两分支互斥导致待审队列恒空(回修E 发现)
                    .or(x -> x.eq(DcsTask::getAuditMode, DcsTask.AUDIT_ASSIGNED)
                        .apply("auditor_user_ids @> ({0}::jsonb)", DcsJsons.toJson(List.of(me))))))
                .stream().map(DcsTask::getId).collect(Collectors.toList());
        if (taskIds.isEmpty()) {
            return TableDataInfo.build(new Page<>(pq.getPageNum(), pq.getPageSize()));
        }
        Page<DcsSubmission> page = submissionMapper.selectPage(pq.build(), new LambdaQueryWrapper<DcsSubmission>()
            .in(DcsSubmission::getTaskId, taskIds)
            .eq(DcsSubmission::getStatus, DcsSubmission.STATUS_PENDING_AUDIT)
            .orderByAsc(DcsSubmission::getSubmittedAt));
        return TableDataInfo.build(page);
    }

    /** 审核决定: PASS/REJECT(CAS 防并发; 事件 + 提交通知)。
     *  三审 dspro P1-3: 加事务——CAS 与审核留痕/通知同生共死, 不再"状态已变但轨迹缺失" */
    @Transactional(rollbackFor = Exception.class)
    public void decide(Long submissionId, String decision, String comment, Long me) {
        if (!DcsAudit.DECISION_PASS.equals(decision) && !DcsAudit.DECISION_REJECT.equals(decision)) {
            throw new ServiceException("审核决定只认 PASS/REJECT");
        }
        DcsSubmission s = submissionMapper.selectById(submissionId);
        if (s == null) {
            throw new ServiceException("提交不存在");
        }
        DcsTask task = taskMapper.selectById(s.getTaskId());
        if (task == null) {
            throw new ServiceException("任务不存在");
        }
        if (!task.auditorIs(me)) {
            throw new ServiceException("你不是该任务的审核人");
        }
        // 全审回修(dspro P1, 覆盖三审 glm53 P1-2 拍板): close 与 submit 的在读-判-写窗口内可并发
        // 造出「CLOSED 任务挂着 PENDING_AUDIT」——若此处硬拦, 该提交永久既不可审也不可重提。
        // 口径改为: CLOSED 后仍可审存量待审(清理尾巴), 仅未发布(DRAFT)不可审。
        if (DcsTask.STATUS_DRAFT.equals(task.getStatus())) {
            throw new ServiceException("任务未发布, 不可审核");
        }
        String to = DcsAudit.DECISION_PASS.equals(decision)
            ? DcsSubmission.STATUS_APPROVED : DcsSubmission.STATUS_REJECTED;
        DcsSubmissionStateMachine.transition(s.getStatus(), to);
        if (submissionMapper.auditStatus(submissionId, to) == 0) {
            throw new ServiceException("该提交已被审核, 请刷新");
        }

        DcsAudit a = new DcsAudit();
        a.setId(IdWorker.getId());
        a.setTenantId(s.getTenantId());
        a.setSubmissionId(submissionId);
        a.setTaskId(s.getTaskId());
        a.setAuditorUserId(me);
        a.setDecision(decision);
        a.setComment(StringUtils.isBlank(comment) ? null : comment.substring(0, Math.min(500, comment.length())));
        a.setCreateTime(new Date());
        auditMapper.insert(a);

        // 事件翻译点③④: 主体=提交人
        String eventType = DcsAudit.DECISION_PASS.equals(decision) ? "data.audit_passed" : "data.audit_rejected";
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("taskId", s.getTaskId());
        props.put("submissionId", submissionId);
        props.put("version", s.getVersion());
        publishEvent(eventType, ProfileUids.uid("audit", decision.toLowerCase(), a.getId()),
            s.getSubmitterUserId(), props);

        String verdict = DcsAudit.DECISION_PASS.equals(decision) ? "已通过" : "被退回";
        noticeService.notify(s.getSubmitterUserId(), DcsNotice.TYPE_AUDIT_RESULT,
            "提交" + verdict + ": " + task.getTitle(),
            "你的提交(v" + s.getVersion() + ")" + verdict
                + (StringUtils.isBlank(comment) ? "" : ", 意见: " + comment), task.getId());
    }

    /** 某提交的审核轨迹(详情页用)。三审 dspro P1-5: 加任务级鉴权——
     *  该任务审核人/发布人/提交本人三方可看, 其他持审核权限者不看跨任务评语 */
    public List<DcsAudit> trailOf(Long submissionId, Long me) {
        DcsSubmission s = submissionMapper.selectById(submissionId);
        if (s == null) {
            throw new ServiceException("提交不存在");
        }
        DcsTask t = taskMapper.selectById(s.getTaskId());
        boolean allowed = t != null && (t.auditorIs(me)
            || me.equals(t.getPublisherUserId())
            || me.equals(s.getSubmitterUserId()));
        if (!allowed) {
            throw new ServiceException("无权查看该提交的审核轨迹");
        }
        return auditMapper.selectList(new LambdaQueryWrapper<DcsAudit>()
            .eq(DcsAudit::getSubmissionId, submissionId)
            .orderByAsc(DcsAudit::getId));
    }

    private void publishEvent(String eventType, String eventUid, Long subjectUserId, Map<String, Object> props) {
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
            eventPublisher.publish(e);
        } catch (Exception ex) {
            log.warn("[Dcs] profile event failed type={} uid={}: {}", eventType, eventUid, ex.getMessage());
        }
    }
}
