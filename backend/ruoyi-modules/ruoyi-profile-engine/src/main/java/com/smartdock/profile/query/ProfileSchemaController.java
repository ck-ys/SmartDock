package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.protocol.ProfileSchemaBo;
import com.smartdock.profile.protocol.ProfileSchemaRegistry;
import com.smartdock.profile.protocol.ProfileSchemaService;
import com.smartdock.profile.protocol.ProfileSchemaValidateBo;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件 schema 注册与校验接口。
 *
 * <p>schema 是可选的强约束: 注册后事件在写入前按 required/properties 校验;
 * 未注册的 source/event 仍按宽松模式放行, 保证旧接入零破坏。</p>
 *
 * <p>单源口径(M1, B 片三审回修): 事件校验固定查内置源——注册/校验/列表若还按
 * 路径 source 落库, 非 dcs 源注册成功却永不生效(假绿灯); 路径参数接收但忽略。</p>
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile/schema")
@RequiredArgsConstructor
public class ProfileSchemaController {

    private final ProfileSchemaService schemaService;

    @SaCheckPermission("profile:schema:query")
    @GetMapping("/{source}")
    public R<List<ProfileSchemaRegistry>> list(@PathVariable String source) {
        return R.ok(schemaService.list(com.smartdock.profile.event.ProfileSources.UNIFIED));
    }

    @SaCheckPermission("profile:schema:edit")
    @PostMapping("/{source}")
    public R<ProfileSchemaRegistry> register(@PathVariable String source,
                                             @RequestBody ProfileSchemaBo bo) {
        return R.ok(schemaService.register(com.smartdock.profile.event.ProfileSources.UNIFIED, bo, currentUserId()));
    }

    @SaCheckPermission("profile:schema:query")
    @PostMapping("/{source}/validate")
    public R<Map<String, Object>> validate(@PathVariable String source,
                                           @RequestBody ProfileSchemaValidateBo bo) {
        List<String> errors = schemaService.validate(currentTenant(),
            com.smartdock.profile.event.ProfileSources.UNIFIED, bo.getEventName(),
            bo.getSchemaVersion(), bo.getProps(), bo.getContext(), bo.getTraits());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", errors.isEmpty());
        out.put("errors", errors);
        return R.ok(out);
    }

    private Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentTenant() {
        try {
            String t = TenantHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        return com.smartdock.profile.engine.ProfileTenantContext.require();
    }
}
