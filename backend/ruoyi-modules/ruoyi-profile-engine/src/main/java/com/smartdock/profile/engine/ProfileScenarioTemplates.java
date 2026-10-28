package com.smartdock.profile.engine;

import com.smartdock.profile.model.ScenarioTemplateDef;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置场景模板库(F 片): 7 套「维度+权重+分群+漏斗」四件套预填, 全部 EVENT_AGG。
 *
 * <p>口径: 内部协作采用度是推荐默认(★), 与系统内置 dcs 维度/权重/分群/漏斗逐字一致,
 * 空系统上套用即还原内置口径; 其余 6 套是行业起步包——事件名是通用示例, 用户套用后
 * 要在接入指南指引下换成自己业务的真实事件名(模板描述里明说, 不装「开箱即准」)。</p>
 *
 * <p>结构护栏由 {@code ProfileScenarioTemplatesTest} 钉死: 每套权重合计≈1 且 ⊆ 维度码,
 * 分群规则过 {@code ProfileSegmentRule.validate}, 维度配置过 {@code ProfileDimensionConfig.validate}。</p>
 *
 * @author smartdock
 */
public final class ProfileScenarioTemplates {

    private ProfileScenarioTemplates() {
    }

    public static final String INTERNAL_COLLAB = "internal_collab";

    private static final Map<String, Object> CALC_COMPLETION = calc(
        score("linear", 0, 1, "higher"), metricRate("data.audit_passed", "task.published"), "all", null, "neutral");
    private static final Map<String, Object> CALC_RESPONSE = calc(
        score("linear", 0, 72, "lower"), metricAvgProp("responseHours", "data.submitted"), "all", null, "neutral");
    private static final Map<String, Object> CALC_QUALITY = calc(
        score("linear", 0, 0.5, "lower"), metricRate("data.audit_rejected", "data.submitted"), "all", null, "neutral");
    private static final Map<String, Object> CALC_NEGATIVE = calc(
        bucketScore(), metricCount("data.audit_rejected"), "rolling", 30, "neutral");

    public static List<ScenarioTemplateDef> builtin() {
        List<ScenarioTemplateDef> out = new ArrayList<>();
        out.add(internalCollab());
        out.add(retailMall());
        out.add(foodDelivery());
        out.add(tourism());
        out.add(community());
        out.add(saasTool());
        out.add(mesOperator());
        return out;
    }

    public static ScenarioTemplateDef byCode(String code) {
        for (ScenarioTemplateDef def : builtin()) {
            if (def.getCode().equals(code)) {
                return def;
            }
        }
        return null;
    }

    /** ★内部协作采用度: 与内置 dcs 口径逐字一致(v1-builtin 权重/内置四维度/内置两分群/内置交付漏斗)。 */
    private static ScenarioTemplateDef internalCollab() {
        ScenarioTemplateDef def = base(INTERNAL_COLLAB, "内部协作采用度", "内部协作",
            "系统自用的打分口径: 发布任务→提交数据→审核通过这条链, 看每个人用得多好。空系统套用即还原内置配置。",
            true);
        dim(def, "completion", "完成率", CALC_COMPLETION, 1);
        dim(def, "response", "响应时效", CALC_RESPONSE, 2);
        dim(def, "quality", "填报质量", CALC_QUALITY, 3);
        dim(def, "negative", "负面指标", CALC_NEGATIVE, 4);
        def.getWeights().put("completion", 0.35);
        def.getWeights().put("response", 0.25);
        def.getWeights().put("quality", 0.25);
        def.getWeights().put("negative", 0.15);
        seg(def, "high_value_active", "高价值活跃", "总分不低于 75 的主体",
            cond("ALL", "totalScore", "GTE", 75));
        seg(def, "low_score_watch", "低分待关注", "总分低于 60、需要复核的主体",
            cond("ALL", "totalScore", "LT", 60));
        funnel(def, "task_delivery", "任务交付",
            steps("task.published", "任务发布", "data.submitted", "数据提交", "data.audit_passed", "审核通过"), 30);
        return def;
    }

