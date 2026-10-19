package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 规则发布参数。
 *
 * @author smartdock
 */
@Data
public class ProfileRulePublishBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String version;

    private String note;

    private Integer revision;
}
