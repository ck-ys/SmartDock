package com.smartdock.risk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import com.smartdock.risk.domain.bo.DcsSensitiveWordBo;
import com.smartdock.risk.domain.bo.SensitiveWordBatchCommand;
import com.smartdock.risk.domain.bo.SensitiveWordTestCommand;
import com.smartdock.risk.domain.vo.DcsSensitiveWordVo;
import com.smartdock.risk.domain.vo.SensitiveWordInspectionVo;
import com.smartdock.risk.domain.vo.SensitiveWordStatsVo;
import com.smartdock.risk.service.IDcsSensitiveWordService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 敏感词配置管理 Controller(后台 CRUD 接口, 供风控管理员维护词库)。
 *
 * <p>路径前缀 {@code /dcs/sensitive-word}, 权限串 {@code dcs:sensitive-word:*}。
 * 敏感词本身由 {@link com.smartdock.risk.service.DcsSensitiveWordFilterService} 在消息发送时
 * 实时过滤, 本 Controller 只负责词库的增删改查, 不参与过滤逻辑。</p>
 *
 * @author smartdock
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dcs/sensitive-word")
public class DcsSensitiveWordController extends BaseController {

    private final IDcsSensitiveWordService sensitiveWordService;

    /**
     * 分页查询
     *
     * @param bo 敏感词查询条件业务对象
     * @param pageQuery 分页参数
     * @return 分页的敏感词列表
     */
    @SaCheckPermission("dcs:sensitive-word:list")
    @GetMapping("/list")
    public TableDataInfo<DcsSensitiveWordVo> list(DcsSensitiveWordBo bo, PageQuery pageQuery) {
        return sensitiveWordService.queryPageList(bo, pageQuery);
    }

    /**
     * 详情
     *
     * @param wordId 敏感词ID
     * @return 敏感词业务数据
     */
    @SaCheckPermission("dcs:sensitive-word:query")
    @GetMapping("/{wordId}")
    public R<DcsSensitiveWordVo> getInfo(@PathVariable Long wordId) {
        return R.ok(sensitiveWordService.queryById(wordId));
    }

    /**
     * 新增
     *
     * @param bo 敏感词业务对象
     * @return 新建记录的ID
     */
    @SaCheckPermission("dcs:sensitive-word:add")
    @Log(title = "DCS敏感词", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody DcsSensitiveWordBo bo) {
        return R.ok(sensitiveWordService.insertByBo(bo));
    }

    /**
     * 修改
     *
     * @param bo 敏感词业务对象
     * @return 操作结果
     */
    @SaCheckPermission("dcs:sensitive-word:edit")
    @Log(title = "DCS敏感词", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/edit")
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody DcsSensitiveWordBo bo) {
        return toAjax(sensitiveWordService.updateByBo(bo));
    }

    /**
     * 删除
     *
     * @param wordIds 敏感词ID数组
     * @return 操作结果
     */
    @SaCheckPermission("dcs:sensitive-word:remove")
    @Log(title = "DCS敏感词", businessType = BusinessType.DELETE)
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/{wordIds}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] wordIds) {
        return toAjax(sensitiveWordService.deleteByIds(List.of(wordIds)));
    }

    /**
     * 全量口径统计(2026-08-23 词库管理页统计卡): 词条总数/启用/各动作数
     *
     * @return 敏感词全量口径统计数据
     */
    @SaCheckPermission("dcs:sensitive-word:list")
    @GetMapping("/stats")
    public R<SensitiveWordStatsVo> stats() {
        return R.ok(sensitiveWordService.stats());
    }

    /**
     * 批量导入(2026-08-23): 单请求单事务, 每词复用新增校验, 重复词跳过
     *
     * @param command 敏感词批量导入指令
     * @return 批量导入受理结果说明
     */
    @SaCheckPermission("dcs:sensitive-word:add")
    @Log(title = "敏感词批量导入", businessType = BusinessType.IMPORT)
    @RepeatSubmit
    @PostMapping("/batch")
    public R<String> batchImport(@Validated @RequestBody SensitiveWordBatchCommand command) {
        return R.ok(sensitiveWordService.batchImport(command));
    }

    /**
     * 命中自测(2026-08-24, 词库管理页"命中测试"): 用真实过滤规则只读匹配测试文本,
     * 返回命中词/动作明细; 不产生风险事件不记分。查询类操作挂 list 权限。
     *
     * @param command 命中自测指令（含测试文本）
     * @return 命中检测结果明细
     */
    @SaCheckPermission("dcs:sensitive-word:list")
    @PostMapping("/test")
    public R<SensitiveWordInspectionVo> testMatch(@Validated @RequestBody SensitiveWordTestCommand command) {
        return R.ok(sensitiveWordService.testMatch(command.text(), command.scene()));
    }
}
