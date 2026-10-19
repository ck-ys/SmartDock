package org.dromara.common.translation.constant;

/**
 * 翻译常量
 *
 * @author Lion Li
 */
public interface TransConstant {

    // ===== DCS 调用指南 =====
    // DCS 引用频次: 9+ 个文件
    // 典型用法: 翻译类型常量; DCS 常用 DICT_TYPE_TO_LABEL(字典 type 转 label) 和 DEPT_ID_TO_NAME(部门 id 转名称)、USER_ID_TO_NAME(用户 id 转账号)
    // 扩展点: 新增 DCS 专属翻译类型(如 mission_code→name)在此 interface 增加常量并配套实现 TranslationInterface
    // ===== DCS 调用指南 结束 =====

    /**
     * 用户id转账号
     */
    String USER_ID_TO_NAME = "user_id_to_name";

    /**
     * 用户id转用户昵称
     */
    String USER_ID_TO_NICKNAME = "user_id_to_nickname";

    /**
     * 部门id转名称
     */
    String DEPT_ID_TO_NAME = "dept_id_to_name";

    /**
     * 字典type转label
     */
    String DICT_TYPE_TO_LABEL = "dict_type_to_label";

    /**
     * ossId转url
     */
    String OSS_ID_TO_URL = "oss_id_to_url";

}
