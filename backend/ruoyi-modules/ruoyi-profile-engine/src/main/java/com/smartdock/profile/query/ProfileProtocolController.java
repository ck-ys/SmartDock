package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.protocol.ProfileProtocol;
import com.smartdock.profile.protocol.ProfileProtocolService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画像接入协议接口: 校验、发布、当前版本、历史、导出。
 *
 * <p>协议是跨业务接入的契约(订单/供应链/工程/商城/租赁/用户均可声明自己的
 * subjectTypes/events/dimensions/metrics/rules/scoreModels/segments), 发布后不可变。</p>
 *
 * <p>单源口径(M1, B 片三审回修): 同 schema 控制器——路径 source 接收但忽略,
 * 一律按内置源读写, 防止在旧源名下发布协议成为永不生效的假绿灯。</p>
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile/protocol")
@RequiredArgsConstructor
public class ProfileProtocolController {

    private final ProfileProtocolService protocolService;

    @SaCheckPermission("profile:protocol:query")
    @GetMapping("/{source}")
    public R<ProfileProtocol> current(@PathVariable String source) {
        return R.ok(protocolService.current(com.smartdock.profile.event.ProfileSources.UNIFIED));
    }

    @SaCheckPermission("profile:protocol:query")
    @GetMapping("/{source}/history")
    public R<List<ProfileProtocol>> history(@PathVariable String source) {
        return R.ok(protocolService.history(com.smartdock.profile.event.ProfileSources.UNIFIED));
    }

    @SaCheckPermission("profile:protocol:edit")
    @PostMapping("/{source}/validate")
    public R<Map<String, Object>> validate(@RequestBody Map<String, Object> manifest) {
        List<String> errors = protocolService.validate(manifest);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", errors.isEmpty());
        out.put("errors", errors);
        return R.ok(out);
    }

    @SaCheckPermission("profile:protocol:edit")
    @PostMapping("/{source}/publish")
    public R<ProfileProtocol> publish(@PathVariable String source,
                                      @RequestParam(required = false) String version,
                                      @RequestBody Map<String, Object> manifest) {
        return R.ok(protocolService.publish(com.smartdock.profile.event.ProfileSources.UNIFIED, version, manifest, currentUserId()));
    }

    /**
     * 导出当前生效 manifest(前端“复制接入协议”/SDK 生成用)。
     */
    @SaCheckPermission("profile:protocol:query")
    @GetMapping("/{source}/export")
    public R<Map<String, Object>> export(@PathVariable String source) {
        return R.ok(JsonUtils.parseMap(protocolService.export(com.smartdock.profile.event.ProfileSources.UNIFIED)));
    }

    private Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
