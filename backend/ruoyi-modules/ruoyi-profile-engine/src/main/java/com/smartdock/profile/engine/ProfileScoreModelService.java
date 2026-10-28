package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.profile.domain.ProfileModelRun;
import com.smartdock.profile.domain.ProfileModelSnapshot;
import com.smartdock.profile.domain.ProfileScoreModel;
import com.smartdock.profile.event.ProfileSubject;
import com.smartdock.profile.mapper.ProfileModelMetricMapper;
import com.smartdock.profile.mapper.ProfileModelRunMapper;
import com.smartdock.profile.mapper.ProfileModelSnapshotMapper;
import com.smartdock.profile.mapper.ProfileScoreModelMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import com.smartdock.profile.model.ProfileBinResolver;
import com.smartdock.profile.model.ProfileModelConfig;
import com.smartdock.profile.model.ProfileModelDefinition;
import com.smartdock.profile.domain.ProfileModelConfigSnapshot;
import com.smartdock.profile.mapper.ProfileModelConfigSnapshotMapper;
import com.smartdock.profile.model.ProfileRfmLevel;
import com.smartdock.profile.query.ProfileModelPublishBo;
import com.smartdock.profile.query.ProfileModelSubjectRow;
import com.smartdock.profile.query.ProfileScoreModelBo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 通用评分模型: N 维指标 → 分箱 → 加权 → 快照。
 *
 * <p>分箱支持 fixed/mean/percentile/topN; RFM 是内置 preset。
 * 全量运行同步执行(主体量 ~10^3 可接受), 事件后的单主体增量走缓存人口统计。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileScoreModelService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_SCORE_MAX = BigDecimal.valueOf(1000);
    private static final long POPULATION_TTL_MS = 10_000L;

    private final ProfileScoreModelMapper modelMapper;
    private final ProfileModelRunMapper runMapper;
    private final ProfileModelSnapshotMapper snapshotMapper;
    private final ProfileModelMetricMapper metricMapper;
    private final ProfileSubjectMapper subjectMapper;
    private final ProfileModelConfigSnapshotMapper configSnapshotMapper;
    /** 批10 Q2: 快照链 advisory 事务锁用(取前链+写快照包进一个事务) */
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;
    /** 批10 R1: 模型运行互斥(publish/rollback/run/回放共用), 封死 RUNNING 可见性窗口 */
    private final org.redisson.api.RedissonClient redisson;
    /** 批4A 规模护栏: 全量载入主体前的轻量 COUNT(默认 2 万, 审计安全区下沿) */
    private final ProfileScaleGuard scaleGuard;

    private final Map<String, CachedModelData> populationCache = new ConcurrentHashMap<>();

    public List<ProfileScoreModel> list(String source, String subjectType) {
        requireSource(source);
        return modelMapper.selectList(new LambdaQueryWrapper<ProfileScoreModel>()
            .eq(ProfileScoreModel::getSource, source)
            .eq(ProfileScoreModel::getSubjectType, SubjectTypes.normalize(subjectType))
            .orderByDesc(ProfileScoreModel::getBuiltin)
            .orderByAsc(ProfileScoreModel::getCreateTime));
    }

    public ProfileScoreModel get(Long modelId) {
        ProfileScoreModel model = modelMapper.selectById(modelId);
        if (model == null) {
            throw new ServiceException("评分模型不存在");
        }
        return model;
    }

    /**
     * RFM 通用预设(不落库, 供新建模型时填充)。
     */
    public Map<String, Object> rfmPreset() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("preset", "RFM");
        config.put("dimensions", List.of(
            dimension("R", "最近活跃", "RECENCY_DAYS", List.of(), null, "lower"),
            dimension("F", "互动频次", "COUNT", List.of(), null, "higher"),
            dimension("M", "价值贡献", "SUM", List.of("order.paid"), "amount", "higher")
        ));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", "rfm");
        out.put("name", "RFM 分层");
        out.put("description", "R=最近活跃, F=互动频次, M=价值贡献; 默认按分位五档, 输出八类客户分层");
        out.put("config", config);
        return out;
    }

    private Map<String, Object> dimension(String code, String name, String type,
                                          List<String> events, String prop, String direction) {
        Map<String, Object> calc = new LinkedHashMap<>();
        calc.put("type", type);
        calc.put("eventTypes", events);
        if (prop != null) {
            calc.put("prop", prop);
        }
        Map<String, Object> bins = new LinkedHashMap<>();
        bins.put("method", "percentile");
        bins.put("direction", direction);
        bins.put("quantiles", List.of(0.2, 0.4, 0.6, 0.8));
        bins.put("scores", List.of(1, 2, 3, 4, 5));
        bins.put("missingScore", 1);
        Map<String, Object> dim = new LinkedHashMap<>();
        dim.put("code", code);
        dim.put("name", name);
        dim.put("weight", 1);
        dim.put("calc", calc);
        dim.put("bins", bins);
        return dim;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileScoreModel create(ProfileScoreModelBo bo) {
        requireSource(bo.getSource());
        validateBo(bo, true);
        String subjectType = SubjectTypes.normalize(bo.getSubjectType());
        Long duplicated = modelMapper.selectCount(new LambdaQueryWrapper<ProfileScoreModel>()
            .eq(ProfileScoreModel::getSource, bo.getSource())
            .eq(ProfileScoreModel::getSubjectType, subjectType)
            .eq(ProfileScoreModel::getModelCode, bo.getModelCode().trim()));
        if (duplicated != null && duplicated > 0) {
            throw new ServiceException("同一业务源+主体类型下模型编码已存在: " + bo.getModelCode());
        }
        ProfileScoreModel model = new ProfileScoreModel();
        model.setModelId(IdWorker.getId());
        model.setTenantId(currentTenant());
        model.setSource(bo.getSource());
        model.setSubjectType(subjectType);
        model.setModelCode(bo.getModelCode().trim());
        model.setModelName(bo.getModelName().trim());
        model.setModelType(resolveType(bo));
        model.setModelVersion(StringUtils.isBlank(bo.getModelVersion()) ? "v1" : bo.getModelVersion().trim());
        model.setConfig(JsonUtils.toJsonString(bo.getConfig()));
        model.setScoreMax(bo.getScoreMax());
        model.setEnabled("0".equals(bo.getEnabled()) ? "0" : "1");
        model.setBuiltin("0");
        model.setRemark(StringUtils.substring(bo.getRemark(), 0, 500));
        model.setRevision(0);
        model.setStatus("DRAFT");
        model.setCreateBy(currentUser());
        modelMapper.insertJsonb(model);
        invalidate();
        return model;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileScoreModel update(ProfileScoreModelBo bo) {
        if (bo.getModelId() == null) {
            throw new ServiceException("modelId 不能为空");
        }
        ProfileScoreModel existing = get(bo.getModelId());
        bo.setSource(existing.getSource());
        bo.setSubjectType(existing.getSubjectType());
        bo.setModelCode(existing.getModelCode());
        validateBo(bo, false);
        if (bo.getRevision() == null || !bo.getRevision().equals(existing.getRevision())) {
            throw new ServiceException("模型已被其他人修改, 请刷新后重试");
        }
        existing.setModelName(bo.getModelName().trim());
        existing.setModelType(resolveType(bo));
        if (StringUtils.isNotBlank(bo.getModelVersion())) {
            existing.setModelVersion(bo.getModelVersion().trim());
        }
        existing.setConfig(JsonUtils.toJsonString(bo.getConfig()));
        existing.setScoreMax(bo.getScoreMax());
        existing.setEnabled("0".equals(bo.getEnabled()) ? "0" : "1");
        existing.setRemark(StringUtils.substring(bo.getRemark(), 0, 500));
        if (!"DRAFT".equals(existing.getStatus())) {
            existing.setStatus("DRAFT");
        }
        existing.setRevision((existing.getRevision() == null ? 0 : existing.getRevision()) + 1);
        existing.setUpdateBy(currentUser());
        if (modelMapper.updateJsonb(existing) == 0) {
            throw new ServiceException("模型已被其他人修改, 请刷新后重试");
        }
        invalidate();
        return existing;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long modelId) {
        ProfileScoreModel model = get(modelId);
        if ("1".equals(model.getBuiltin())) {
            throw new ServiceException("内置模型不可删除, 可在编辑中停用");
        }
        invalidate();
        return modelMapper.deleteById(modelId) > 0;
    }

    /**
     * 全量运行模型: 一次人口统计 + 全部主体打分, 写 run 与快照。
     */
    public ProfileModelRun run(Long modelId, String triggerType) {
        ProfileScoreModel model = get(modelId);
        if (!"PUBLISHED".equals(model.getStatus())) {
            throw new ServiceException("只有已发布模型可以全量重算, 草稿请先试算并发布");
        }
        // 批10 R1: 分布式锁封死并发窗口——RUNNING 行要等外层事务提交才对其他会话可见,
        // 纯靠它判重时 publish 大事务里的标记在提交前拦不住并发的直接 /run(批9 多审遗留①②)
        org.redisson.api.RLock lock = redisson.getLock(ProfileLocks.modelRun(modelId));
        boolean acquired;
        try {
            acquired = lock.tryLock(0, ProfileLocks.MODEL_RUN_LEASE_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("模型重算被中断, 请重试");
        }
        if (!acquired) {
            throw new ServiceException("该模型已有重算在进行中, 请稍后再试");
        }
        try {
            return doRun(model, triggerType);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private ProfileModelRun doRun(ProfileScoreModel model, String triggerType) {
        // 先回收僵尸 RUNNING(进程重启遗留), 阈值须大于最长合法重算时长
        runMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ProfileModelRun>()
            .eq(ProfileModelRun::getModelId, model.getModelId())
            .eq(ProfileModelRun::getStatus, "RUNNING")
            .lt(ProfileModelRun::getStartedAt, new Date(System.currentTimeMillis() - 30 * 60_000L))
            .set(ProfileModelRun::getStatus, "FAILED")
            .set(ProfileModelRun::getErrorMsg, "stale RUNNING reclaimed (进程重启遗留)"));
        Long running = runMapper.selectCount(new LambdaQueryWrapper<ProfileModelRun>()
            .eq(ProfileModelRun::getModelId, model.getModelId())
            .eq(ProfileModelRun::getStatus, "RUNNING"));
        if (running != null && running > 0) {
            throw new ServiceException("该模型已有重算在进行中, 请稍后再试");
        }
        ProfileModelDefinition def = parseDefinition(model.getConfig());
        List<String> errors = ProfileModelConfig.validate(def);
        if (!errors.isEmpty()) {
            throw new ServiceException("模型配置不合法: " + String.join("; ", errors));
        }
        // 批4A 护栏: 全量运行前主体轻量 COUNT, 超阈值在载入前报错(不写 SKIPPED——人工入口)
        // 题0-乙: 模型只统计/打分自己那类主体(员工模型不算会员, 反之亦然)
        String modelSubjectType = SubjectTypes.normalize(model.getSubjectType());
        // 批末回修: 护栏同口径按类型计数(全类型合计会误拦单类型运行)
        scaleGuard.checkModelSubjects(model.getTenantId(), model.getSource(), modelSubjectType);
        List<ProfileSubject> subjects = subjectMapper.selectList(new LambdaQueryWrapper<ProfileSubject>()
            .eq(ProfileSubject::getTenantId, model.getTenantId())
            .eq(ProfileSubject::getSource, model.getSource())
            .eq(ProfileSubject::getSubjectType, modelSubjectType)
            .orderByAsc(ProfileSubject::getSubjectType)
            .orderByAsc(ProfileSubject::getSubjectId));

        ProfileModelRun run = new ProfileModelRun();
        run.setRunId(IdWorker.getId());
        run.setTenantId(model.getTenantId());
        run.setSource(model.getSource());
        run.setModelId(model.getModelId());
        run.setModelCode(model.getModelCode());
        run.setModelVersion(model.getModelVersion());
        run.setStatus("RUNNING");
        run.setTriggerType(StringUtils.isBlank(triggerType) ? "MANUAL" : triggerType);
        run.setTotalSubjects(subjects.size());
        run.setDoneSubjects(0);
        run.setStartedAt(new Date());
        run.setCreateBy(currentUser());
        runMapper.insert(run);
        try {
            CachedModelData data = loadModelData(model, def, true);
            writeSnapshots(model, def, subjects, data, run, null);
            run.setStatus("DONE");
            run.setDoneSubjects(subjects.size());
        } catch (Exception e) {
            log.error("[Profile] model run failed model={} : {}", model.getModelCode(), e.getMessage(), e);
            run.setStatus("FAILED");
            run.setErrorMsg(StringUtils.substring(String.valueOf(e.getMessage()), 0, 2000));
        }
        run.setFinishedAt(new Date());
        runMapper.updateById(run);
        return run;
    }

    /**
     * 事件后的单主体增量: 只刷新启用的模型; 失败不阻断主链路。
     */
    public void evaluateSubjectIfActive(String tenantId, String source, String subjectType, String subjectId) {
        // 事件已落库: 人口统计与主体指标都可能变化, 增量不能读旧缓存
        invalidate();
        String type = SubjectTypes.normalize(subjectType);
        List<ProfileScoreModel> models = modelMapper.selectList(new LambdaQueryWrapper<ProfileScoreModel>()
            .eq(ProfileScoreModel::getTenantId, tenantId)
            .eq(ProfileScoreModel::getSource, source)
            .eq(ProfileScoreModel::getSubjectType, type)
            .eq(ProfileScoreModel::getEnabled, "1")
            .eq(ProfileScoreModel::getStatus, "PUBLISHED"));
        for (ProfileScoreModel model : models) {
            try {
                ProfileModelDefinition def = parseDefinition(model.getConfig());
                if (!ProfileModelConfig.validate(def).isEmpty()) {
                    continue;
                }
                CachedModelData data = cachedModelData(model, def);
                String key = subjectKey(subjectType, subjectId);
                boolean missing = data.values.values().stream().noneMatch(m -> m.containsKey(key));
                if (missing) {
                    // 新主体: 强制刷新一次人口与指标, 避免首条事件拿不到自己的值
                    data = loadModelData(model, def, true);
                }
                ProfileSubject subject = new ProfileSubject();
                subject.setSubjectType(subjectType);
                subject.setSubjectId(subjectId);
                Map<String, BigDecimal> values = new HashMap<>();
                for (ProfileModelDefinition.ModelDimension dim : def.getDimensions()) {
                    values.put(dim.getCode(),
                        data.values.getOrDefault(dim.getCode(), Map.of()).get(key));
                }
                ProfileModelSnapshot snapshot = scoreOne(model, def, subject, values, data, null);
                // 批10 Q2: 增量写快照同样持 advisory 事务锁——与全量 run 在 DB 层排队,
                // 前"取历史+定前链+写入"原子, 消除增量与全量并发时的链分叉
                txTemplate.executeWithoutResult(status -> {
                    snapshotMapper.advisoryXactLock(snapshotChainKey(model));
                    List<Map<String, Object>> history = snapshotMapper.selectHistory(model.getTenantId(),
                        model.getSource(), model.getModelCode(), subjectType, subjectId, 1);
                    if (!history.isEmpty()) {
                        Object prev = history.get(0).get("modelSnapshotId");
                        if (prev != null) {
                            snapshot.setPrevSnapshotId(Long.parseLong(String.valueOf(prev)));
                        }
                    }
                    snapshotMapper.insertJsonb(snapshot);
                });
            } catch (Exception e) {
                log.warn("[Profile] model incremental failed model={} subject={} err={}",
                    model.getModelCode(), subjectId, e.getMessage());
            }
        }
    }

    public Map<String, Object> overview(Long modelId) {
        ProfileScoreModel model = get(modelId);
        // 批4A 护栏: overview 是第 4 个全量读面(selectLatestAll 全量载入后 Java 聚合,
        // rfm 页首屏即调), 主体超阈值先报错——subjectPage 走分页不拦
        scaleGuard.checkModelSubjects(model.getTenantId(), model.getSource(),
            SubjectTypes.normalize(model.getSubjectType()));
        List<ProfileModelSubjectRow> rows = snapshotMapper.selectLatestAll(
            model.getTenantId(), model.getSource(), SubjectTypes.normalize(model.getSubjectType()),
            model.getModelCode(), model.getModelVersion());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelId", model.getModelId());
        out.put("modelCode", model.getModelCode());
        out.put("modelName", model.getModelName());
        out.put("modelType", model.getModelType());
        out.put("modelVersion", model.getModelVersion());
        out.put("enabled", model.getEnabled());
        out.put("scoredCount", rows.size());
        out.put("avgModelScore", average(rows, ProfileModelSubjectRow::getModelScore));
        out.put("avgNormalizedScore", average(rows, ProfileModelSubjectRow::getNormalizedScore));
        Date lastCalcTime = rows.stream().map(ProfileModelSubjectRow::getCalcTime)
            .filter(java.util.Objects::nonNull).max(Date::compareTo).orElse(null);
        out.put("lastCalcTime", lastCalcTime);

        Map<String, Long> levelCounts = new LinkedHashMap<>();
        for (ProfileModelSubjectRow row : rows) {
            levelCounts.merge(StringUtils.isBlank(row.getLevel()) ? "未分层" : row.getLevel(), 1L, Long::sum);
        }
        List<Map<String, Object>> levels = new ArrayList<>();
        levelCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("level", e.getKey());
                item.put("count", e.getValue());
                item.put("rate", rows.isEmpty() ? BigDecimal.ZERO
                    : BigDecimal.valueOf(e.getValue() * 100.0 / rows.size()).setScale(2, RoundingMode.HALF_UP));
                levels.add(item);
            });
        out.put("levels", levels);

        out.put("dimensions", dimensionStats(rows));
        out.put("topSubjects", rows.subList(0, Math.min(5, rows.size())));
        return out;
    }

    public IPage<ProfileModelSubjectRow> subjectPage(Long modelId, int pageNum, int pageSize,
                                                      String level, String keyword) {
        ProfileScoreModel model = get(modelId);
        Page<ProfileModelSubjectRow> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 200));
        page.setOptimizeCountSql(false);
        return snapshotMapper.selectLatestPage(page, model.getTenantId(), model.getSource(),
            model.getSubjectType(), model.getModelCode(), model.getModelVersion(), level, keyword);
    }

    public Map<String, Object> subjectDetail(Long modelId, String subjectType, String subjectId) {
        ProfileScoreModel model = get(modelId);
        // 缺省取模型自身类型而非写死 user——会员模型从 UI 点主体详情时也该查会员快照
        String type = StringUtils.isBlank(subjectType) ? SubjectTypes.normalize(model.getSubjectType()) : subjectType;
        List<Map<String, Object>> history = snapshotMapper.selectHistory(model.getTenantId(),
            model.getSource(), model.getModelCode(), type, subjectId, 20);
        List<ProfileSubject> subjects = subjectMapper.selectList(new LambdaQueryWrapper<ProfileSubject>()
            .eq(ProfileSubject::getTenantId, model.getTenantId())
            .eq(ProfileSubject::getSource, model.getSource())
            .eq(ProfileSubject::getSubjectType, type)
            .eq(ProfileSubject::getSubjectId, subjectId)
            .last("LIMIT 1"));
        ProfileSubject subject = subjects.stream().findFirst().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelId", model.getModelId());
        out.put("modelCode", model.getModelCode());
        out.put("modelVersion", model.getModelVersion());
        out.put("subject", subject);
        out.put("subjectProperties", subject == null ? Map.of() : parseMap(subject.getProperties()));
        out.put("latest", history.isEmpty() ? null : history.get(0));
        out.put("history", history);
        return out;
    }

    public List<Map<String, Object>> trend(Long modelId, int days) {
        ProfileScoreModel model = get(modelId);
        // 批末回修(dsflash P2): model_code 唯一键含 subject_type, 不带类型会把
        // 员工/会员同名模型的快照混成一条趋势线
        return snapshotMapper.selectTrend(model.getTenantId(), model.getSource(),
            SubjectTypes.normalize(model.getSubjectType()), model.getModelCode(),
            Math.min(Math.max(1, days), 365));
    }

    // ---------------------------------------------------------------- 批9: 版本对比与发布纪律

    /**
     * 模型运行记录(版本对比的选材来源; 只列最近 50 条)。
     */
    public List<ProfileModelRun> runs(Long modelId) {
        ProfileScoreModel model = get(modelId);
        return runMapper.selectList(new LambdaQueryWrapper<ProfileModelRun>()
            .eq(ProfileModelRun::getModelId, modelId)
            .eq(ProfileModelRun::getTenantId, model.getTenantId())
            .orderByDesc(ProfileModelRun::getCreateTime)
            .last("LIMIT 50"));
    }

    /**
     * 版本对比(批9 Q1-A): 只允许选「已完成」的两次 run, 逐主体对比分数/分层。
     */
    public Map<String, Object> compare(Long modelId, Long runAId, Long runBId) {
        ProfileScoreModel model = get(modelId);
        ProfileModelRun runA = requireDoneRun(model, runAId);
        ProfileModelRun runB = requireDoneRun(model, runBId);
        // 批4A 护栏: 对比将载入两份 run 快照, 行数=run 落库总量(存档值, 免二次扫表)
        scaleGuard.checkModelSubjectsCount(Math.max(
            runA.getTotalSubjects() == null ? 0L : runA.getTotalSubjects(),
            runB.getTotalSubjects() == null ? 0L : runB.getTotalSubjects()));
        List<ProfileModelCompare.SubjectVersion> a = subjectVersionsOfRun(model.getTenantId(), runA);
        List<ProfileModelCompare.SubjectVersion> b = subjectVersionsOfRun(model.getTenantId(), runB);
        Map<String, Object> out = ProfileModelCompare.compare(a, b, 20);
        out.put("modelId", model.getModelId());
        out.put("runA", runBrief(runA));
        out.put("runB", runBrief(runB));
        out.put("psi", ProfilePsi.of(ProfileModelCompare.levelDistribution(a), ProfileModelCompare.levelDistribution(b)));
        out.put("distribution", ProfilePsi.distribution(ProfileModelCompare.levelDistribution(a),
            ProfileModelCompare.levelDistribution(b)));
        return out;
    }

    /**
     * 试算预览(批9 Q6-A): 用模型当前(草稿)配置对存量主体影子计算, 不落任何快照。
     * 输出新旧分布对比 + PSI 稳定性 + 样本量提示。
     */
    public Map<String, Object> dryRun(Long modelId) {
        ProfileScoreModel model = get(modelId);
        ProfileModelDefinition def = parseDefinition(model.getConfig());
        List<String> errors = ProfileModelConfig.validate(def);
        if (!errors.isEmpty()) {
            throw new ServiceException("模型配置不合法: " + String.join("; ", errors));
        }
        // 批4A 护栏: 试算与全量运行同一全量载入模式, 同阈值前置
        scaleGuard.checkModelSubjects(model.getTenantId(), model.getSource(),
            SubjectTypes.normalize(model.getSubjectType()));
        List<ProfileSubject> subjects = subjectMapper.selectList(new LambdaQueryWrapper<ProfileSubject>()
            .eq(ProfileSubject::getTenantId, model.getTenantId())
            .eq(ProfileSubject::getSource, model.getSource())
            .eq(ProfileSubject::getSubjectType, SubjectTypes.normalize(model.getSubjectType()))
            .orderByAsc(ProfileSubject::getSubjectType)
            .orderByAsc(ProfileSubject::getSubjectId));
        CachedModelData data = loadModelData(model, def, true);
        List<ProfileModelCompare.SubjectVersion> candidate = new ArrayList<>();
        for (ProfileSubject subject : subjects) {
            String key = subjectKey(subject.getSubjectType(), subject.getSubjectId());
            Map<String, BigDecimal> values = new HashMap<>();
            for (ProfileModelDefinition.ModelDimension dim : def.getDimensions()) {
                values.put(dim.getCode(), data.values.getOrDefault(dim.getCode(), Map.of()).get(key));
            }
            ProfileModelSnapshot snapshot = scoreOne(model, def, subject, values, data, null);
            candidate.add(new ProfileModelCompare.SubjectVersion(subject.getSubjectType(), subject.getSubjectId(),
                subject.getDisplayName(), snapshot.getLevel(), snapshot.getNormalizedScore()));
        }
        List<ProfileModelSubjectRow> current = snapshotMapper.selectLatestAll(
            model.getTenantId(), model.getSource(), SubjectTypes.normalize(model.getSubjectType()),
            model.getModelCode(), model.getModelVersion());
        List<ProfileModelCompare.SubjectVersion> base = new ArrayList<>();
        for (ProfileModelSubjectRow row : current) {
            base.add(new ProfileModelCompare.SubjectVersion(row.getSubjectType(), row.getSubjectId(),
                row.getDisplayName(), row.getLevel(), row.getNormalizedScore()));
        }
        Map<String, Object> out = ProfileModelCompare.compare(base, candidate, 20);
        out.put("modelId", model.getModelId());
        out.put("modelStatus", model.getStatus());
        out.put("currentVersion", model.getModelVersion());
        out.put("psi", ProfilePsi.of(ProfileModelCompare.levelDistribution(base), ProfileModelCompare.levelDistribution(candidate)));
        out.put("distribution", ProfilePsi.distribution(ProfileModelCompare.levelDistribution(base),
            ProfileModelCompare.levelDistribution(candidate)));
        return out;
    }

    /**
     * 发布(批9 Q6-A): 版本号落定 + 配置存档 + 状态切 PUBLISHED + 触发全量重算。
     * 读侧只认「已完成 run」的快照(SnapshotMapper 已过滤), 重算期间线上分数不混拼。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publish(Long modelId, ProfileModelPublishBo bo) {
        ProfileScoreModel model = get(modelId);
        if ("PUBLISHED".equals(model.getStatus())) {
            throw new ServiceException("模型已是发布态, 请先编辑存草稿再发布新版本");
        }
        String version = resolveVersion(model, bo == null ? null : bo.getVersion());
        ProfileModelConfigSnapshot archive = new ProfileModelConfigSnapshot();
        archive.setConfigSnapshotId(IdWorker.getId());
        archive.setTenantId(model.getTenantId());
        archive.setSource(model.getSource());
        archive.setModelId(model.getModelId());
        archive.setModelCode(model.getModelCode());
        archive.setModelVersion(version);
        archive.setModelType(model.getModelType());
        archive.setConfig(model.getConfig());
        archive.setScoreMax(model.getScoreMax());
        archive.setPublishedBy(currentUser());
        configSnapshotMapper.upsertJsonb(archive);
        if (bo != null && StringUtils.isNotBlank(bo.getNote())) {
            model.setRemark(StringUtils.substring(bo.getNote(), 0, 500));
        }
        model.setStatus("PUBLISHED");
        model.setModelVersion(version);
        model.setRevision((model.getRevision() == null ? 0 : model.getRevision()) + 1);
        model.setUpdateBy(currentUser());
        if (modelMapper.updateJsonb(model) == 0) {
            throw new ServiceException("模型已被其他人修改, 请刷新后重试");
        }
        invalidate();
        ProfileModelRun run = run(modelId, "PUBLISH");
        if (!"DONE".equals(run.getStatus())) {
            // 重算失败则整个发布回滚: 避免出现"已发布新版本但线上全是旧分数"的错位态
            throw new ServiceException("发布未生效, 全量重算失败: " + run.getErrorMsg());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model);
        out.put("run", run);
        out.put("version", version);
        return out;
    }

    /**
     * 一键回滚(批9 Q6-A): 把存档版本配置写回模型并重新发布重算。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rollback(Long modelId, String version) {
        ProfileScoreModel model = get(modelId);
        if (StringUtils.isBlank(version)) {
            throw new ServiceException("回滚版本不能为空");
        }
        ProfileModelConfigSnapshot archived = configSnapshotMapper.selectByVersion(
            model.getTenantId(), modelId, version.trim());
        if (archived == null) {
            throw new ServiceException("该版本没有配置存档: " + version);
        }
        model.setConfig(archived.getConfig());
        model.setScoreMax(archived.getScoreMax());
        // 批10 R5: 模型类型随存档恢复(存档早于本批的行无 model_type, 保持当前值)
        if (StringUtils.isNotBlank(archived.getModelType())) {
            model.setModelType(archived.getModelType());
        }
        model.setStatus("PUBLISHED");
        model.setModelVersion(archived.getModelVersion());
        model.setRevision((model.getRevision() == null ? 0 : model.getRevision()) + 1);
        model.setUpdateBy(currentUser());
        if (modelMapper.updateJsonb(model) == 0) {
            throw new ServiceException("模型已被其他人修改, 请刷新后重试");
        }
        invalidate();
        ProfileModelRun run = run(modelId, "ROLLBACK");
        if (!"DONE".equals(run.getStatus())) {
            throw new ServiceException("回滚未生效, 全量重算失败: " + run.getErrorMsg());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model);
        out.put("run", run);
        out.put("rolledBackTo", archived.getModelVersion());
        return out;
    }

    /** 配置版本存档列表(历史可 diff / 回滚选材)。 */
    public List<ProfileModelConfigSnapshot> configSnapshots(Long modelId) {
        ProfileScoreModel model = get(modelId);
        return configSnapshotMapper.selectByModel(model.getTenantId(), modelId, 50);
    }

    private ProfileModelRun requireDoneRun(ProfileScoreModel model, Long runId) {
        if (runId == null) {
            throw new ServiceException("runId 不能为空");
        }
        ProfileModelRun run = runMapper.selectById(runId);
        if (run == null || !model.getModelId().equals(run.getModelId())) {
            throw new ServiceException("运行记录不属于该模型: " + runId);
        }
        if (!"DONE".equals(run.getStatus())) {
            throw new ServiceException("只能对比已完成的运行(该 run 状态=" + run.getStatus() + ")");
        }
        return run;
    }

    private List<ProfileModelCompare.SubjectVersion> subjectVersionsOfRun(String tenantId, ProfileModelRun run) {
        List<ProfileModelCompare.SubjectVersion> out = new ArrayList<>();
        for (Map<String, Object> row : snapshotMapper.selectByRun(tenantId, run.getRunId())) {
            out.add(new ProfileModelCompare.SubjectVersion(
                String.valueOf(row.get("subjectType")), String.valueOf(row.get("subjectId")),
                row.get("displayName") == null ? null : String.valueOf(row.get("displayName")),
                row.get("level") == null ? null : String.valueOf(row.get("level")),
                toDecimal(row.get("normalizedScore"))));
        }
        return out;
    }

    private Map<String, Object> runBrief(ProfileModelRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.getRunId());
        out.put("modelVersion", run.getModelVersion());
        out.put("status", run.getStatus());
        out.put("triggerType", run.getTriggerType());
        out.put("totalSubjects", run.getTotalSubjects());
        out.put("startedAt", run.getStartedAt());
        out.put("finishedAt", run.getFinishedAt());
        return out;
    }

    /** 版本号解析: 未指定则自动递增(v12 → v13)。 */
    private String resolveVersion(ProfileScoreModel model, String provided) {
        String version;
        if (StringUtils.isNotBlank(provided)) {
            version = provided.trim();
            if (version.length() > 32) {
                throw new ServiceException("版本号不能超过 32 个字符");
            }
        } else {
            version = nextVersion(model.getModelVersion());
        }
        if (version.equals(model.getModelVersion())) {
            throw new ServiceException("新版本号不能与当前版本相同: " + version);
        }
        // 版本号不允许与已存档版本撞车（撞车会让 upsert 覆盖历史存档, 回滚链被破坏）
        int guard = 0;
        while (configSnapshotMapper.selectByVersion(model.getTenantId(), model.getModelId(), version) != null) {
            if (++guard > 100) {
                throw new ServiceException("版本号冲突无法自增, 请手动指定版本号");
            }
            version = nextVersion(version);
        }
        return version;
    }

    static String nextVersion(String current) {
        if (current == null || current.isBlank()) {
            return "v1";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)(\\d+)$").matcher(current.trim());
        if (m.matches()) {
            try {
                long n = Long.parseLong(m.group(2));
                if (n < 9_999) {
                    return m.group(1) + (n + 1);
                }
            } catch (NumberFormatException overflow) {
                // 数字后缀超出 long 范围(版本号最长 32 位)时走 -next 兜底
            }
        }
        return current + "-next";
    }

    // ---------------------------------------------------------------- 运行内核

    private void writeSnapshots(ProfileScoreModel model, ProfileModelDefinition def,
                                List<ProfileSubject> subjects, CachedModelData data,
                                ProfileModelRun run, String trigger) {
        // 批10 Q2: 「取前链 + 写全部快照」包进一个持 pg advisory xact lock 的事务——
        // 任何写入者(全量/增量/回放)先在 DB 层排队, 前链指向唯一, 链分叉结构上不可能。
        // 单事务附带收益: 全量失败不再遗留"FAILED run 的孤儿快照"(整批回滚)。
        // 代价: 进度更新随事务提交才可见, 演示量级毫秒级无感。
        txTemplate.executeWithoutResult(status -> {
            snapshotMapper.advisoryXactLock(snapshotChainKey(model));
            doWriteSnapshots(model, def, subjects, data, run);
        });
    }

    private void doWriteSnapshots(ProfileScoreModel model, ProfileModelDefinition def,
                                  List<ProfileSubject> subjects, CachedModelData data,
                                  ProfileModelRun run) {
        Map<String, Long> latest = latestIds(model);
        int done = 0;
        for (ProfileSubject subject : subjects) {
            String key = subjectKey(subject.getSubjectType(), subject.getSubjectId());
            Map<String, BigDecimal> values = new HashMap<>();
            for (ProfileModelDefinition.ModelDimension dim : def.getDimensions()) {
                values.put(dim.getCode(), data.values.getOrDefault(dim.getCode(), Map.of()).get(key));
            }
            ProfileModelSnapshot snapshot = scoreOne(model, def, subject, values, data, run.getRunId());
            snapshot.setPrevSnapshotId(latest.get(key));
            snapshotMapper.insertJsonb(snapshot);
            latest.put(key, snapshot.getModelSnapshotId());
            done++;
            if (done % 200 == 0) {
                run.setDoneSubjects(done);
                runMapper.updateById(run);
            }
        }
    }

    /** 快照链 advisory 键: 同租户同模型一条链一把锁(与 Redis 任务锁互补: DB 层防最后竞态)。 */
    private String snapshotChainKey(ProfileScoreModel model) {
        return "profile:snapshot:" + model.getTenantId() + ":" + model.getModelCode();
    }

    private ProfileModelSnapshot scoreOne(ProfileScoreModel model, ProfileModelDefinition def,
                                          ProfileSubject subject, Map<String, BigDecimal> values,
                                          CachedModelData data, Long runId) {
        Map<String, Object> dimensions = new LinkedHashMap<>();
        BigDecimal rawWeighted = BigDecimal.ZERO;
        BigDecimal normalizedWeighted = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        for (ProfileModelDefinition.ModelDimension dim : def.getDimensions()) {
            BigDecimal value = values.get(dim.getCode());
            ProfileBinResolver.Population population = data.populations.get(dim.getCode());
            ProfileBinResolver.BinResult bin = ProfileBinResolver.resolve(dim.getBins(), value, population);
            BigDecimal maxScore = ProfileBinResolver.maxScore(dim.getBins());
            BigDecimal normalized = bin.getScore().multiply(HUNDRED)
                .divide(maxScore, 2, RoundingMode.HALF_UP);
            BigDecimal weight = dim.getWeight() == null ? BigDecimal.ONE : dim.getWeight();
            BigDecimal weighted = normalized.multiply(weight).setScale(4, RoundingMode.HALF_UP);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", dim.getCode());
            entry.put("name", dim.getName());
            entry.put("value", value);
            entry.put("score", bin.getScore());
            entry.put("maxScore", maxScore);
            entry.put("normalizedScore", normalized);
            entry.put("weight", weight);
            entry.put("weightedScore", weighted.setScale(2, RoundingMode.HALF_UP));
            entry.put("method", bin.getMethod());
            entry.put("edges", bin.getEdges());
            if (bin.getRank() != null) {
                entry.put("rank", bin.getRank());
            }
            dimensions.put(dim.getCode(), entry);
            scores.put(dim.getCode(), bin.getScore());
            rawWeighted = rawWeighted.add(bin.getScore().multiply(weight));
            normalizedWeighted = normalizedWeighted.add(weighted);
            weightSum = weightSum.add(weight);
        }
        BigDecimal rawScore = weightSum.signum() > 0
            ? rawWeighted.divide(weightSum, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal normalizedScore = weightSum.signum() > 0
            ? normalizedWeighted.divide(weightSum, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        String level = levelOf(def, scores, normalizedScore);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("preset", def.getPreset());
        detail.put("modelScore", rawScore);
        detail.put("normalizedScore", normalizedScore);
        detail.put("level", level);
        detail.put("dimensions", dimensions);

        ProfileModelSnapshot snapshot = new ProfileModelSnapshot();
        snapshot.setModelSnapshotId(IdWorker.getId());
        snapshot.setTenantId(model.getTenantId());
        snapshot.setSource(model.getSource());
        snapshot.setModelId(model.getModelId());
        snapshot.setModelCode(model.getModelCode());
        snapshot.setModelVersion(model.getModelVersion());
        snapshot.setSubjectType(StringUtils.isBlank(subject.getSubjectType()) ? "user" : subject.getSubjectType());
        snapshot.setSubjectId(subject.getSubjectId());
        snapshot.setModelScore(rawScore);
        snapshot.setNormalizedScore(normalizedScore);
        snapshot.setLevel(level);
        snapshot.setDetail(JsonUtils.toJsonString(detail));
        snapshot.setRunId(runId);
        snapshot.setCalcTime(new Date());
        try {
            snapshot.setCreateBy(LoginHelper.getUserId());
        } catch (Exception ignored) {
            // 系统线程无登录态
        }
        return snapshot;
    }

    private String levelOf(ProfileModelDefinition def, Map<String, BigDecimal> scores, BigDecimal normalized) {
        if ("RFM".equalsIgnoreCase(def.getPreset())) {
            BigDecimal r = scores.get("R");
            BigDecimal f = scores.get("F");
            BigDecimal m = scores.get("M");
            if (r != null && f != null && m != null) {
                return ProfileRfmLevel.of(r, f, m, def.getRfmMid());
            }
        }
        return ProfileRfmLevel.generic(normalized);
    }

    private Map<String, Long> latestIds(ProfileScoreModel model) {
        Map<String, Long> out = new HashMap<>();
        for (Map<String, Object> row : snapshotMapper.selectLatestIds(
            model.getTenantId(), model.getSource(), model.getModelCode())) {
            String type = String.valueOf(row.get("subjectType"));
            String id = String.valueOf(row.get("subjectId"));
            Object snapshotId = row.get("modelSnapshotId");
            if (snapshotId instanceof Number n) {
                out.put(subjectKey(type, id), n.longValue());
            }
        }
        return out;
    }

    private CachedModelData cachedModelData(ProfileScoreModel model, ProfileModelDefinition def) {
        String key = cacheKey(model);
        CachedModelData cached = populationCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt < POPULATION_TTL_MS) {
            return cached;
        }
        return loadModelData(model, def, false);
    }

    private CachedModelData loadModelData(ProfileScoreModel model, ProfileModelDefinition def, boolean force) {
        String key = cacheKey(model);
        long now = System.currentTimeMillis();
        CachedModelData cached = populationCache.get(key);
        if (!force && cached != null && now - cached.loadedAt < POPULATION_TTL_MS) {
            return cached;
        }
        Map<String, Map<String, BigDecimal>> values = new LinkedHashMap<>();
        Map<String, ProfileBinResolver.Population> populations = new LinkedHashMap<>();
        for (ProfileModelDefinition.ModelDimension dim : def.getDimensions()) {
            Map<String, BigDecimal> metric = fetchMetric(model, dim.getCalc());
            values.put(dim.getCode(), metric);
            populations.put(dim.getCode(), ProfileBinResolver.Population.of(metric.values()));
        }
        CachedModelData data = new CachedModelData(values, populations, now);
        populationCache.put(key, data);
        return data;
    }

    private Map<String, BigDecimal> fetchMetric(ProfileScoreModel model, ProfileModelDefinition.MetricCalc calc) {
        String type = calc.getType() == null ? "" : calc.getType().trim().toUpperCase();
        // 模型指标只统计自己那类主体(题0-乙; mapper 第三参预留位启用)
        String modelSubjectType = SubjectTypes.normalize(model.getSubjectType());
        List<String> events = normalizeEvents(calc.getEventTypes());
        List<Map<String, Object>> rows = switch (type) {
            case "COUNT" -> metricMapper.selectCount(model.getTenantId(), model.getSource(), modelSubjectType, events);
            case "COUNT_DISTINCT" -> metricMapper.selectCountDistinct(model.getTenantId(), model.getSource(),
                modelSubjectType, events, calc.getProp());
            case "SUM" -> metricMapper.selectSum(model.getTenantId(), model.getSource(), modelSubjectType, events, calc.getProp());
            case "AVG" -> metricMapper.selectAvg(model.getTenantId(), model.getSource(), modelSubjectType, events, calc.getProp());
            case "LAST_VALUE" -> metricMapper.selectLastValue(model.getTenantId(), model.getSource(),
                modelSubjectType, events, calc.getProp());
            case "RATE" -> metricMapper.selectRate(model.getTenantId(), model.getSource(), modelSubjectType,
                calc.getNumerator(), calc.getDenominator());
            default -> metricMapper.selectRecencyDays(model.getTenantId(), model.getSource(), modelSubjectType, events);
        };
        Map<String, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String subjectType = row.get("subject_type") == null ? "user" : String.valueOf(row.get("subject_type"));
            String subjectId = row.get("subject_id") == null ? null : String.valueOf(row.get("subject_id"));
            BigDecimal value = toDecimal(row.get("metric_value"));
            if (subjectId != null && value != null) {
                out.put(subjectKey(subjectType, subjectId), value);
            }
        }
        return out;
    }

    private List<String> normalizeEvents(List<String> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String event : events) {
            if (StringUtils.isNotBlank(event)) {
                out.add(event.trim());
            }
        }
        return new ArrayList<>(out);
    }

    private BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal b) {
            return b;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(6, RoundingMode.HALF_UP);
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal average(List<ProfileModelSubjectRow> rows,
                               java.util.function.Function<ProfileModelSubjectRow, BigDecimal> getter) {
        List<BigDecimal> values = rows.stream().map(getter).filter(java.util.Objects::nonNull).toList();
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dimensionStats(List<ProfileModelSubjectRow> rows) {
        Map<String, Map<String, Object>> stats = new LinkedHashMap<>();
        for (ProfileModelSubjectRow row : rows) {
            Map<String, Object> detail = parseMap(row.getDetail());
            Map<String, Object> dimensions = detail.get("dimensions") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
            for (Map.Entry<String, Object> e : dimensions.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> dim = (Map<String, Object>) raw;
                Map<String, Object> stat = stats.computeIfAbsent(e.getKey(), code -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", code);
                    item.put("name", dim.get("name"));
                    item.put("sumScore", BigDecimal.ZERO);
                    item.put("sumValue", BigDecimal.ZERO);
                    item.put("count", 0);
                    item.put("distribution", new LinkedHashMap<String, Long>());
                    return item;
                });
                stat.put("name", dim.get("name") == null ? stat.get("name") : dim.get("name"));
                BigDecimal score = toDecimal(dim.get("score"));
                BigDecimal value = toDecimal(dim.get("value"));
                if (score != null) {
                    stat.put("sumScore", ((BigDecimal) stat.get("sumScore")).add(score));
                    Map<String, Long> distribution = (Map<String, Long>) stat.get("distribution");
                    distribution.merge(score.stripTrailingZeros().toPlainString(), 1L, Long::sum);
                }
                if (value != null) {
                    stat.put("sumValue", ((BigDecimal) stat.get("sumValue")).add(value));
                }
                stat.put("count", (Integer) stat.get("count") + 1);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> stat : stats.values()) {
            int count = (Integer) stat.get("count");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", stat.get("code"));
            item.put("name", stat.get("name"));
            item.put("count", count);
            item.put("avgScore", count == 0 ? BigDecimal.ZERO
                : ((BigDecimal) stat.get("sumScore")).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP));
            item.put("avgValue", count == 0 ? BigDecimal.ZERO
                : ((BigDecimal) stat.get("sumValue")).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP));
            List<Map<String, Object>> dist = new ArrayList<>();
            ((Map<String, Long>) stat.get("distribution")).entrySet().stream()
                .sorted(Comparator.comparing(e -> new BigDecimal(e.getKey())))
                .forEach(e -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("score", e.getKey());
                    d.put("count", e.getValue());
                    dist.add(d);
                });
            item.put("distribution", dist);
            out.add(item);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parseMap(json);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ProfileModelDefinition parseDefinition(String json) {
        ProfileModelDefinition def = JsonUtils.parseObject(json, ProfileModelDefinition.class);
        return def == null ? new ProfileModelDefinition() : def;
    }

    private void validateBo(ProfileScoreModelBo bo, boolean requireCode) {
        if (requireCode && (StringUtils.isBlank(bo.getModelCode())
            || !bo.getModelCode().trim().matches("^[A-Za-z][A-Za-z0-9_]{0,63}$"))) {
            throw new ServiceException("modelCode 只允许字母开头的字母/数字/下划线, 最长 64");
        }
        if (StringUtils.isBlank(bo.getModelName()) || bo.getModelName().trim().length() > 100) {
            throw new ServiceException("modelName 不能为空且不能超过 100 字");
        }
        if (bo.getScoreMax() == null || bo.getScoreMax().signum() <= 0
            || bo.getScoreMax().compareTo(MAX_SCORE_MAX) > 0) {
            throw new ServiceException("scoreMax 必须在 1~1000 之间");
        }
        // 批5A: RFM 分层阈值可配, 非法值拦截(1~5 分制开区间)
        Object mid = bo.getConfig() == null ? null : bo.getConfig().get("rfmMid");
        if (mid != null) {
            BigDecimal rfmMid;
            try {
                rfmMid = new BigDecimal(String.valueOf(mid));
            } catch (NumberFormatException e) {
                throw new ServiceException("rfmMid 必须是数字(1~5 之间, 缺省 3.5)");
            }
            if (rfmMid.compareTo(BigDecimal.ONE) <= 0 || rfmMid.compareTo(BigDecimal.valueOf(5)) >= 0) {
                throw new ServiceException("rfmMid 必须在 1~5 之间(不含端点, 缺省 3.5)");
            }
        }
        if (StringUtils.isNotBlank(bo.getModelVersion()) && bo.getModelVersion().trim().length() > 32) {
            throw new ServiceException("modelVersion 不能超过 32 个字符");
        }
        ProfileModelDefinition def = JsonUtils.parseObject(JsonUtils.toJsonString(bo.getConfig()),
            ProfileModelDefinition.class);
        List<String> errors = ProfileModelConfig.validate(def);
        if (!errors.isEmpty()) {
            throw new ServiceException("模型配置不合法: " + String.join("; ", errors));
        }
    }

    private String resolveType(ProfileScoreModelBo bo) {
        if (StringUtils.isNotBlank(bo.getModelType())) {
            String type = bo.getModelType().trim().toUpperCase();
            return "RFM".equals(type) ? "RFM" : "GENERIC";
        }
        Object preset = bo.getConfig() == null ? null : bo.getConfig().get("preset");
        return preset != null && "RFM".equalsIgnoreCase(String.valueOf(preset)) ? "RFM" : "GENERIC";
    }

    private void invalidate() {
        populationCache.clear();
    }

    private String cacheKey(ProfileScoreModel model) {
        return model.getTenantId() + "|" + model.getSource() + "|" + model.getModelId()
            + "|" + model.getModelVersion();
    }

    private String subjectKey(String subjectType, String subjectId) {
        return (StringUtils.isBlank(subjectType) ? "user" : subjectType) + "|" + subjectId;
    }

    private void requireSource(String source) {
        if (StringUtils.isBlank(source)) {
            throw new ServiceException("source 不能为空");
        }
        if (source.length() > 32) {
            throw new ServiceException("source 长度不能超过 32");
        }
    }

    private Long currentUser() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentTenant() {
        try {
            String t = TenantHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        try {
            String t = LoginHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 非登录上下文
        }
        return com.smartdock.profile.engine.ProfileTenantContext.require();
    }

    private record CachedModelData(Map<String, Map<String, BigDecimal>> values,
                                   Map<String, ProfileBinResolver.Population> populations,
                                   long loadedAt) {
    }
}
