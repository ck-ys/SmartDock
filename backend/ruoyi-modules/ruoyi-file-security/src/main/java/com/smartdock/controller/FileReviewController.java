package com.smartdock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import com.smartdock.domain.bo.FileReviewConfigBo;
import com.smartdock.domain.bo.FileReviewDecisionBo;
import com.smartdock.domain.bo.FileReviewQueryBo;
import com.smartdock.domain.vo.FileReviewConfigVo;
import com.smartdock.domain.vo.FileReviewVo;
import com.smartdock.domain.vo.FileGuardLogVo;
import com.smartdock.domain.vo.GuardStatsVo;
import com.smartdock.service.IFileReviewConfigService;
import com.smartdock.service.IFileReviewService;
import com.smartdock.service.IFileGuardLogService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文件审核中心 控制层（12b 文件审核中心 P1）
 *
 * <p>菜单 9007「文件审核」（任务中心下，与消息中心平级）；
 * 按钮权限 F 9101-9104 与种子 SQL 一一对应。</p>
 *
 * @author smartdock
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/fileReview")
public class FileReviewController extends BaseController {

    private final IFileReviewService reviewService;
    private final IFileReviewConfigService configService;
    private final IFileGuardLogService guardLogService;

    /**
     * 审核单分页列表（待审/已审页签共用，status 必传）
     */
    @SaCheckPermission("smartdock:fileReview:list")
    @GetMapping("/list")
    public TableDataInfo<FileReviewVo> list(FileReviewQueryBo bo, PageQuery pageQuery) {
        return reviewService.queryList(bo, pageQuery);
    }

    /**
     * 待审单数（侧边栏徽标 / 页签计数 / 设置页警告共用数据源）
     */
    @SaCheckPermission("smartdock:fileReview:list")
    @GetMapping("/pendingCount")
    public R<Long> pendingCount() {
        return R.ok(reviewService.pendingCount());
    }

    /**
     * 安全检测近 7 天命中概览（设置弹窗顶部概览条，Q4=A 改版）
     */
    @SaCheckPermission("smartdock:fileReview:list")
    @GetMapping("/guard/stats")
    public R<GuardStatsVo> guardStats() {
        return R.ok(guardLogService.stats7d());
    }

    /**
     * 安全检测命中记录分页（「检测记录」页签，时间倒序，Q4=A 改版）
     */
    @SaCheckPermission("smartdock:fileReview:list")
    @GetMapping("/guard/logs")
    public TableDataInfo<FileGuardLogVo> guardLogs(PageQuery pageQuery) {
        return guardLogService.page(pageQuery);
    }

    /**
     * 审核单详情
     *
     * @param reviewId 审核单ID
     */
    @SaCheckPermission("smartdock:fileReview:query")
    @GetMapping("/{reviewId}")
    public R<FileReviewVo> getInfo(@PathVariable Long reviewId) {
        return R.ok(reviewService.queryById(reviewId));
    }

    /**
     * 批量通过（Q5-A 一期：计数确认；单条冲突不影响整批，结果分桶返回）
     *
     * @param reviewIds 审核单ID列表
     */
    @SaCheckPermission("smartdock:fileReview:approve")
    @Log(title = "文件审核-通过", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/approve")
    public R<IFileReviewService.ApproveResult> approve(@NotEmpty(message = "请选择要通过的文件")
                                                          @RequestBody List<Long> reviewIds) {
        return R.ok(reviewService.approve(reviewIds));
    }

    /**
     * 驳回单条（理由必填，随通知发给上传人）
     */
    @SaCheckPermission("smartdock:fileReview:reject")
    @Log(title = "文件审核-驳回", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/reject")
    public R<Void> reject(@Valid @RequestBody FileReviewDecisionBo bo) {
        reviewService.reject(bo.getReviewId(), bo.getReason());
        return R.ok();
    }

    /**
     * 配置读取（设置页：总开关/类别 + 待审数/审核员数警告数据）
     */
    @SaCheckPermission("smartdock:fileReview:config")
    @GetMapping("/config")
    public R<FileReviewConfigVo> getConfig() {
        return R.ok(configService.getConfig());
    }

    /**
     * 配置保存（幂等 upsert；关闭只影响新上传，存量待审单保留可审，Q4-A）
     */
    @SaCheckPermission("smartdock:fileReview:config")
    @Log(title = "文件审核-设置", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/config")
    public R<Void> saveConfig(@Valid @RequestBody FileReviewConfigBo bo) {
        configService.saveConfig(bo);
        return R.ok();
    }
}
