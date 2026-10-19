package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.profile.domain.ProfileScoreDimension;
import com.smartdock.profile.mapper.ProfileScoreDimensionMapper;
import com.smartdock.profile.engine.ProfileScoreEngine;
import com.smartdock.profile.engine.ProfileScoreSnapshot;
import com.smartdock.profile.event.ProfileSubject;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 画像查询接口(只读)。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileQueryController {

    private final ProfileSubjectMapper subjectMapper;
    private final ProfileScoreDimensionMapper dimensionMapper;
    private final ProfileScoreEngine scoreEngine;

    /**
     * 主体分页(按 source 过滤)。
     */
    @SaCheckPermission("profile:score:query")
    @GetMapping("/subject/page")
    public TableDataInfo<ProfileSubject> subjectPage(String source, PageQuery pageQuery) {
        Page<ProfileSubject> page = subjectMapper.selectPage(pageQuery.build(),
            new LambdaQueryWrapper<ProfileSubject>()
                .eq(source != null && !source.isBlank(), ProfileSubject::getSource, source)
                .orderByDesc(ProfileSubject::getLastEventTime));
        return TableDataInfo.build(page);
    }

    /**
     * 单主体画像: 最新快照 + 维度明细 + 最近 N 次快照链尾。
     */
    @SaCheckPermission("profile:score:query")
    @GetMapping("/score/{source}/{subjectId}")
    public R<Map<String, Object>> score(@PathVariable String source,
                                        @PathVariable String subjectId,
                                        @RequestParam(defaultValue = "user") String subjectType,
                                        @RequestParam(defaultValue = "5") int tail) {
        Map<String, Object> out = new LinkedHashMap<>();
        ProfileScoreSnapshot latest = scoreEngine.latestSnapshot(source, subjectType, subjectId);
        out.put("latest", latest);
        out.put("history", scoreEngine.snapshotTail(source, subjectType, subjectId, tail));
        return R.ok(out);
    }

    /**
     * 维度配置列表(按 source 过滤)。
     */
    @SaCheckPermission("profile:score:query")
    @GetMapping("/dimension/list")
    public R<java.util.List<ProfileScoreDimension>> dimensionList(String source) {
        return R.ok(dimensionMapper.selectList(new LambdaQueryWrapper<ProfileScoreDimension>()
            .eq(source != null && !source.isBlank(), ProfileScoreDimension::getSource, source)
            .orderByAsc(ProfileScoreDimension::getSortOrder)));
    }
}
