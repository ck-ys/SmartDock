package org.dromara.common.log.enums;

/**
 * 业务操作类型
 *
 * @author ruoyi
 */
public enum BusinessType {

    // ===== DCS 调用指南 =====
    // DCS 引用频次: 32+ 个文件
    // 典型用法: DCS 常用 OTHER(业务操作) 和 INSERT/UPDATE/DELETE/EXPORT; 配合 @Log 注解的 businessType 属性标注操作类型
    // 扩展点: 新增业务操作类型在此枚举扩展(一般 OTHER 即可覆盖大部分自定义业务场景)
    // ===== DCS 调用指南 结束 =====

    /**
     * 其它
     */
    OTHER,

    /**
     * 新增
     */
    INSERT,

    /**
     * 修改
     */
    UPDATE,

    /**
     * 删除
     */
    DELETE,

    /**
     * 授权
     */
    GRANT,

    /**
     * 导出
     */
    EXPORT,

    /**
     * 导入
     */
    IMPORT,

    /**
     * 强退
     */
    FORCE,

    /**
     * 生成代码
     */
    GENCODE,

    /**
     * 清空数据
     */
    CLEAN,
}
