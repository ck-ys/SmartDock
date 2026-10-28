package com.smartdock.profile.monitor;

import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.engine.ProfileEngineConfigService;
import com.smartdock.profile.event.ProfileEventBo;
import com.smartdock.profile.event.ProfileSources;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.constant.Constants;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.log.event.LogininforEvent;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

/**
 * 自监控监听器(单源重构 C 片·题1): 登录成功 → 静默画像事件。
 *
 * <p>挂点取舍: LogininforEvent 的成功态只由 sa-token doLogin 回发(UserActionListener)——
 * 登录失败/锁定/登出走 SysLoginService 且状态值不同, 天然不会双发。监听器在登录请求线程
 * 同步执行, 保持轻量与异常吞没(自监控失败绝不阻断登录); 页面浏览一律不记(白名单见
 * {@link SelfMonitorEvents})。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileSelfMonitorListener {

    private final ProfileEventPublisher eventPublisher;
    private final ProfileEngineConfigService configService;
    private final ProfileSubjectMapper subjectMapper;

    @EventListener
    public void onLogin(LogininforEvent event) {
        try {
            if (event == null || !Constants.LOGIN_SUCCESS.equals(event.getStatus())) {
                return;
            }
            if (StringUtils.isBlank(event.getTenantId()) || StringUtils.isBlank(event.getUsername())) {
                return;
            }
            String userName = event.getUsername().trim();
            TenantHelper.dynamic(event.getTenantId(), () -> {
                // 总开关停用后不再新增记录(已有数据保留)
                String enabled = configService.getOrCreate(ProfileSources.UNIFIED).getSelfMonitor();
                if (!"1".equals(enabled)) {
                    return;
                }
                // 登录名 → user_id: 画像主视图按 user_id::text 联表, 存登录名会变成查无此人
                Map<String, Object> user = subjectMapper.selectUserIdByName(event.getTenantId(), userName);
                if (user == null || user.get("userId") == null) {
                    return;
                }
                ProfileEventBo bo = new ProfileEventBo();
                bo.setEventName(SelfMonitorEvents.LOGIN);
                bo.setEventUid("selfmon.login:" + user.get("userId") + ":" + System.currentTimeMillis());
                bo.setSubjectType("user");
                bo.setSubjectId(String.valueOf(user.get("userId")));
                bo.setSubjectName((String) user.get("nickName"));
                bo.setOccurredTime(new Date());
                eventPublisher.publishQuiet(bo);
            });
        } catch (Exception e) {
            log.warn("[Profile] self-monitor login event failed user={} : {}", event.getUsername(), e.getMessage());
        }
    }
}
