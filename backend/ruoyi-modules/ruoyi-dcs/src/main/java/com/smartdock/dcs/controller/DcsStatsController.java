package com.smartdock.dcs.controller;

import org.dromara.common.satoken.utils.LoginHelper;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.dcs.service.DcsStatsService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 双视角概览统计(端点组 10 后半; 验收"镜像统计非零")。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/dcs/stats")
@RequiredArgsConstructor
public class DcsStatsController extends BaseController {

    private final DcsStatsService statsService;

    /** 权限锚=成员级(dcs:submission:submit): 双视角统计是成员首页主数据源,
     *  挂 dcs:task:list 会把纯成员挡在门外(E2E 实锤 403); 发布人/管理员天然持高级权限 */
    @SaCheckPermission("dcs:submission:submit")
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        return R.ok(statsService.overview(LoginHelper.getUserId()));
    }
}
