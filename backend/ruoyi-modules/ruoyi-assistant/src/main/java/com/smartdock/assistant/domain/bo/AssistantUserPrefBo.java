package com.smartdock.assistant.domain.bo;

import lombok.Data;

/**
 * 助手常规偏好入参（批C Q8 案1，2026-11-15）。
 *
 * <p>七项全量覆盖式保存（与前端「改哪项推整份」一致）：不传的项按默认值落库，
 * 不做部分更新——避免两台设备各改一半后互相盖出半新半旧的状态。</p>
 */
@Data
public class AssistantUserPrefBo {

    /** enter / ctrl-enter */
    private String sendKey;

    private Boolean streamOutput;

    private Boolean toolsOpen;

    private Boolean autoRetry;

    /** system / full / reduced */
    private String motion;

    /** standard / large */
    private String answerSize;

    private Boolean askAutoContinue;
}
