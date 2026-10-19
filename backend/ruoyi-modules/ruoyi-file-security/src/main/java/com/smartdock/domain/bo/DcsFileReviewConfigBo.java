package com.smartdock.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 文件审核配置保存入参（12b）——总开关 + 启用类别多选
 *
 * <p>仅管理员/租户管理员可见的设置页使用（dcs:fileReview:config 权限）。</p>
 *
 * @author smartdock
 */
@Data
public class DcsFileReviewConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 总开关：true=启用审核，false=全部直通（关闭只影响新上传，存量待审单保留可审，Q4-A） */
    @NotNull(message = "总开关不能为空")
    private Boolean enabled;

    /** 启用审核的类别（IMAGE/VIDEO/...七类子集；未列出的类别上传直接通过不落数据） */
    private List<String> categories;

    /** file-guard 检测模式：OFF/OBSERVE/ENFORCE（空=OBSERVE；批三 Q5=B 界面化管理） */
    private String guardMode;

    /** file-guard 白名单逗号串（小写不带点；空=FileReviewCategory 七类并集默认） */
    private String guardWhitelist;

    /** file-guard 深扫大小上限 MB（空=50；超过只做白名单+魔数两层） */
    private Integer guardDeepScanMaxMb;
}
