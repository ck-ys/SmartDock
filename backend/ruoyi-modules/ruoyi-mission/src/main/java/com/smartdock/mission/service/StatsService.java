package com.smartdock.mission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.mission.domain.Submission;
import com.smartdock.mission.domain.Task;
import com.smartdock.mission.mapper.SubmissionMapper;
import com.smartdock.mission.mapper.TaskMapper;
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
public class StatsService {

    private final TaskMapper taskMapper;
    private final SubmissionMapper submissionMapper;
    private final NoticeService noticeService;

    public Map<String, Object> overview(Long me) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 发布人视角
        List<Task> myPublished = taskMapper.selectList(new LambdaQueryWrapper<Task>()
            .eq(Task::getPublisherUserId, me));
        Map<String, Long> byStatus = myPublished.stream()
            .collect(Collectors.groupingBy(Task::getStatus, Collectors.counting()));
        Map<String, Object> publisher = new LinkedHashMap<>();
        publisher.put("draft", byStatus.getOrDefault(Task.STATUS_DRAFT, 0L));
        publisher.put("collecting", byStatus.getOrDefault(Task.STATUS_COLLECTING, 0L));
        publisher.put("closed", byStatus.getOrDefault(Task.STATUS_CLOSED, 0L));
        out.put("publisher", publisher);

        // 成员视角
        List<Task> collected = taskMapper.selectList(new LambdaQueryWrapper<Task>()
            .eq(Task::getStatus, Task.STATUS_COLLECTING)
            .apply("assignee_user_ids @> ({0}::jsonb)", com.smartdock.mission.support.Jsons.toJson(List.of(me))));
        Set<Long> settledTaskIds = submissionMapper.selectList(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getSubmitterUserId, me)
                .in(Submission::getStatus, List.of(Submission.STATUS_PENDING_AUDIT, Submission.STATUS_APPROVED)))
            .stream().map(Submission::getTaskId).collect(Collectors.toSet());
        long todo = collected.stream().filter(t -> !settledTaskIds.contains(t.getId())).count();

        Map<String, Long> subByStatus = submissionMapper.selectList(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getSubmitterUserId, me))
            .stream().collect(Collectors.groupingBy(Submission::getStatus, Collectors.counting()));
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("todo", todo);
        member.put("submitted", collected.size());
        member.put("pendingAudit", subByStatus.getOrDefault(Submission.STATUS_PENDING_AUDIT, 0L));
        member.put("approved", subByStatus.getOrDefault(Submission.STATUS_APPROVED, 0L));
        member.put("rejected", subByStatus.getOrDefault(Submission.STATUS_REJECTED, 0L));
        member.put("unreadNotices", noticeService.unreadCount(me));
        out.put("member", member);
        return out;
    }
}
