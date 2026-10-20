package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.domain.ProfileSegment;
import com.smartdock.profile.domain.ProfileSegmentRun;
import com.smartdock.profile.domain.ProfileReachBatch;
import com.smartdock.profile.domain.ProfileReachItem;
import com.smartdock.profile.engine.ProfileReachService;
import com.smartdock.profile.engine.ProfileSegmentService;
import com.smartdock.profile.query.ProfileReachSendBo;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 标签分群接口。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile/segment")
@RequiredArgsConstructor
public class ProfileSegmentController {

    private final ProfileSegmentService segmentService;
    private final ProfileReachService reachService;

    @SaCheckPermission("profile:segment:query")
    @GetMapping("/list")
    public R<List<ProfileSegment>> list(@RequestParam String source) {
        return R.ok(segmentService.list(source));
    }

    @SaCheckPermission("profile:segment:query")
    @GetMapping("/{segmentId}")
    public R<ProfileSegment> get(@PathVariable Long segmentId) {
        return R.ok(segmentService.get(segmentId));
    }

    @SaCheckPermission("profile:segment:edit")
    @Log(title = "画像分群", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<ProfileSegment> create(@RequestBody ProfileSegmentBo bo) {
        return R.ok(segmentService.create(bo));
    }

    @SaCheckPermission("profile:segment:edit")
    @Log(title = "画像分群", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/edit")
    public R<ProfileSegment> edit(@RequestBody ProfileSegmentBo bo) {
        return R.ok(segmentService.update(bo));
    }

    @SaCheckPermission("profile:segment:edit")
    @Log(title = "画像分群", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @PostMapping("/{segmentId}/delete")
    public R<Boolean> delete(@PathVariable Long segmentId) {
        return R.ok(segmentService.remove(segmentId));
    }

    @SaCheckPermission("profile:segment:query")
    @PostMapping("/preview")
    public R<Map<String, Object>> preview(@RequestBody ProfileSegmentBo bo) {
        return R.ok(segmentService.preview(bo));
    }

    @SaCheckPermission("profile:segment:edit")
    @Log(title = "画像分群-评估", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{segmentId}/evaluate")
    public R<ProfileSegmentRun> evaluate(@PathVariable Long segmentId) {
        return R.ok(segmentService.evaluate(segmentId));
    }

    @SaCheckPermission("profile:segment:edit")
    @Log(title = "画像分群-导入", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{segmentId}/import")
    public R<Map<String, Object>> importMembers(@PathVariable Long segmentId,
                                                @RequestBody ProfileSegmentImportBo bo) {
        return R.ok(segmentService.importMembers(segmentId, bo));
    }

    @SaCheckPermission("profile:segment:query")
    @GetMapping("/{segmentId}/members")
    public TableDataInfo<ProfileSegmentMemberRow> members(@PathVariable Long segmentId,
                                                          @RequestParam(required = false) String keyword,
                                                          PageQuery pageQuery) {
        return TableDataInfo.build(segmentService.memberPage(segmentId,
            pageQuery.getPageNum() == null ? 1 : pageQuery.getPageNum(),
            pageQuery.getPageSize() == null ? 20 : pageQuery.getPageSize(),
            keyword));
    }

    @SaCheckPermission("profile:segment:query")
    @GetMapping("/{segmentId}/runs")
    public R<List<ProfileSegmentRun>> runs(@PathVariable Long segmentId) {
        return R.ok(segmentService.runs(segmentId));
    }

    /** 触达预览: 当前成员中站内通道可覆盖的收件人数(批9 Q3-A)。 */
    @SaCheckPermission("profile:reach:query")
    @GetMapping("/{segmentId}/reach/preview")
    public R<Map<String, Object>> reachPreview(@PathVariable Long segmentId) {
        return R.ok(reachService.preview(segmentId));
    }

    /** 发送触达: 冻结收件人快照 → 逐人投递(幂等键防重发)。 */
    @SaCheckPermission("profile:reach:edit")
    @Log(title = "画像分群触达", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/{segmentId}/reach/send")
    public R<ProfileReachBatch> reachSend(@PathVariable Long segmentId,
                                          @RequestBody ProfileReachSendBo bo) {
        return R.ok(reachService.send(segmentId, bo));
    }

    /** 失败一键重试: 只重发 FAILED 明细。 */
    @SaCheckPermission("profile:reach:edit")
    @Log(title = "画像分群触达重试", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/reach/{reachId}/retry")
    public R<ProfileReachBatch> reachRetry(@PathVariable Long reachId) {
        return R.ok(reachService.retry(reachId));
    }

    /** 触达/导出记录(审计列表)。 */
    @SaCheckPermission("profile:reach:query")
    @GetMapping("/{segmentId}/reach/records")
    public TableDataInfo<ProfileReachBatch> reachRecords(@PathVariable Long segmentId,
                                                         @RequestParam(required = false) String channel,
                                                         @RequestParam(required = false, defaultValue = "1") Integer pageNum,
                                                         @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return TableDataInfo.build(reachService.records(segmentId, channel, pageNum, pageSize));
    }

    /** 批次逐人明细(收件人快照/成败)。 */
    @SaCheckPermission("profile:reach:query")
    @GetMapping("/reach/{reachId}/items")
    public R<List<ProfileReachItem>> reachItems(@PathVariable Long reachId,
                                                @RequestParam(required = false, defaultValue = "200") Integer limit) {
        return R.ok(reachService.items(reachId, limit));
    }

    /** 成员导出 CSV: 默认脱敏; 明文导出需编辑权限(审计行 channel=export)。 */
    @SaCheckPermission("profile:reach:edit")
    @Log(title = "画像分群导出", businessType = BusinessType.EXPORT)
    @RepeatSubmit
    @PostMapping("/{segmentId}/export")
    public void export(@PathVariable Long segmentId,
                       @RequestParam(required = false, defaultValue = "true") boolean masked,
                       HttpServletResponse response) throws java.io.IOException {
        byte[] csv = reachService.export(segmentId, masked);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
            "attachment; filename=segment-" + segmentId + "-members.csv");
        response.getOutputStream().write(csv);
        response.getOutputStream().flush();
    }
}
