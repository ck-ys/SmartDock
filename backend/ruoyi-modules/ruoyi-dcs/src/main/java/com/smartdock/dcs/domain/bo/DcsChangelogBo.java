package com.smartdock.dcs.domain.bo;

import com.smartdock.dcs.domain.ChangelogItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新日志保存/查询入参（N3 2026-10-23）。
 *
 * @author smartdock
 */
@Data
public class DcsChangelogBo {

    @NotNull(message = "日志 ID 不能为空", groups = Edit.class)
    private Long logId;

    @NotBlank(message = "版本号不能为空", groups = {Add.class, Edit.class})
    @Size(max = 32, message = "版本号最长 32 字", groups = {Add.class, Edit.class})
    @Pattern(regexp = "^v?\\d+(\\.\\d+)*$", message = "版本号格式应为 v1.2.3", groups = {Add.class, Edit.class})
    private String version;

    @NotBlank(message = "版本主题不能为空", groups = {Add.class, Edit.class})
    @Size(max = 200, message = "版本主题最长 200 字", groups = {Add.class, Edit.class})
    private String title;

    @NotNull(message = "更新条目不能为空", groups = {Add.class, Edit.class})
    @Size(min = 1, max = 50, message = "每个版本需 1~50 条更新", groups = {Add.class, Edit.class})
    private List<ChangelogItem> itemList;

    /** null=保留旧名单；空数组=清空（仅草稿允许） */
    private List<String> targetTenantList;

    @Pattern(regexp = "[01]", message = "状态不合法", groups = Edit.class)
    private String status;

    public interface Add {
    }

    public interface Edit {
    }
}
