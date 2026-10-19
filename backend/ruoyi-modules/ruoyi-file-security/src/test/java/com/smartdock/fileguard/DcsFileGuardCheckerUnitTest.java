package com.smartdock.fileguard;

import org.dromara.common.core.exception.ServiceException;
import com.smartdock.domain.DcsFileReviewConfig;
import com.smartdock.mapper.DcsFileGuardLogMapper;
import com.smartdock.service.IDcsFileReviewConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * file-guard SPI 实现单测（批二接线，合同#1/#2/#3/#6/#8 的模式语义验收）。
 * OBSERVE/ENFORCE/OFF 三档对同一批恶意样本的行为矩阵。
 *
 * @author smartdock
 */
@Tag("dev")
class DcsFileGuardCheckerUnitTest {

    private final IDcsFileReviewConfigService configService = mock(IDcsFileReviewConfigService.class);
    private final DcsFileGuardLogMapper guardLogMapper = mock(DcsFileGuardLogMapper.class);

    private DcsFileReviewConfig cfg(String mode) {
        DcsFileReviewConfig cfg = new DcsFileReviewConfig();
        cfg.setEnabled("0");
        cfg.setCategories("");
        cfg.setGuardMode(mode);
        return cfg;
    }

    private DcsFileGuardCheckerImpl checker(String mode) {
        when(configService.getEffective()).thenReturn(cfg(mode));
        return new DcsFileGuardCheckerImpl(configService, guardLogMapper);
    }

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

    @Test
    void offModePassesEverything() {
        // OFF=全直通：exe 也不拦
        MockMultipartFile exe = new MockMultipartFile("file", "hack.exe", "application/x-msdownload", new byte[]{'M', 'Z', (byte) 0x90});
        assertThat(checker("OFF").checkUpload(exe)).isNull();
    }

    @Test
    void observeModeLogsButNeverBlocks() {
        // OBSERVE（含空值缺省）：拒传级 exe 也只记日志、放行
        assertThat(checker("OBSERVE").checkUpload(
            new MockMultipartFile("file", "hack.exe", "application/x-msdownload", new byte[]{'M', 'Z'}))).isNull();
        // guard_mode 为 null（DISABLED 常量行）→ 兜底 OBSERVE 同样放行
        assertThat(checker(null).checkUpload(
            new MockMultipartFile("file", "hack.exe", "application/x-msdownload", new byte[]{'M', 'Z'}))).isNull();
    }

    @Test
    void enforceDeniesNonWhitelistedExtension() {
        MockMultipartFile exe = new MockMultipartFile("file", "hack.exe", "application/x-msdownload", new byte[]{'M', 'Z', (byte) 0x90});
        assertThatThrownBy(() -> checker("ENFORCE").checkUpload(exe))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("不允许");
    }

    @Test
    void enforceDeniesDisguisedFile() {
        // exe(MZ) 改名 .pdf
        MockMultipartFile fake = new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[]{'M', 'Z', (byte) 0x90, 0x00});
        assertThatThrownBy(() -> checker("ENFORCE").checkUpload(fake))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("伪装");
    }

    @Test
    void enforceMacroGoesToReviewNotDenied() throws IOException {
        // 宏命中=灰度信号：返回进审说明而非抛异常（Q3=B：老师带宏课件不误杀）
        byte[] docx = zipBytes("[Content_Types].xml", "word/vbaProject.bin");
        String risk = checker("ENFORCE").checkUpload(
            new MockMultipartFile("file", "课件.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx));
        assertThat(risk).contains("宏");
        // OBSERVE 下同一文件只记日志 → null
        assertThat(checker("OBSERVE").checkUpload(
            new MockMultipartFile("file", "课件.docx", "application/octet-stream", docx))).isNull();
    }

    @Test
    void enforceCleanDocxPasses() throws IOException {
        byte[] docx = zipBytes("[Content_Types].xml", "word/document.xml");
        assertThat(checker("ENFORCE").checkUpload(
            new MockMultipartFile("file", "作业.docx", "application/octet-stream", docx))).isNull();
    }

    @Test
    void enforceSvgScriptGoesToReview() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>".getBytes();
        String risk = checker("ENFORCE").checkUpload(
            new MockMultipartFile("file", "icon.svg", "image/svg+xml", svg));
        assertThat(risk).contains("SVG");
    }

    @Test
    void oversizeFileSkipsDeepScanOnly() throws IOException {
        // 深扫上限 1MB、文件 2MB（wmv 在白名单且无魔数判据）→ 只做 L1/L2，深扫跳过
        DcsFileReviewConfig cfg = cfg("ENFORCE");
        cfg.setGuardDeepScanMaxMb(1);
        when(configService.getEffective()).thenReturn(cfg);
        byte[] big = new byte[2 * 1024 * 1024];
        assertThat(new DcsFileGuardCheckerImpl(configService, guardLogMapper).checkUpload(
            new MockMultipartFile("file", "clip.wmv", "video/x-ms-wmv", big))).isNull();
    }

    @Test
    void customWhitelistOverridesDefault() {
        // 白名单收窄为 jpg：png 也会被拒（配置口径优先于七类并集）
        DcsFileReviewConfig cfg = cfg("ENFORCE");
        cfg.setGuardWhitelist("jpg");
        when(configService.getEffective()).thenReturn(cfg);
        assertThatThrownBy(() -> new DcsFileGuardCheckerImpl(configService, guardLogMapper).checkUpload(
            new MockMultipartFile("file", "photo.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'})))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("不允许");
    }
}
