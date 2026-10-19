package com.smartdock.risk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import com.smartdock.risk.domain.DcsRiskEvent;
import com.smartdock.risk.domain.vo.DcsRiskEventVo;
import com.smartdock.risk.mapper.DcsRiskEventMapper;
import com.smartdock.risk.support.RiskUserEnricher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;

/**
 * 风险事件流水 Controller（从 DcsRiskController 拆出）。
 *
 * <p>职责：风险事件列表分页查询、单条详情查看。事件由系统自动采集，本接口不提供新增/编辑入口。
 *
 * <p>路由前缀 {@code /dcs/risk} 与拆分前保持一致，前端零改动。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/dcs/risk")
@RequiredArgsConstructor
public class DcsRiskEventController extends BaseController {

    /** 风险事件表读写 Mapper（事件流水分页/详情数据源） */
    private final DcsRiskEventMapper eventMapper;
    /** 风险数据用户名补全器（VO 填充 userName/nickName） */
    private final RiskUserEnricher userEnricher;

    /**
     * 分页查询风险事件流水。
     *
     * <p>支持多维度筛选：事件类型 / 用户 / IP / 时间区间 / 风险分下限。流水类页面最常用的是时间区间，
     * 所以 startTime/endTime 用毫秒时间戳（前端 el-date-picker datetimerange 直接传 Date.getTime()）。
     * 高分事件排查场景可传 minRiskScore 过滤 + orderByScore=true 按风险分降序（默认按时间降序）。</p>
     *
     * @param eventType    事件类型（如 LOGIN_FAILED），可选；为空则全部
     * @param userId       用户 ID，可选
     * @param ip           来源 IP（精确匹配），可选
     * @param startTime    起始时间戳（毫秒），可选；为空则不限下界
     * @param endTime      截止时间戳（毫秒），可选；为空则不限上界
     * @param minRiskScore 风险分下限（≥），可选；用于滤掉低分噪音（2026-08-23）
     * @param orderByScore true=按风险分降序优先（同分按时间降序），默认 false 按时间降序（2026-08-23）
     * @param pageQuery    分页参数
     * @return 事件列表分页数据，每行附带用户昵称
     */
    @SaCheckPermission(value = {"dcs:risk:event:list", "dcs:risk:list"}, mode = SaMode.OR)
    @GetMapping("/events")
    public TableDataInfo<DcsRiskEventVo> events(
        @RequestParam(required = false) String eventType,
        @RequestParam(required = false) Long userId,
        @RequestParam(required = false) String ip,
        @RequestParam(required = false) Long startTime,
        @RequestParam(required = false) Long endTime,
        @RequestParam(required = false) Integer minRiskScore,
        @RequestParam(required = false, defaultValue = "false") Boolean orderByScore,
        PageQuery pageQuery
    ) {
        LambdaQueryWrapper<DcsRiskEvent> qw = new LambdaQueryWrapper<>();
        if (eventType != null && !eventType.isBlank()) qw.eq(DcsRiskEvent::getEventType, eventType);
        if (userId != null) qw.eq(DcsRiskEvent::getUserId, userId);
        if (ip != null && !ip.isBlank()) qw.eq(DcsRiskEvent::getIp, ip);
        // 时间区间筛选：startTime/endTime 为毫秒时间戳，转 Date 后用 ge/le 比较 created_time
        if (startTime != null) qw.ge(DcsRiskEvent::getCreatedTime, new Date(startTime));
        if (endTime != null) qw.le(DcsRiskEvent::getCreatedTime, new Date(endTime));
        // 风险分下限：滤掉低分噪音，让高分事件浮上来
        if (minRiskScore != null) qw.ge(DcsRiskEvent::getRiskScore, minRiskScore);
        if (Boolean.TRUE.equals(orderByScore)) {
            qw.orderByDesc(DcsRiskEvent::getRiskScore).orderByDesc(DcsRiskEvent::getCreatedTime);
        } else {
            qw.orderByDesc(DcsRiskEvent::getCreatedTime);
        }
        var page = eventMapper.selectPage(pageQuery.build(), qw);
        // 转 VO 并补用户名
        List<DcsRiskEventVo> voRows = userEnricher.enrichEventVo(page.getRecords());
        return TableDataInfo.build(new Page<DcsRiskEventVo>(
            page.getCurrent(), page.getSize(), page.getTotal()).setRecords(voRows));
    }

    /**
     * 查询单条风险事件详情。
     *
     * <p>典型场景：事件流水表格操作列点击"查看详情"弹窗展示完整字段（detail/sourceTrack 等）。
     *
     * @param eventId 事件 ID
     * @return 单条事件 VO（带 userName/nickName）
     */
    @SaCheckPermission(value = {"dcs:risk:event:list", "dcs:risk:list"}, mode = SaMode.OR)
    @GetMapping("/events/{eventId}")
    public R<DcsRiskEventVo> eventDetail(@PathVariable Long eventId) {
        DcsRiskEvent entity = eventMapper.selectById(eventId);
        return R.ok(userEnricher.enrichEventVo(entity));
    }
}
