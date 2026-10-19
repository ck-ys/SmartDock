package com.smartdock.dcs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.dcs.domain.DcsSubmission;
import com.smartdock.dcs.domain.DcsTask;
import com.smartdock.dcs.mapper.DcsSubmissionMapper;
import com.smartdock.dcs.mapper.DcsTaskMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 双视角概览统计(验收红线"镜像统计非零"的数据源)。
 *
 * <p>发布人视角: 我的任务按状态计数 + 待审队列长度;
 * 成员视角: 待办数 + 我的提交按状态计数 + 未读通知。</p>
 *
 * @author smartdock
 */
@Service
@RequiredArgsConstructor
public class DcsStatsService {

    private final DcsTaskMapper taskMapper;
    private final DcsSubmissionMapper submissionMapper;
    private final DcsNoticeService noticeService;

    public Map<String, Object> overview(Long me) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 发布人视角
        List<DcsTask> myPublished = taskMapper.selectList(new LambdaQueryWrapper<DcsTask>()
            .eq(DcsTask::getPublisherUserId, me));
        Map<String, Long> byStatus = myPublished.stream()
            .collect(Collectors.groupingBy(DcsTask::getStatus, Collectors.counting()));
        Map<String, Object> publisher = new LinkedHashMap<>();
        publisher.put("draft", byStatus.getOrDefault(DcsTask.STATUS_DRAFT, 0L));
        publisher.put("collecting", byStatus.getOrDefault(DcsTask.STATUS_COLLECTING, 0L));
        publisher.put("closed", byStatus.getOrDefault(DcsTask.STATUS_CLOSED, 0L));
        out.put("publisher", publisher);

        // 成员视角
        List<DcsTask> collected = taskMapper.selectList(new LambdaQueryWrapper<DcsTask>()
            .eq(DcsTask::getStatus, DcsTask.STATUS_COLLECTING)
            .apply("assignee_user_ids @> ({0}::jsonb)", com.smartdock.dcs.support.DcsJsons.toJson(List.of(me))));
        Set<Long> settledTaskIds = submissionMapper.selectList(new LambdaQueryWrapper<DcsSubmission>()
                .eq(DcsSubmission::getSubmitterUserId, me)
                .in(DcsSubmission::getStatus, List.of(DcsSubmission.STATUS_PENDING_AUDIT, DcsSubmission.STATUS_APPROVED)))
            .stream().map(DcsSubmission::getTaskId).collect(Collectors.toSet());
        long todo = collected.stream().filter(t -> !settledTaskIds.contains(t.getId())).count();

        Map<String, Long> subByStatus = submissionMapper.selectList(new LambdaQueryWrapper<DcsSubmission>()
                .eq(DcsSubmission::getSubmitterUserId, me))
            .stream().collect(Collectors.groupingBy(DcsSubmission::getStatus, Collectors.counting()));
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("todo", todo);
        member.put("submitted", collected.size());
        member.put("pendingAudit", subByStatus.getOrDefault(DcsSubmission.STATUS_PENDING_AUDIT, 0L));
        member.put("approved", subByStatus.getOrDefault(DcsSubmission.STATUS_APPROVED, 0L));
        member.put("rejected", subByStatus.getOrDefault(DcsSubmission.STATUS_REJECTED, 0L));
        member.put("unreadNotices", noticeService.unreadCount(me));
        out.put("member", member);
        return out;
    }
}
