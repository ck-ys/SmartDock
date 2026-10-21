package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.profile.event.ProfileSubject;
import com.smartdock.profile.mapper.ProfileRecalcBatchMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 全量重算服务: 按 subject_id 升序游标分页, 断点续跑, 单主体失败不中断。
 *
 * <p>批1 为同步执行(快照库主体量 ~10^2 级可接受); 万级主体时二期改异步线程池
 * + TenantHelper.dynamic 显式租户。进度可经 /profile/recalc/{batchId} 轮询。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileRecalcService {

    private static final int PAGE_SIZE = 500;

    private final ProfileSubjectMapper subjectMapper;
    private final ProfileScoreEngine scoreEngine;
    private final ProfileRecalcBatchMapper batchMapper;

    /**
     * 发起一次全量重算(当前租户 + 指定 source), 同步执行完毕返回批次。
     */
    public ProfileRecalcBatch startAndRun(String source) {
        // dspro P1-5 回修: 同租户同 source 已有 RUNNING 批次时拒绝并发重跑——
        // 前链读-写非原子, 双跑会让两条快照指向同一前驱(链变树)。
        // 进程崩溃遗留的 RUNNING 批: 手工置 FAILED 后可 resume 续跑。
        Long running = batchMapper.selectCount(new LambdaQueryWrapper<ProfileRecalcBatch>()
            .eq(ProfileRecalcBatch::getSource, source)
            .eq(ProfileRecalcBatch::getStatus, "RUNNING"));
        if (running != null && running > 0) {
            throw new ServiceException("source=" + source + " 已有重算批次在执行, 拒绝并发重跑");
        }
        Long total = subjectMapper.selectCount(new LambdaQueryWrapper<ProfileSubject>()
            .eq(ProfileSubject::getSource, source));

        ProfileRecalcBatch batch = new ProfileRecalcBatch();
        batch.setTenantId(currentTenant());
        batch.setSource(source);
        batch.setStatus("RUNNING");
        batch.setTotalSubjects(total == null ? 0 : total.intValue());
        batch.setDoneSubjects(0);
        batch.setLastSubjectId(0L);
        try {
            batchMapper.insert(batch);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // LongCat P2-1: uk_profile_recalc_running 部分唯一索引在 DB 层封死
            // check-then-act 竞态——并发第二插入被拒, 转可读错误
            throw new ServiceException("source=" + source + " 已有重算批次在执行, 拒绝并发重跑");
        }

        runFrom(batch, 0L);
        return batch;
    }

    /**
     * 从断点续跑(批次中断后手工/定时拉起)。
     */
    public ProfileRecalcBatch resume(Long batchId) {
        ProfileRecalcBatch batch = batchMapper.selectById(batchId);
        if (batch == null || "DONE".equals(batch.getStatus())) {
            return batch;
        }
        if ("RUNNING".equals(batch.getStatus())) {
            // dspro P1-5 回修: RUNNING 中再 resume = 双跑, 拒绝
            throw new ServiceException("批次 " + batchId + " 正在执行中, 拒绝并发续跑");
        }
        runFrom(batch, batch.getLastSubjectId() == null ? 0L : batch.getLastSubjectId());
        return batch;
    }

    private String currentTenant() {
        // M3 查漏 P1: 批次显式落租户——拦截器补列依赖请求线程上下文, 显式值与
        // updateById 的 TenantLine 过滤同源, 多租户下进度更新/并发闸才不失效
        try {
            String t = org.dromara.common.tenant.helper.TenantHelper.getTenantId();
            if (t != null && !t.isBlank()) {
                return t;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        return com.smartdock.profile.engine.ProfileTenantContext.require();
    }

    private void runFrom(ProfileRecalcBatch batch, long fromId) {
        int done = 0;
        int failures = 0;
        long cursor = fromId;
        String lastError = null;
        try {
            while (true) {
                List<ProfileSubject> page = subjectMapper.selectList(new LambdaQueryWrapper<ProfileSubject>()
                    .eq(ProfileSubject::getSource, batch.getSource())
                    .gt(ProfileSubject::getId, cursor)
                    .orderByAsc(ProfileSubject::getId)
                    .last("LIMIT " + PAGE_SIZE));
                if (page.isEmpty()) {
                    break;
                }
                for (ProfileSubject s : page) {
                    try {
                        scoreEngine.recalcSubject(s.getSource(), s.getSubjectType(), s.getSubjectId());
                    } catch (Exception e) {
                        failures++;
                        lastError = "subject " + s.getSubjectId() + ": " + e.getMessage();
                        log.warn("[Profile] recalc subject failed subject={} : {}", s.getSubjectId(), e.getMessage());
                    }
                    done++;
                    cursor = s.getId();
                }
                batch.setDoneSubjects((batch.getDoneSubjects() == null ? 0 : batch.getDoneSubjects()) + done);
                batch.setLastSubjectId(cursor);
                batchMapper.updateById(batch);
                done = 0;
            }
            batch.setStatus("DONE");
            if (failures > 0) {
                batch.setErrorMsg(failures + " subjects failed; last: " + lastError);
            }
        } catch (Exception e) {
            batch.setStatus("FAILED");
            batch.setErrorMsg(String.valueOf(e.getMessage()));
            log.error("[Profile] recalc batch {} aborted", batch.getBatchId(), e);
        }
        batchMapper.updateById(batch);
    }
}
