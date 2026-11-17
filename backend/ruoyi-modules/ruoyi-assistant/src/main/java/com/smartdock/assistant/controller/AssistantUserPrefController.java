package com.smartdock.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.smartdock.assistant.domain.bo.AssistantUserPrefBo;
import com.smartdock.assistant.domain.vo.AssistantUserPrefVo;
import com.smartdock.assistant.service.AssistantUserPrefService;

/**
 * 助手常规偏好接口（批C Q8 案1，2026-11-15）。
 *
 * <p>只操作本人的数据（租户+用户双谓词在 Mapper 里），所以权限沿用
 * {@code dcs:assistant:use}——不新增权限串、不新增菜单。</p>
 */
@Tag(name = "助手常规偏好", description = "本人七项常规设置（换设备仍在）")
@RestController
@RequestMapping("/dcs/assistant/user-pref")
@RequiredArgsConstructor
public class AssistantUserPrefController {

    private final AssistantUserPrefService prefService;

    /** 读本人偏好；没存过返回默认值 + saved=false */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping
    public R<AssistantUserPrefVo> get() {
        return R.ok(prefService.load(LoginHelper.getTenantId(), LoginHelper.getUserId()));
    }

    /** 全量覆盖保存（七项一起交；不传的按默认值） */
    @SaCheckPermission("dcs:assistant:use")
    @Log(title = "助手-常规偏好", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<Void> save(@RequestBody AssistantUserPrefBo bo) {
        // 枚举外的值：抛业务异常走全局处理（人话回前端，操作日志记 FAIL——不再吃掉异常记成 SUCCESS）
        prefService.save(LoginHelper.getTenantId(), LoginHelper.getUserId(), bo);
        return R.ok();
    }
}
