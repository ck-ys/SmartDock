package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serializable;

/**
 * 模型发布 BO(批9 Q6-A): 版本号可留空自动递增。
 *
 * @author smartdock
 */
@Data
public class ProfileModelPublishBo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 发布版本号; 空 = 自动递增(v12 → v13) */
    private String version;

    /** 版本说明(≤500, 写入模型 remark) */
    private String note;
}