    private static ScenarioTemplateDef retailMall() {
        ScenarioTemplateDef def = base("retail_mall", "零售商城", "零售商城",
            "给顾客会员打分: 买得多、买得贵、退款少的分高。事件名(如 order.paid)请换成你商城的真实事件名。",
            false);
        dim(def, "purchase_frequency", "购买频次", calc(score("linear", 0, 20, "higher"),
            metricCount("order.paid"), "rolling", 90, "zero"), 1);
        dim(def, "avg_order_amount", "客单价", calc(score("linear", 50, 500, "higher"),
            metricAvgProp("amount", "order.paid"), "all", null, "neutral"), 2);
        dim(def, "refund_rate", "退款率", calc(score("linear", 0, 0.3, "lower"),
            metricRate("order.refunded", "order.paid"), "all", null, "neutral"), 3);
        dim(def, "category_breadth", "购买品类宽度", calc(score("linear", 0, 5, "higher"),
            metricCountDistinct("order.paid", "skuId"), "rolling", 180, "zero"), 4);
        w(def, 0.35, 0.35, 0.15, 0.15);
        // 三审 P0 回修: dimension.<码> 取的是 0~100 归一分数, 不是业务原值——
        // 阈值一律按维度 score 的 lo/hi 线性换算成分数口径(lower 方向反向), 描述里注明换算。
        seg(def, "high_value", "高价值顾客", "客单价维度分不低于 56(约对应客单价 300 元)",
            cond("ALL", "dimension.avg_order_amount", "GTE", 56));
        seg(def, "churn_watch", "流失预警", "超过 60 天没有任何动作的顾客",
            cond("ALL", "lastEventDays", "GTE", 60));
        funnel(def, "mall_conversion", "逛购下单",
            steps("page_view", "看商品", "cart.add", "加入购物车", "order.paid", "支付下单"), 7);
        return def;
    }

    private static ScenarioTemplateDef foodDelivery() {
        ScenarioTemplateDef def = base("food_delivery", "外卖平台", "外卖平台",
            "给点单顾客打分: 常点单、准时率高、投诉少的分高。事件名请换成你平台的真实事件名。",
            false);
        dim(def, "order_frequency", "下单频次", calc(score("linear", 0, 10, "higher"),
            metricCount("order.paid"), "rolling", 30, "zero"), 1);
        dim(def, "on_time_rate", "配送准时率", calc(score("linear", 0, 1, "higher"),
            metricRate("order.delivered_on_time", "order.delivered"), "all", null, "neutral"), 2);
        dim(def, "complaint_rate", "投诉率", calc(score("linear", 0, 0.2, "lower"),
            metricRate("order.complained", "order.paid"), "all", null, "neutral"), 3);
        w(def, 0.4, 0.35, 0.25);
        seg(def, "heavy_users", "高频用户", "下单频次维度分不低于 30(约对应近 30 天 6 单)",
            cond("ALL", "dimension.order_frequency", "GTE", 30));
        seg(def, "complaint_watch", "投诉关注", "投诉率维度分不高于 75(投诉越多分越低, 75 约对应投诉率 5%)",
            cond("ALL", "dimension.complaint_rate", "LTE", 75));
        funnel(def, "order_conversion", "下单转化",
            steps("app.open", "打开应用", "merchant.view", "逛店铺", "order.paid", "支付订单"), 1);
        return def;
    }

    private static ScenarioTemplateDef tourism() {
        ScenarioTemplateDef def = base("tourism", "文旅景点", "文旅景点",
            "给游客会员打分: 常来、约了就来、来了会消费的分高。事件名请换成你景区的真实事件名。",
            false);
        dim(def, "visit_frequency", "游览频次", calc(score("linear", 0, 8, "higher"),
            metricCount("visit.checked_in"), "rolling", 365, "zero"), 1);
        dim(def, "booking_completion", "预约成行率", calc(score("linear", 0, 1, "higher"),
            metricRate("visit.checked_in", "visit.reserved"), "all", null, "neutral"), 2);
        dim(def, "package_conversion", "增值消费转化", calc(score("linear", 0, 1, "higher"),
            metricRate("order.paid", "visit.checked_in"), "all", null, "neutral"), 3);
        w(def, 0.4, 0.35, 0.25);
        seg(def, "revisit_crowd", "复游人群", "游览频次维度分不低于 25(约对应近一年 2 次)",
            cond("ALL", "dimension.visit_frequency", "GTE", 25));
        seg(def, "no_show_watch", "爽约关注", "预约成行率维度分低于 50(约对应成行率不足一半)",
            cond("ALL", "dimension.booking_completion", "LT", 50));
        funnel(def, "visit_conversion", "预约到园",
            steps("visit.reserved", "创建预约", "ticket.paid", "购票支付", "visit.checked_in", "检票入园"), 14);
        return def;
    }

