package com.smartdock.service.support;

import lombok.RequiredArgsConstructor;
import com.smartdock.mapper.AiUsageAnalysisMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 用量分析服务（AI 缺口批 Q5/Q6，拍板全A，2026-10-14）。
 *
 * <p>「用量分析」页签数据源：近 30 天消耗趋势 / 模型占比 / Top10 用户（含部门）/
 * 部门占比 / 汇总卡（成功率·平均耗时）。日期轴由本层补零——库里没调用的日子也要
 * 出现在趋势图上（断档轴会误导"那天没图"和"那天没数据"）。非法/缺省日期回退默认
 * 近 30 天，不抛错（管理台筛选手滑不炸页面）。</p>
 *
 * @author smartdock
 */
@Service
@RequiredArgsConstructor
public class AiUsageAnalysisService {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final AiUsageAnalysisMapper analysisMapper;

    /** 解析后的分析窗口（begin 当日 00:00 起，endExcl 为 end 次日 00:00 半开上界） */
    record Range(LocalDate begin, LocalDate end, Date beginAt, Date endExclAt) {
    }

    /** 日期入参解析：非法/缺失回退默认近 30 天；begin&gt;end 时对调（容错不炸图） */
    static Range parseRange(String beginDate, String endDate) {
        LocalDate begin = parseDay(beginDate);
        LocalDate end = parseDay(endDate);
        LocalDate today = LocalDate.now(ZONE);
        if (end == null) {
            end = today;
        }
        if (begin == null) {
            begin = end.minusDays(29);
        }
        if (begin.isAfter(end)) {
            LocalDate t = begin;
            begin = end;
            end = t;
        }
        return new Range(begin, end,
            Date.from(begin.atStartOfDay(ZONE).toInstant()),
            Date.from(end.plusDays(1).atStartOfDay(ZONE).toInstant()));
    }

    private static LocalDate parseDay(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim().substring(0, Math.min(10, s.trim().length())));
        } catch (Exception e) {
            return null;
        }
    }

    /** 分析载荷：trend(补零)/modelShare/topUsers/deptShare/summary */
    public Map<String, Object> analysis(String tenantId, String beginDate, String endDate) {
        Range r = parseRange(beginDate, endDate);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("beginDate", r.begin().toString());
        out.put("endDate", r.end().toString());
        out.put("trend", trendZeroFilled(tenantId, r));
        out.put("modelShare", analysisMapper.modelShare(tenantId, r.beginAt(), r.endExclAt()));
        out.put("topUsers", analysisMapper.topUsers(tenantId, r.beginAt(), r.endExclAt()));
        out.put("deptShare", analysisMapper.deptShare(tenantId, r.beginAt(), r.endExclAt()));
        Map<String, Object> row = analysisMapper.summaryRow(tenantId, r.beginAt(), r.endExclAt());
        Map<String, Object> summary = new LinkedHashMap<>();
        long calls = row == null ? 0 : toLong(row.get("calls"));
        long successCalls = row == null ? 0 : toLong(row.get("success_calls"));
        summary.put("calls", calls);
        summary.put("successCalls", successCalls);
        summary.put("successRate", calls == 0 ? 0
            : BigDecimal.valueOf(successCalls * 100.0 / calls).setScale(1, RoundingMode.HALF_UP));
        summary.put("avgLatencyMs", row == null ? 0
            : BigDecimal.valueOf(toLong(row.get("avg_latency_ms"))).longValue());
        summary.put("totalCost", row == null ? BigDecimal.ZERO
            : new BigDecimal(String.valueOf(row.get("cost"))).setScale(4, RoundingMode.HALF_UP));
        out.put("summary", summary);
        return out;
    }

    /** 分析报表导出行（按日×模型聚合），复用同一窗口解析 */
    public List<Map<String, Object>> dailyModelRows(String tenantId, String beginDate, String endDate) {
        Range r = parseRange(beginDate, endDate);
        return analysisMapper.dailyModelRows(tenantId, r.beginAt(), r.endExclAt());
    }

    /** 趋势补零：窗口内每个日历日一行（无调用=calls 0/cost 0） */
    private List<Map<String, Object>> trendZeroFilled(String tenantId, Range r) {
        Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
        for (Map<String, Object> row : analysisMapper.trend(tenantId, r.beginAt(), r.endExclAt())) {
            byDay.put(String.valueOf(row.get("day")), row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalDate d = r.begin(); !d.isAfter(r.end()); d = d.plusDays(1)) {
            String key = d.toString();
            Map<String, Object> row = byDay.get(key);
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("day", key);
            day.put("calls", row == null ? 0 : toLong(row.get("calls")));
            day.put("cost", row == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : new BigDecimal(String.valueOf(row.get("cost"))).setScale(4, RoundingMode.HALF_UP));
            out.add(day);
        }
        return out;
    }

    /** PG 数值列类型随驱动漂移（Long/BigDecimal/Integer）——统一转 long */
    static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return new BigDecimal(String.valueOf(v)).longValue();
        } catch (Exception e) {
            return 0L;
        }
    }
}
