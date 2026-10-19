package com.smartdock.dcs.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.smartdock.dcs.support.DcsJsons;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 微核心: 任务动态字段定义。
 *
 * <p>field_type ∈ {text, number, date, select, multi_select, attachment};
 * select/multi_select 的候选取 options(JSON 文本)。</p>
 *
 * @author smartdock
 */
@Data
@TableName("dcs_task_field")
public class DcsTaskField implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_DATE = "date";
    public static final String TYPE_SELECT = "select";
    public static final String TYPE_MULTI_SELECT = "multi_select";
    public static final String TYPE_ATTACHMENT = "attachment";

    @TableId
    private Long id;

    private String tenantId;

    private Long taskId;

    private String fieldCode;

    private String fieldName;

    private String fieldType;

    /** 1=必填 */
    private String required;

    /** 枚举候选的 JSON 文本(["A","B"]型) */
    private String options;

    private Integer orderNum;

    private Date createTime;

    /** 必填口径("1"=必填)。不写 isRequired()——与 Lombok getRequired() 撞 JavaBeans
     *  属性名, MyBatis 反射报 Illegal overloaded getter(E2E 实锤)。 */
    public boolean requiredFlag() {
        return "1".equals(required);
    }

    public List<String> optionList() {
        return DcsJsons.stringList(options);
    }
}
