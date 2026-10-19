package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.engine.ProfileQueryService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 画像查询接口(只读): 列表/总览/排行/详情。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileQueryController {

    private final ProfileQueryService queryService;

    /**
     * 主体分页(带最新快照与筛选/排序)。
     */
    @SaCheckPermission("profile:score:query")
    @GetMapping("/subject/page")
    public TableDataInfo<ProfileSubjectRow> subjectPage(ProfileSubjectQuery query, PageQuery pageQuery) {
        return queryService.subjectPage(query, pageQuery);
    }

    /**
     * 画像总览: 主体数/已算分/平均分/风险分层。
     */
    @SaCheckPermission("profile:score:query")
    @GetMapping("/overview")
    public R<ProfileOverview> overview(@RequestParam String source) {
        return R.ok(queryService.overview(source));
    }

    /**
     * 维度排行: dimension 为空按总分。
     */
    @SaCheckPermission("profile:score:query")
    @GetMapping("/ranking")
    public R<List<ProfileRankingRow>> ranking(@RequestParam String source,
                                              @RequestParam(required = false) String subjectType,
                                              @RequestParam(required = false) String dimension,
                                              @RequestParam(defaultValue = "10") int limit) {
        return R.ok(queryService.ranking(source, subjectType, dimension, limit));
    }

    /**
     * 单主体画像: 主体 + 最新快照 + 维度/证据 + 排名/风险 + 趋势。
     */
    @SaCheckPermission("profile:score:query")
    @GetMapping("/score/{source}/{subjectId}")
    public R<Map<String, Object>> score(@PathVariable String source,
                                        @PathVariable String subjectId,
                                        @RequestParam(defaultValue = "user") String subjectType,
                                        @RequestParam(defaultValue = "5") int tail) {
        return R.ok(queryService.detail(source, subjectType, subjectId, tail));
    }
}
