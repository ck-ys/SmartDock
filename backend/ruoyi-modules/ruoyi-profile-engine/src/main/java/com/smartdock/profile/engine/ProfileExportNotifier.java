package com.smartdock.profile.engine;

import com.smartdock.profile.domain.ProfileExportLog;

/**
 * 导出通知 SPI（批11 Q5）：画像引擎只留审计行，"给管理员发站内通知"由宿主侧
 * 实现并注册为 Spring Bean（demo 模块实现站内通知版；不注册则只审计不通知）。
 *
 * @author smartdock
 */
public interface ProfileExportNotifier {

    /**
     * 一次导出完成后回调。实现方自行幂等、自行吞异常(通知失败不能影响导出)。
     */
    void onExport(ProfileExportLog log);
}
