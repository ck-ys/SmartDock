package org.dromara.web.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.dcs.domain.DcsTask;
import com.smartdock.dcs.mapper.DcsTaskMapper;
import lombok.RequiredArgsConstructor;
import com.smartdock.service.support.MissionReviewProbe;
import org.springframework.stereotype.Component;

/**
 * 任务审核人探针适配器（批3 微核心化，2026-10-18）。
 *
 * <p>旧 dcs 的 MissionReviewProbeImpl（查 dcs_review_task 表）随模块退役。
 * 微核心口径下「在审审核人」= 任务处于收集态且我是其指定审核人
 * （dcs_task.status=COLLECTING 且 auditor_user_ids 含我）。file-security
 * 不依赖 ruoyi-dcs（依赖单向），适配器落 admin 聚合层桥接两模块；
 * 批4 端口补齐时再评估落位。</p>
 */
@Component
@RequiredArgsConstructor
public class AdminMissionReviewProbe implements MissionReviewProbe {

    private final DcsTaskMapper taskMapper;

    @Override
    public boolean hasPendingReviewTask(Long missionId, Long userId) {
        if (missionId == null || userId == null) {
            return false;
        }
        DcsTask task = taskMapper.selectOne(new LambdaQueryWrapper<DcsTask>()
            .eq(DcsTask::getId, missionId)
            .eq(DcsTask::getStatus, DcsTask.STATUS_COLLECTING)
            .last("limit 1"));
        return task != null && task.auditorIs(userId);
    }
}
