package org.dromara.system.controller.monitor;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.lock.annotation.Lock4j;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.CacheConstants;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.excel.utils.ExcelUtil;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.system.domain.bo.SysLogininforBo;
import org.dromara.system.domain.vo.SysLogininforVo;
import org.dromara.system.service.ISysLogininforService;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统访问记录
 *
 * @author Lion Li
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/monitor/logininfor")
public class SysLogininforController extends BaseController {

    private final ISysLogininforService logininforService;

    /**
     * 获取系统访问记录列表
     */
    @SaCheckPermission("monitor:logininfor:list")
    @GetMapping("/list")
    public TableDataInfo<SysLogininforVo> list(SysLogininforBo logininfor, PageQuery pageQuery) {
        return logininforService.selectPageLogininforList(logininfor, pageQuery);
    }

    /**
     * 导出系统访问记录列表
     */
    @Log(title = "登录日志", businessType = BusinessType.EXPORT)
    @SaCheckPermission("monitor:logininfor:export")
    @PostMapping("/export")
    public void export(SysLogininforBo logininfor, HttpServletResponse response) {
        List<SysLogininforVo> list = logininforService.selectLogininforList(logininfor);
        ExcelUtil.exportExcel(list, "登录日志", SysLogininforVo.class, response);
    }

    /**
     * 批量删除登录日志
     * @param infoIds 日志ids
     */
    @SaCheckPermission("monitor:logininfor:remove")
    @Log(title = "登录日志", businessType = BusinessType.DELETE)
    @DeleteMapping("/{infoIds}")
    public R<Void> remove(@PathVariable Long[] infoIds) {
        return toAjax(logininforService.deleteLogininforByIds(infoIds));
    }

    /**
     * 清理系统访问记录
     */
    @SaCheckPermission("monitor:logininfor:remove")
    @Log(title = "登录日志", businessType = BusinessType.CLEAN)
    @Lock4j
    @DeleteMapping("/clean")
    public R<Void> clean() {
        logininforService.cleanLogininfor();
        return R.ok();
    }

    @SaCheckPermission("monitor:logininfor:unlock")
    @Log(title = "账户解锁", businessType = BusinessType.OTHER)
    @RepeatSubmit()
    @GetMapping("/unlock/{userName}")
    public R<Void> unlock(@PathVariable("userName") String userName, @RequestParam("tenantId") String tenantId) {
        // 深挖审核 Q4（2026-09-27 拍板，DB 实证）：tenantId 由调用方任意指定，本环境两家租户的
        // "管理员"角色实持 unlock 权限——不拦则低权限租户管理员可清他租户账号的防爆破锁（IDOR）。
        // 非超管只允许解本单位；超管不受限
        if (!LoginHelper.isSuperAdmin() && !StringUtils.equals(tenantId, LoginHelper.getTenantId())) {
            return R.fail("无权解锁其他单位账号");
        }
        // 锁定 key 带租户前缀：跨租户同名账号不共账，解锁须按日志行所属租户拼 key。
        // ⚠️ 多租户 Redis NameMapper(TenantKeyPrefixHandler) 按【当前操作者】租户上下文加前缀，
        // 超管在 000000 代理解锁他租户用户时前缀会错位——TenantHelper.dynamic 钉住目标租户（glm53 P1-1）。
        // dynamic 的 finally 会 clearDynamic 连带删掉超管「租户切换」持久态（glm53 delta P2）——
        // 先存后还，避免解锁动作把超管静默弹回默认视角。旧格式 key（改前写入）与
        // V2 计数器段（Q7 换段：INCR 原子化）一并清做升级过渡
        String prevDynamic = TenantHelper.getDynamic();
        try {
            TenantHelper.dynamic(tenantId, () -> {
                RedisUtils.deleteObject(CacheConstants.PWD_ERR_CNT_V2_KEY + tenantId + ":" + userName);
                RedisUtils.deleteObject(CacheConstants.PWD_ERR_CNT_KEY + tenantId + ":" + userName);
                RedisUtils.deleteObject(CacheConstants.PWD_ERR_CNT_KEY + userName);
            });
        } finally {
            if (StringUtils.isNotBlank(prevDynamic)) {
                TenantHelper.setDynamic(prevDynamic, true);
            }
        }
        return R.ok();
    }

}
