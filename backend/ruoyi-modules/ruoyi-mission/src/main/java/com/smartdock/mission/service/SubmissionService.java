package com.smartdock.mission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.mission.domain.Notice;
import com.smartdock.mission.domain.Submission;
import com.smartdock.mission.domain.Task;
import com.smartdock.mission.mapper.SubmissionMapper;
import com.smartdock.mission.support.Jsons;
import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.monitor.SelfMonitorEvents;
import com.smartdock.profile.event.ProfileEventBo;
import com.smartdock.profile.ProfileUids;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提交服务: 提交/重提(新版本行)、我的提交历史。
 *
 * <p>事件翻译点②: submit→data.submitted(props.responseHours=提交距发布小时数,
 * 直接喂画像内置维度 9602)。提交即通知审核人。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final SubmissionMapper submissionMapper;
    private final TaskService taskService;
    private final TaskFieldValidator validator;
    private final NoticeService noticeService;
    private final ProfileEventPublisher eventPublisher;
    private final com.smartdock.mission.support.SubjectNameResolver subjectNameResolver;

    /** 提交(首提或 REJECTED 重提; PENDING_AUDIT/APPROVED 在途则拒)。
     *  三审 dspro P1-1: 读-判-写在 READ COMMITTED 下是 TOCTOU——并发双击靠
     *  uk(tenant,task,submitter,version) 唯一索引兜底(DuplicateKey=有并发, 让用户刷新重试) */
    @Transactional(rollbackFor = Exception.class)
    public Submission submit(Long taskId, Map<String, Object> data, Long me) {
        Task task = taskService.requireVisible(taskId, me);
        if (!Task.STATUS_COLLECTING.equals(task.getStatus())) {
            throw new ServiceException("任务不在收集状态, 不可提交");
        }
        if (!task.assignedTo(me)) {
            throw new ServiceException("你不是该任务的目标成员");
        }
        Submission latest = latestOf(taskId, me);
        if (latest != null) {
            if (Submission.STATUS_PENDING_AUDIT.equals(latest.getStatus())) {
                throw new ServiceException("已有待审核的提交, 请等待审核");
            }
            if (Submission.STATUS_APPROVED.equals(latest.getStatus())) {
                throw new ServiceException("提交已通过, 无需重复提交");
            }
        }
        validator.validate(taskService.fieldsOf(taskId), data == null ? Map.of() : data);

        Date now = new Date();
        Submission s = new Submission();
        s.setId(IdWorker.getId());
        s.setTenantId(task.getTenantId());
        s.setTaskId(taskId);
        s.setSubmitterUserId(me);
        s.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        s.setStatus(Submission.STATUS_PENDING_AUDIT);
        s.setData(Jsons.toJson(data == null ? Map.of() : data));
        s.setSubmittedAt(now);
        try {
            submissionMapper.insertJsonb(s);
        } catch (org.springframework.dao.DuplicateKeyException concurrent) {
            throw new ServiceException("检测到并发提交, 请刷新后重试");
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("taskId", taskId);
        props.put("submissionId", s.getId());
        props.put("version", s.getVersion());
        java.math.BigDecimal hours = task.hoursSincePublish(now);
        if (hours != null) {
            props.put("responseHours", hours);
        }
        publishEvent(SelfMonitorEvents.DATA_SUBMITTED,
            ProfileUids.uid("submission", "submit", s.getId(), "v" + s.getVersion()), taskId, me, props);

        // 通知审核人(按模式)
        if (Task.AUDIT_ASSIGNED.equals(task.getAuditMode())) {
            for (Long auditor : task.auditorList()) {
                noticeService.notify(auditor, Notice.TYPE_SUBMITTED,
                    "新提交: " + task.getTitle(), "有新提交待审核(v" + s.getVersion() + ")", taskId);
            }
        } else {
            noticeService.notify(task.getPublisherUserId(), Notice.TYPE_SUBMITTED,
                "新提交: " + task.getTitle(), "有新提交待审核(v" + s.getVersion() + ")", taskId);
        }
        return s;
    }

    /** 我的提交历史(可按任务过滤) */
    public TableDataInfo<Submission> mySubmissions(Long taskId, PageQuery pq, Long userId) {
        Page<Submission> page = submissionMapper.selectPage(pq.build(), new LambdaQueryWrapper<Submission>()
            .eq(Submission::getSubmitterUserId, userId)
            .eq(taskId != null, Submission::getTaskId, taskId)
            .orderByDesc(Submission::getId));
        return TableDataInfo.build(page);
    }

    /** (taskId, userId) 的最新版本提交 */
    public Submission latestOf(Long taskId, Long userId) {
        return submissionMapper.selectList(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getTaskId, taskId)
                .eq(Submission::getSubmitterUserId, userId)
                .orderByDesc(Submission::getVersion)
                .orderByDesc(Submission::getId)
                .last("LIMIT 1"))
            .stream().findFirst().orElse(null);
    }

    private void publishEvent(String eventType, String eventUid, Long taskId, Long subjectUserId,
                              Map<String, Object> props) {
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
            log.warn("[Dcs] profile event failed type={} uid={}: {}", eventType, eventUid, ex.getMessage());
        }
    }
}
