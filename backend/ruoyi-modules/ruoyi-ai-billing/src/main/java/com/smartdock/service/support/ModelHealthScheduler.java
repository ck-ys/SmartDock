package com.smartdock.service.support;

import com.baomidou.lock.annotation.Lock4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.DcsAiModelHealth;
import com.smartdock.domain.vo.DcsAiTestResultVo;
import com.smartdock.mapper.DcsAiConfigMapper;
import com.smartdock.mapper.DcsAiModelHealthMapper;
import com.smartdock.service.IDcsAiConfigService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型定时巡检（AI 缺口批 Q9，拍板全A，2026-10-14）。
 *
 * <p>每日 04:00（与钥匙余额刷新 04:30 错峰）探活各租户「在用模型」，结果滚动落到
 * {@code dcs_ai_model_health}（每 tenant×config 一行），管理端模型列表读最新行显示健康徽标。
 * 连续 {@link #ALERT_STREAK_DAYS} 天 FAIL → 站内信 MODEL_HEALTH_DOWN（复用批1
 * publishCreditAlert 同款 outbox 管道，键 modelHealthDown:{tenant}:{config}:{yyyyMM} 每模型
 * 每月一告——恢复后连败清零，下一轮再连败跨月自然重报）。</p>
 *
 * <p>探活目标三源合一（去重）：①租户助手默认模型 ②换模白名单 ③最近 7 天真实用过的配置。
 * 无用量且无设置 = 没人在用，探活纯属花钱，整租户跳过。每次探活是一次极小模型调用
 * （与「测速」同芯），成本随在用模型数线性、量级每天个位数次，可接受。</p>
 *
 * <p>无启动补跑：连败计数容忍空窗（漏跑一天 streak 不涨也不清，次日续跑即可），
 * 告警又是月频去重——补跑只会多花钱不多报信息。</p>
 *
 * <p>时区口径：cron 用 JVM 默认时区、月键用 BEIJING——两者都以启动参数
 * {@code -Duser.timezone=GMT+8} 为唯一防线（漏参数历史事故两轮，见 AGENTS.md）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelHealthScheduler {

    /** 告警判重时区(与启动参数 -Duser.timezone=GMT+8 同口径) */
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    /** 连败告警阈值(天)：fail_streak 达到即告警(>= 而非 ==，容忍漏跑空窗) */
    static final int ALERT_STREAK_DAYS = 3;

    /** 在用判定窗口(天)：最近 N 天有用量的配置才探活 */
    static final int USAGE_WINDOW_DAYS = 7;

    /** detail 截断长度(表列宽 500) */
    private static final int DETAIL_MAX = 500;

    /** AI 配置管理页(通知跳转)。k3 P0-1 回修：菜单 9060 挂在「租户管理」下，真实路由
     *  是 /tenant/ai-config（与批1 /tenant/ai-credit 同款前缀），写 /ai/ai-config 会 404。
     *  包级可见供单测断言防再漂移。 */
    static final String REF_URL = "/tenant/ai-config";

    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyyMM");

    private final DcsAiModelHealthMapper healthMapper;
    private final DcsAiConfigMapper aiConfigMapper;
    private final IDcsAiConfigService aiConfigService;
    private final WalletAlertService walletAlertService;

    /** 每日 04:00 巡检(与余额刷新 04:30 错峰) */
    @Scheduled(cron = "0 0 4 * * ?")
    @Lock4j(keys = {"'model-health-patrol'"})
    public void patrol() {
        Date since = Date.from(LocalDate.now(BEIJING).minusDays(USAGE_WINDOW_DAYS)
            .atStartOfDay(BEIJING).toInstant());
        List<String> tenants = healthMapper.patrolTenants(since);
        int fail = 0;
        for (String tenantId : tenants) {
            try {
                fail += patrolTenant(tenantId, since);
            } catch (Exception e) {
                // per-租户兜底：单租户失败(如库抖动)不拦其余租户当日巡检
                log.error("[ModelHealth] 租户 {} 巡检失败, 跳过继续", tenantId, e);
            }
        }
        log.info("[ModelHealth] 巡检完成: {} 个租户, {} 个模型 FAIL", tenants.size(), fail);
    }

    /** 单租户巡检：三源取目标 → 逐配置探活落行，返回 FAIL 行数 */
    private int patrolTenant(String tenantId, Date since) {
        Set<Long> targets = resolveTargets(tenantId, since);
        int fail = 0;
        for (Long configId : targets) {
            try {
                if (probeOne(tenantId, configId)) {
                    fail++;
                }
            } catch (Exception e) {
                // per-配置兜底：探活/落库异常跳过该配置(次日照常巡检)
                log.error("[ModelHealth] 租户 {} 配置 {} 探活处理失败, 跳过继续", tenantId, configId, e);
            }
        }
        return fail;
    }

    /** 三源目标解析：助手默认 + 换模白名单 + 近 7 天用过的配置（去重稳定序） */
    private Set<Long> resolveTargets(String tenantId, Date since) {
        Set<Long> targets = new LinkedHashSet<>();
        Map<String, Object> setting = healthMapper.assistantModelSetting(tenantId);
        if (setting != null) {
            Object def = setting.get("defaultConfigId");
            if (def instanceof Number n) {
                targets.add(n.longValue());
            }
            parseScopeIds(setting.get("switchScope")).forEach(targets::add);
        }
        for (Map<String, Object> row : healthMapper.recentUsedConfigs(tenantId, since)) {
            Object cid = row.get("config_id");
            if (cid instanceof Number n) {
                targets.add(n.longValue());
            }
        }
        return targets;
    }

    /** switch_scope JSON 串 → 配置 id 集（坏 JSON=空集，与助手侧 fail-open 同口径不锁巡检）。
     *  用 hutool JSONUtil 而非 JsonUtils：后者静态取 Spring 容器里的 ObjectMapper，
     *  调度上下文外（单测/极早期）会炸；纯解析不需要容器。 */
    private Set<Long> parseScopeIds(Object scope) {
        if (!(scope instanceof String s) || s.isBlank()) {
            return Set.of();
        }
        try {
            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(s);
            Set<Long> ids = new LinkedHashSet<>();
            for (Object o : arr) {
                if (o instanceof Number n) {
                    ids.add(n.longValue());
                } else {
                    ids.add(Long.parseLong(String.valueOf(o)));
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("[ModelHealth] switch_scope 解析失败按空集处理 scope={} 原因={}", s, e.getMessage());
            return Set.of();
        }
    }

    /**
     * 单配置探活落行。返回是否 FAIL。
     * 停用/不存在的配置跳过不建档（徽标只对可切换的行有意义）。
     */
    private boolean probeOne(String tenantId, Long configId) {
        DcsAiTestResultVo result = aiConfigService.patrolProbe(configId);
        if (result == null) {
            return false;
        }
        boolean ok = Boolean.TRUE.equals(result.getSuccess());
        // 冗余显示列：探活与取显示名各查一次同 PK 行，代价可忽略
        DcsAiConfig config = TenantHelper.ignore(() -> aiConfigMapper.selectById(configId));
        String provider = config == null ? null : config.getProvider();
        String modelName = config == null ? null : config.getModelName();

        DcsAiModelHealth prev = healthMapper.selectOne(new LambdaQueryWrapper<DcsAiModelHealth>()
            .eq(DcsAiModelHealth::getTenantId, tenantId)
            .eq(DcsAiModelHealth::getConfigId, configId));
        Date now = new Date();
        int streak = nextStreak(ok, prev == null ? null : prev.getFailStreak());
        String detail = StrUtil.maxLength(StrUtil.blankToDefault(result.getMessage(), ok ? "连接成功" : "连接失败"), DETAIL_MAX);
        Integer latency = result.getLatencyMs() == null ? null : result.getLatencyMs().intValue();

        if (prev == null) {
            DcsAiModelHealth row = new DcsAiModelHealth();
            row.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
            row.setTenantId(tenantId);
            row.setConfigId(configId);
            row.setProvider(provider);
            row.setModelName(modelName);
            row.setStatus(ok ? "OK" : "FAIL");
            row.setLatencyMs(latency);
            row.setDetail(detail);
            row.setFailStreak(streak);
            row.setCheckTime(now);
            row.setCreateTime(now);
            healthMapper.insert(row);
        } else {
            prev.setProvider(provider);
            prev.setModelName(modelName);
            prev.setStatus(ok ? "OK" : "FAIL");
            prev.setLatencyMs(latency);
            prev.setDetail(detail);
            prev.setFailStreak(streak);
            prev.setCheckTime(now);
            healthMapper.updateById(prev);
        }

        if (!ok && streak >= ALERT_STREAK_DAYS) {
            publishHealthAlert(tenantId, configId, modelName, provider, streak, detail);
        }
        return !ok;
    }

    /** 纯函数(可测)：探活结果 × 上次连败数 → 新连败数（OK 归零；FAIL 累加；首败=1） */
    static int nextStreak(boolean ok, Integer prevStreak) {
        if (ok) {
            return 0;
        }
        return prevStreak == null ? 1 : prevStreak + 1;
    }

    /* 包内测试钩子（单测直探私有路径，不起 Spring） */
    boolean probeOneForTest(String tenantId, Long configId) {
        return probeOne(tenantId, configId);
    }

    Set<Long> resolveTargetsForTest(String tenantId, Date since) {
        return resolveTargets(tenantId, since);
    }

    /** 连败告警：月频去重（恢复清零后跨月再连败自然重报；同月内不轰炸） */
    private void publishHealthAlert(String tenantId, Long configId, String modelName,
                                    String provider, int streak, String detail) {
        String display = StrUtil.blankToDefault(modelName, String.valueOf(configId));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("configId", configId);
        extra.put("provider", provider);
        extra.put("modelName", modelName);
        extra.put("failStreak", streak);
        walletAlertService.publishCreditAlert(tenantId, "MODEL_HEALTH_DOWN",
            "AI 模型连续异常",
            "模型「" + display + "」已连续 " + streak + " 天探活失败（最近原因：" + detail + "），"
                + "期间调用该模型的对话会自动回退其它可用模型；请到 AI 配置页检查钥匙与网络。",
            REF_URL, extra, "modelHealthDown:" + tenantId + ":" + configId + ":"
                + LocalDate.now(BEIJING).format(MONTH_KEY));
    }
}
