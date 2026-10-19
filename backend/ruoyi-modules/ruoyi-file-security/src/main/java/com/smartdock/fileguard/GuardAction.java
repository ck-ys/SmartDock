package com.smartdock.fileguard;

/**
 * 文件安全检测处置动作（file-guard 批，计划 FILE_DETECTION_GUARD_PLAN_20260918）
 *
 * <p>分层混合策略（grill Q2=C 拍板）：确定性信号当场拒传，灰度信号送 12b 审核中心人工裁决。</p>
 *
 * @author smartdock
 */
public enum GuardAction {

    /** 放行（含"超过深扫上限仅做白名单+魔数校验"的跳过场景） */
    PASS,

    /** 拒传（确定性危险：白名单外扩展名 / 魔数不符的伪装文件），对象不落 OSS */
    DENY,

    /** 强制进审（灰度信号：Office 宏 / PDF 内嵌 JS / SVG 脚本内容），即使类目未开审也建单 */
    REVIEW
}
