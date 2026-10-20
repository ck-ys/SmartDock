package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serializable;

/**
 * 分群触达发送 BO（批9 Q3-A）。
 *
 * @author smartdock
 */
@Data
public class ProfileReachSendBo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 通知标题(必填, ≤200) */
    private String title;

    /** 通知正文(≤1000) */
    private String content;
}
