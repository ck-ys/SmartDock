package com.smartdock.assistant.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 助手常规偏好出参（批C Q8 案1，2026-11-15）。
 *
 * <p>字段全用前端可直接用的形态（布尔 / 枚举字符串），不把库里的 '1'/'0' 字面量漏给界面；
 * 缺行时返回全部默认值，{@code saved=false} 让前端知道这是默认而不是用户存过的。</p>
 */
@Data
public class AssistantUserPrefVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 库里是否已有本人的行（false=下面是默认值，用户从没存过） */
    private boolean saved;

    private String sendKey;

    private boolean streamOutput;

    private boolean toolsOpen;

    private boolean autoRetry;

    private String motion;

    private String answerSize;

    private boolean askAutoContinue;
}