    private static ScenarioTemplateDef community() {
        ScenarioTemplateDef def = base("community", "社区内容", "社区内容",
            "给社区用户打分: 常发帖、常互动、不违规的分高。事件名请换成你社区的真实事件名。",
            false);
        dim(def, "creation_activity", "创作活跃", calc(score("linear", 0, 10, "higher"),
            metricCount("content.published"), "rolling", 30, "zero"), 1);
        dim(def, "interaction_activity", "互动活跃", calc(score("linear", 0, 60, "higher"),
            metricCount("content.interacted"), "rolling", 30, "zero"), 2);
        dim(def, "violation_rate", "违规率", calc(score("linear", 0, 0.3, "lower"),
            metricRate("content.violated", "content.published"), "all", null, "neutral"), 3);
        w(def, 0.4, 0.35, 0.25);
        seg(def, "core_creators", "核心创作者", "创作活跃维度分不低于 30(约对应近 30 天 3 篇)",
            cond("ALL", "dimension.creation_activity", "GTE", 30));
        seg(def, "violation_watch", "违规关注", "违规率维度分不高于 67(违规越多分越低, 67 约对应违规率 10%)",
            cond("ALL", "dimension.violation_rate", "LTE", 67));
        funnel(def, "creation_conversion", "创作转化",
            steps("user.signup", "注册", "content.read", "阅读内容", "content.published", "发布内容"), 30);
        return def;
    }

    private static ScenarioTemplateDef saasTool() {
        ScenarioTemplateDef def = base("saas_tool", "SaaS 工具", "SaaS 工具",
            "给 SaaS 账户打分: 用得多、用得广、从试用转付费的分高。事件名请换成你产品的真实事件名。",
            false);
        dim(def, "usage_activity", "使用活跃", calc(score("linear", 0, 40, "higher"),
            metricCount("app.used"), "rolling", 30, "zero"), 1);
        dim(def, "feature_breadth", "功能覆盖", calc(score("linear", 0, 10, "higher"),
            metricCountDistinct("feature.used", "featureKey"), "rolling", 90, "neutral"), 2);
        dim(def, "paid_conversion", "付费转化", calc(score("linear", 0, 1, "higher"),
            metricRate("billing.paid", "billing.trial_started"), "all", null, "neutral"), 3);
        w(def, 0.4, 0.3, 0.3);
        seg(def, "sticky_accounts", "高粘性账户", "使用活跃维度分不低于 50(约对应近 30 天 20 次)",
            cond("ALL", "dimension.usage_activity", "GTE", 50));
        seg(def, "trial_stall", "试用停滞", "付费转化维度分低于 10(约对应转化不足 10%)",
            cond("ALL", "dimension.paid_conversion", "LT", 10));
        funnel(def, "trial_conversion", "试用转化",
            steps("user.signup", "注册", "feature.used", "使用功能", "billing.paid", "付费订阅"), 30);
        return def;
    }

    private static ScenarioTemplateDef mesOperator() {
        ScenarioTemplateDef def = base("mes_operator", "MES 操作工", "MES 操作工",
            "给车间操作工打分: 干得完、报警响应快、质检合格的分高。事件名请换成你产线的真实事件名。",
            false);
        dim(def, "job_completion", "任务完成率", calc(score("linear", 0, 1, "higher"),
            metricRate("job.finished", "job.assigned"), "all", null, "neutral"), 1);
        dim(def, "alarm_response", "报警响应", calc(score("linear", 0, 30, "lower"),
            metricAvgProp("responseMinutes", "alarm.cleared"), "all", null, "neutral"), 2);
        dim(def, "qc_pass_rate", "质检合格率", calc(score("linear", 0, 1, "higher"),
            metricRate("qc.passed", "qc.inspected"), "all", null, "neutral"), 3);
        w(def, 0.35, 0.3, 0.35);
        seg(def, "excellent_operators", "优秀操作工", "质检合格率维度分不低于 98(约对应合格率 98%)",
            cond("ALL", "dimension.qc_pass_rate", "GTE", 98));
        seg(def, "slow_response", "响应偏慢", "报警响应维度分低于 50(响应越慢分越低, 50 约对应 15 分钟)",
            cond("ALL", "dimension.alarm_response", "LT", 50));
        funnel(def, "shift_flow", "交接班链路",
            steps("shift.started", "上岗", "job.finished", "完工", "qc.passed", "质检合格"), 1);
        return def;
    }

