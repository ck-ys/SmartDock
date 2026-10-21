package com.smartdock.dcs.reach;

import com.smartdock.dcs.mapper.DcsUserLookupMapper;
import com.smartdock.dcs.service.DcsNoticeService;
import com.smartdock.profile.domain.ProfileExportLog;
import com.smartdock.profile.engine.ProfileExportNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 导出审计站内通知适配器(批11 Q5): 每次分群成员导出后给本租户在职超管
 * 发一条 PROFILE_EXPORT 通知。幂等键带 logId, 重试/重发不重复打扰。
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileExportNotifyAdapter implements ProfileExportNotifier {

    private static final String NOTICE_TYPE = "PROFILE_EXPORT";

    private final DcsNoticeService noticeService;
    private final DcsUserLookupMapper userLookupMapper;

    @Override
    public void onExport(ProfileExportLog logRow) {
        List<Long> admins = TenantHelper.dynamic(logRow.getTenantId(), userLookupMapper::selectAdminUserIds);
        if (admins == null || admins.isEmpty()) {
            log.info("[Profile] export notify skipped, no admin in tenant={}", logRow.getTenantId());
            return;
        }
        String title = "画像分群导出提醒";
        String content = "分群「" + logRow.getSegmentName() + "」成员被导出 " + logRow.getRowCount()
            + " 行(" + ("1".equals(logRow.getMasked()) ? "脱敏" : "明文") + "), 导出人 userId="
            + logRow.getExportBy() + ", 请在合规中心核查。";
        for (Long adminId : admins) {
            noticeService.notifyIdem(adminId, NOTICE_TYPE, title, content, null,
                "profile:export:" + logRow.getLogId() + ":" + adminId);
        }
    }
}
