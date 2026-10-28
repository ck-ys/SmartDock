package com.smartdock.profile.engine;

import com.smartdock.analytics.mapper.AnalyticsEventMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 规模护栏(2026-10-25 批4A): 三台"全量载入进堆"发动机的前置信号——
 * 超阈值在载入前明确报错(文案含实际数量), 而不是把堆吃满后静默 OOM。
 *
 * <p>默认阈值对齐审计安全区(分析窗口事件 ≤10^5): 窗口事件 10 万 /
 * 分群候选 5 万 / 模型主体 2 万, 均可经配置覆盖。定时路径(分群保鲜)不抛错
 * 改跳过: 由 ProfileSegmentService 写 SKIPPED run 留痕且不更新保鲜时间,
 * 每小时重试保持信号可见——静默停转变为有信号。</p>
 *
 * <p>不拦的两处(设计定案): 会话重建入口是事件驱动的增量读取(窗口左移 30 分钟,
 * 量非用户可选); rebuild 是 GROUP BY 日聚合 SQL 下推——都不属全量载入模式。</p>
 *
 * <p>模型侧文案不得指引走 recalc——那是经典评分链, 与模型快照链互不相干,
 * 指了就是断头路。</p>
 *
 * @author smartdock
 */
@Component
@RequiredArgsConstructor
public class ProfileScaleGuard {

    /** 分析窗口将载入的事件行数上限(默认 10 万, 审计安全区边界值) */
    public static final long DEFAULT_MAX_WINDOW_EVENTS = 100_000L;
    /** 分群候选主体数上限(默认 5 万, LATERAL 全扫为 IO 型、候选行小) */
    public static final long DEFAULT_MAX_SEGMENT_CANDIDATES = 50_000L;
    /** 模型全量运行/对比的主体数上限(默认 2 万, 审计安全区 1~5 万主体的下沿) */
    public static final long DEFAULT_MAX_MODEL_SUBJECTS = 20_000L;

    @Value("${smartdock.analytics.max-window-events:100000}")
    private long maxWindowEvents = DEFAULT_MAX_WINDOW_EVENTS;

    @Value("${smartdock.profile.max-segment-candidates:50000}")
    private long maxSegmentCandidates = DEFAULT_MAX_SEGMENT_CANDIDATES;

    @Value("${smartdock.profile.max-model-subjects:20000}")
    private long maxModelSubjects = DEFAULT_MAX_MODEL_SUBJECTS;

    private final AnalyticsEventMapper eventMapper;
    private final ProfileSubjectMapper subjectMapper;

    /**
     * 分析窗口护栏: 漏斗/路径/流失下钻在载入前先轻量 COUNT。
     * 计数与实际载入同过滤口径(租户+源+时间窗+事件名), 事件名列表与
     * selectEventsByNames 一致; 流失下钻虽会连带载入首步访客的其余事件,
     * 但漏斗预览已被同一计数拦住, 两口径保持一致才不会"预览过、下钻炸"。
     */
    public void checkAnalysisWindow(String tenantId, String source, Date from, Date to, List<String> names) {
        long count = eventMapper.countEventsByNames(tenantId, source, from, to, names);
        requireWithin(count, maxWindowEvents, windowMessage(count, maxWindowEvents));
    }

    /** 分群候选护栏(人工路径: 评估/预览/名单导入, 超限明确报错)。 */
    public void checkSegmentCandidates(String tenantId, String source) {
        long count = subjectMapper.countBySource(tenantId, source);
        requireWithin(count, maxSegmentCandidates, segmentMessage(count, maxSegmentCandidates));
    }

    /** 模型全量运行/试算/总览护栏(载入前主体轻量 COUNT)——按模型自己的主体类型计数。 */
    public void checkModelSubjects(String tenantId, String source, String subjectType) {
        long count = subjectMapper.countBySourceType(tenantId, source, subjectType);
        checkModelSubjectsCount(count);
    }

    /** 模型版本对比护栏: 将载入的行数=历史 run 落库总量, 免二次扫表。 */
    public void checkModelSubjectsCount(long knownCount) {
        requireWithin(knownCount, maxModelSubjects, modelMessage(knownCount, maxModelSubjects));
    }

    public long countSegmentCandidates(String tenantId, String source) {
        return subjectMapper.countBySource(tenantId, source);
    }

    /** 定时路径判定(不抛错, 由调用方写 SKIPPED 留痕)。 */
    public boolean exceedsSegmentCandidates(long count) {
        return exceeds(count, maxSegmentCandidates);
    }

    public long maxSegmentCandidates() {
        return maxSegmentCandidates;
    }

    // ---------------------------------------------------------------- 纯函数(单测锚点)

    static void requireWithin(long count, long threshold, String message) {
        if (exceeds(count, threshold)) {
            throw new ServiceException(message);
        }
    }

    static boolean exceeds(long count, long threshold) {
        return count > threshold;
    }

    static String windowMessage(long count, long threshold) {
        return "分析窗口事件量 " + count + " 已超护栏阈值 " + threshold
            + "，为保护服务暂不执行本次分析，请缩小时间范围，或联系管理员调整 smartdock.analytics.max-window-events";
    }

    static String segmentMessage(long count, long threshold) {
        return "分群候选主体 " + count + " 已超护栏阈值 " + threshold
            + "，分群评估暂不可用，请联系管理员调整阈值或收窄主体范围";
    }

    static String modelMessage(long count, long threshold) {
        // 设计定案: 不指路 recalc(经典链), 模型侧超限只有调阈值或等分批能力
        return "主体量 " + count + " 超护栏阈值 " + threshold
            + "，模型运行/对比暂不可用，请联系管理员调整阈值或等待模型侧分批能力";
    }
}