    // ---- 构造小工具(保序 + 禁 null, 全部不可变约定由校验层兜底) ----

    private static ScenarioTemplateDef base(String code, String name, String scenario,
                                            String description, boolean recommended) {
        ScenarioTemplateDef def = new ScenarioTemplateDef();
        def.setCode(code);
        def.setName(name);
        def.setScenario(scenario);
        def.setDescription(description);
        def.setRecommended(recommended);
        return def;
    }

    private static void dim(ScenarioTemplateDef def, String code, String name,
                            Map<String, Object> calcConfig, int sortOrder) {
        ScenarioTemplateDef.DimensionDef d = new ScenarioTemplateDef.DimensionDef();
        d.setDimCode(code);
        d.setDimName(name);
        d.setCalcConfig(calcConfig);
        d.setSortOrder(sortOrder);
        def.getDimensions().add(d);
    }

    private static void w(ScenarioTemplateDef def, double... values) {
        List<ScenarioTemplateDef.DimensionDef> dims = def.getDimensions();
        for (int i = 0; i < values.length && i < dims.size(); i++) {
            def.getWeights().put(dims.get(i).getDimCode(), values[i]);
        }
    }

    private static void seg(ScenarioTemplateDef def, String code, String name, String description,
                            Map<String, Object> ruleConfig) {
        ScenarioTemplateDef.SegmentDef s = new ScenarioTemplateDef.SegmentDef();
        s.setSegmentCode(code);
        s.setSegmentName(name);
        s.setDescription(description);
        s.setRuleConfig(ruleConfig);
        def.getSegments().add(s);
    }

    private static void funnel(ScenarioTemplateDef def, String code, String name,
                               List<Map<String, Object>> steps, int windowDays) {
        ScenarioTemplateDef.FunnelDef f = new ScenarioTemplateDef.FunnelDef();
        f.setFunnelCode(code);
        f.setFunnelName(name);
        f.setSteps(steps);
        f.setWindowDays(windowDays);
        def.getFunnels().add(f);
    }

    private static List<Map<String, Object>> steps(String... pairs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("eventName", pairs[i]);
            step.put("name", pairs[i + 1]);
            out.add(step);
        }
        return out;
    }

    private static Map<String, Object> cond(String match, String field, String op, Object value) {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("field", field);
        condition.put("op", op);
        condition.put("value", value);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("match", match);
        rule.put("conditions", List.of(condition));
        return rule;
    }

    private static Map<String, Object> score(String type, double lo, double hi, String direction) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("lo", lo);
        out.put("hi", hi);
        out.put("direction", direction);
        return out;
    }

    private static Map<String, Object> bucketScore() {
        Map<String, Object> b1 = new LinkedHashMap<>();
        b1.put("lte", 0);
        b1.put("score", 100);
        Map<String, Object> b2 = new LinkedHashMap<>();
        b2.put("lte", 2);
        b2.put("score", 70);
        Map<String, Object> b3 = new LinkedHashMap<>();
        b3.put("lte", 5);
        b3.put("score", 40);
        Map<String, Object> b4 = new LinkedHashMap<>();
        b4.put("score", 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "bucket");
        out.put("buckets", List.of(b1, b2, b3, b4));
        return out;
    }

    private static Map<String, Object> metricRate(String numerator, String denominator) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operator", "RATE");
        out.put("numerator", target(numerator));
        out.put("denominator", target(denominator));
        return out;
    }

    private static Map<String, Object> metricCount(String eventType) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operator", "COUNT");
        out.put("target", target(eventType));
        return out;
    }

    private static Map<String, Object> metricCountDistinct(String eventType, String dedupKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operator", "COUNT_DISTINCT");
        out.put("target", target(eventType));
        out.put("dedupKey", dedupKey);
        return out;
    }

    private static Map<String, Object> metricAvgProp(String prop, String eventType) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operator", "AVG");
        out.put("prop", prop);
        out.put("target", target(eventType));
        return out;
    }

    private static Map<String, Object> target(String eventType) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventType", eventType);
        return out;
    }

    private static Map<String, Object> calc(Map<String, Object> score, Map<String, Object> metric,
                                            String windowType, Integer windowDays, String missing) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("type", windowType);
        if (windowDays != null) {
            window.put("days", windowDays);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("metric", metric);
        out.put("window", window);
        out.put("missing", missing);
        return out;
    }
}
