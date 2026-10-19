package com.smartdock.dcs.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 任务创建/编辑入参(DRAFT 期可改; 发布后只读)。
 *
 * @author smartdock
 */
@Data
public class DcsTaskBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 编辑时必填; 新建忽略 */
    private Long id;

    private String title;

    private String description;

    private Date deadline;

    /** 目标成员(单级, 发布人自填) */
    private List<Long> assigneeUserIds;

    /** ASSIGNED / PUBLISHER */
    private String auditMode;

    /** 指定审核人(ASSIGNED 模式必填非空) */
    private List<Long> auditorUserIds;

    /** 字段定义(整表替换式保存) */
    private List<DcsFieldBo> fields;

    @Data
    public static class DcsFieldBo implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private String fieldCode;

        private String fieldName;

        private String fieldType;

        /** 1=必填 */
        private String required;

        /** select/multi_select 候选 */
        private List<String> options;

        private Integer orderNum;
    }
}
