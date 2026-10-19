package com.smartdock.risk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runtime rollout switches for security risk controls and profile scoring.
 *
 * <p>All switches default to enabled so rolling upgrades preserve the existing
 * behavior. Operations can disable one stage independently through environment
 * variables and restart the application.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "dcs.rollout")
public class DcsRolloutProperties {

    /** 是否启用行为事件采集, 默认 true */
    private boolean eventCollectionEnabled = true;
    /** 是否启用 L2 中风险校验(图形验证码), 默认 true */
    private boolean l2EnforcementEnabled = true;
    /** 是否启用 L3/L4 高风险校验(滑块/OTP), 默认 true */
    private boolean l34EnforcementEnabled = true;
    /** 是否启用用户画像评分, 默认 true */
    private boolean profileScoringEnabled = true;
    /** 是否启用 AI 自动调分(画像/规则), 默认 true */
    private boolean aiAdjustmentEnabled = true;
    /** 是否启用画像重算 Worker, 默认 true */
    private boolean recalculationWorkersEnabled = true;
    /** 是否启用已发布任务快照严格校验, 默认 true */
    private boolean strictPublishedSnapshotsEnabled = true;
    /**
     * 是否启用任务填写内容机审(风控2.0, 2026-09-22), 默认 false——新增行为默认关, 保持既有行为不变。
     * 开启后表单/追加/在线批量/Excel 四入口对文本字段做 FORM 场景词库匹配并记 TASK_CONTENT_RISK 事件。
     * volatile(收尾批Q4, 2026-09-23): 风控配置页运行时可改, 保证跨线程可见; 重启回 yml 安全档。
     */
    private volatile boolean contentModerationEnabled = false;
    /**
     * 是否启用内容机审 BLOCK 拦截, 默认 false(观察模式: 只记事件不拒绝任何提交)。
     * 业务确认误伤率可控后另行开启; 开启后仅拒绝命中 BLOCK 级词的行, 正常提交零感知。
     * volatile(收尾批Q4, 2026-09-23): 同上, 页面开关运行时可改。
     */
    private volatile boolean contentBlockEnabled = false;

    public boolean isChallengeEnabled(int level) {
        if (level <= 1) {
            return true;
        }
        if (level == 2) {
            return l2EnforcementEnabled;
        }
        return l34EnforcementEnabled;
    }
}
