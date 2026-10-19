package com.smartdock.fileguard;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Office 宏扫描单测（file-guard 批一，合同#3 的验收依据）。
 * 恶意样本全部内存构造（zip 条目 / POI 写 OLE2 目录），不落真病毒文件。
 *
 * @author smartdock
 */
@Tag("dev")
class OfficeMacroScannerUnitTest {

    /** 构造 zip 字节（docx/xlsx/pptx 载体），entries 为 (条目名, 内容) 交替 */
    private static byte[] zipBytes(String... entryNames) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String name : entryNames) {
                zos.putNextEntry(new ZipEntry(name));
                zos.write(new byte[]{1, 2, 3, 4});
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** 构造 OLE2 字节（doc/xls/ppt 载体）；withVba=true 时在根存储建 _VBA_PROJECT_CUR 目录 */
    private static byte[] ole2Bytes(boolean withVba) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (withVba) {
                fs.getRoot().createDirectory("_VBA_PROJECT_CUR");
            } else {
                fs.getRoot().createDocument("WordDocument", new ByteArrayInputStream(new byte[16]));
            }
            fs.writeFilesystem(bos);
            return bos.toByteArray();
        }
    }

    @Test
    void zipWithVbaProjectBinHits() throws IOException {
        byte[] docx = zipBytes("[Content_Types].xml", "word/document.xml", "word/vbaProject.bin");
        Optional<RiskFinding> hit = OfficeMacroScanner.scan("docx", new ByteArrayInputStream(docx));
        assertThat(hit).hasValueSatisfying(f -> {
            assertThat(f.rule()).isEqualTo("OFFICE_MACRO");
            assertThat(f.message()).contains("vbaProject.bin");
        });
        // 路径在 xl/ 下同样命中
        assertThat(OfficeMacroScanner.scan("xlsx",
            new ByteArrayInputStream(zipBytes("xl/workbook.xml", "xl/vbaProject.bin")))).isPresent();
    }

    @Test
    void cleanZipPasses() throws IOException {
        byte[] docx = zipBytes("[Content_Types].xml", "word/document.xml", "word/theme/theme1.xml");
        assertThat(OfficeMacroScanner.scan("docx", new ByteArrayInputStream(docx))).isEmpty();
        assertThat(OfficeMacroScanner.scan("pptx",
            new ByteArrayInputStream(zipBytes("ppt/presentation.xml")))).isEmpty();
    }

    @Test
    void ole2WithVbaStorageHits() throws IOException {
        byte[] doc = ole2Bytes(true);
        assertThat(OfficeMacroScanner.scan("doc", new ByteArrayInputStream(doc)))
            .hasValueSatisfying(f -> assertThat(f.message()).contains("_VBA_PROJECT_CUR"));
        assertThat(OfficeMacroScanner.scan("xls", new ByteArrayInputStream(doc))).isPresent();
    }

    @Test
    void cleanOle2Passes() throws IOException {
        byte[] doc = ole2Bytes(false);
        assertThat(OfficeMacroScanner.scan("doc", new ByteArrayInputStream(doc))).isEmpty();
    }

    @Test
    void nonOfficeExtensionPassthrough() throws IOException {
        // 非 Office 扩展名不进扫描器（接线层也不会路由，此处为防御性验证）
        assertThat(OfficeMacroScanner.scan("pdf", new ByteArrayInputStream("%PDF-1.4".getBytes()))).isEmpty();
        assertThat(OfficeMacroScanner.scan(null, new ByteArrayInputStream(new byte[4]))).isEmpty();
    }

    @Test
    void brokenContainerFailsOpen() {
        // 魔数是 PK 但 zip 损坏 → fail-open（预览侧自然报错，不算宏信号）
        assertThat(OfficeMacroScanner.scan("docx",
            new ByteArrayInputStream(new byte[]{'P', 'K', 0x03, 0x04, 9, 9, 9}))).isEmpty();
    }
}
