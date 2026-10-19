package com.smartdock.fileguard;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Office 文档宏检测器（file-guard L3，iemp 完全没做的威胁面主战场——grill Q3=B 拍板进审）。
 *
 * <p>两类载体两条路径：docx/xlsx/pptx（zip 结构）流式扫包内条目名 vbaProject.bin；
 * doc/xls/ppt（OLE2 老格式）扫根存储下的 _VBA_PROJECT_CUR 目录。
 * docm/xlsm/pptm 扩展名不在默认白名单内，L1 已拒传，不会到达本扫描器。</p>
 *
 * <p>解析异常 fail-open：L2 已确认容器魔数，损坏文档由预览侧自然暴露，
 * 不构成"宏"的安全信号（避免误报拦下正常文件）。</p>
 *
 * @author smartdock
 */
public final class OfficeMacroScanner {

    /** docx/xlsx/pptx 包内 VBA 宏的存储文件名（路径形如 word/vbaProject.bin） */
    private static final String VBA_ENTRY_NAME = "vbaproject.bin";

    /** OLE2 老格式中 VBA 工程所在的根存储目录名 */
    private static final String VBA_STORAGE_NAME = "_VBA_PROJECT_CUR";

    private OfficeMacroScanner() {
    }

    /**
     * 按扩展名路由扫描（非 Office 扩展名直接放行）。
     *
     * @param ext 小写扩展名（不带点）
     * @param in  文件内容流（只读一遍，调用方供应全新流）
     */
    public static Optional<RiskFinding> scan(String ext, InputStream in) {
        if (ext == null || in == null) {
            return Optional.empty();
        }
        return switch (ext) {
            case "docx", "xlsx", "pptx" -> scanZip(in);
            case "doc", "xls", "ppt" -> scanOle2(in);
            default -> Optional.empty();
        };
    }

    /** zip 路径：流式遍历条目名，只找宏存储、不解压内容（大包也只扫目录，快） */
    private static Optional<RiskFinding> scanZip(InputStream in) {
        try (ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.getName().toLowerCase(Locale.ROOT).contains(VBA_ENTRY_NAME)) {
                    return Optional.of(new RiskFinding("OFFICE_MACRO", "检测到 VBA 宏存储（vbaProject.bin）"));
                }
            }
            return Optional.empty();
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** OLE2 路径：POI 读根存储，查 VBA 工程目录是否存在 */
    private static Optional<RiskFinding> scanOle2(InputStream in) {
        try (POIFSFileSystem fs = new POIFSFileSystem(in)) {
            fs.getRoot().getEntry(VBA_STORAGE_NAME);
            return Optional.of(new RiskFinding("OFFICE_MACRO", "检测到 VBA 宏工程（_VBA_PROJECT_CUR）"));
        } catch (FileNotFoundException notPresent) {
            // 根存储下无 VBA 工程目录 = 无宏
            return Optional.empty();
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }
}
