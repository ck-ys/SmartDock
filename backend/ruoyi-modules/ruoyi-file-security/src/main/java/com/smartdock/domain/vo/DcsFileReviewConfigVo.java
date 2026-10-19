package com.smartdock.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文件审核配置视图对象（12b）——设置页读取/保存载体，附实时统计（待审数/审核员数）供警告区展示。
 *
 * @author smartdock
 */
@Data
public class DcsFileReviewConfigVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 总开关：1=启用，0=关闭 */
    private String enabled;

    /** 启用审核的类别逗号串 */
    private String categories;

    /** 当前待审单数（设置页"关闭影响面"警告） */
    private Long pendingCount;

    /** file_reviewer 角色下的用户数（0 时设置页警告"无审核员"；超管/租户管理员兜底兼任但不计入本口径） */
    private Long reviewerCount;

    /** file-guard 检测模式：OFF/OBSERVE/ENFORCE（展示层缺省 OBSERVE） */
    private String guardMode;

    /** file-guard 白名单逗号串（空=使用七类并集默认） */
    private String guardWhitelist;

    /** file-guard 深扫大小上限 MB */
    private Integer guardDeepScanMaxMb;
}
