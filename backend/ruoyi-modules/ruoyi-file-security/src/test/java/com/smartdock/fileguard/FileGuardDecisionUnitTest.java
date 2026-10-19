package com.smartdock.fileguard;

import com.smartdock.enums.FileReviewCategory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判定协调器单测（file-guard 批一，合同#1-#8 的矩阵验收）。
 * 白名单用 FileReviewCategory.whitelistedExtensions()（单一事实源）。
 *
 * @author smartdock
 */
@Tag("dev")
class FileGuardDecisionUnitTest {

    /** 深扫上限 50MB（批三配置的默认值口径） */
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private static final Function<String, Optional<RiskFinding>> NO_RISK = ext -> Optional.empty();

    private static byte[] bytes(int... values) {
        byte[] arr = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            arr[i] = (byte) values[i];
        }
        return arr;
    }

    @Test
    void extensionWhitelistDenies() {
        FileGuardVerdict v = FileGuardDecision.decide("hack.exe", 100, bytes('M', 'Z'),
            FileReviewCategory.whitelistedExtensions(), MAX_BYTES, NO_RISK);
        assertThat(v.action()).isEqualTo(GuardAction.DENY);
        assertThat(v.rule()).isEqualTo("EXTENSION_WHITELIST");
        assertThat(v.userMessage()).contains("不允许");

        // 无扩展名同样拒
        assertThat(FileGuardDecision.decide("README", 10, new byte[8],
            FileReviewCategory.whitelistedExtensions(), MAX_BYTES, NO_RISK).action()).isEqualTo(GuardAction.DENY);
        // html 明确在白名单外（Q4=A：剔除）
        assertThat(FileGuardDecision.decide("page.html", 10, new byte[8],
            FileReviewCategory.whitelistedExtensions(), MAX_BYTES, NO_RISK).action()).isEqualTo(GuardAction.DENY);
    }

    @Test
    void magicMismatchDeniesDisguisedFile() {
        // exe(MZ) 改名 .pdf（拍板页威胁表场景）
        FileGuardVerdict v = FileGuardDecision.decide("report.pdf", 100, bytes('M', 'Z', 0x90, 0x00),
            FileReviewCategory.whitelistedExtensions(), MAX_BYTES, NO_RISK);
        assertThat(v.action()).isEqualTo(GuardAction.DENY);
        assertThat(v.rule()).isEqualTo("MAGIC_MISMATCH");
        assertThat(v.userMessage()).contains("伪装");
    }

    @Test
    void macroGoesToReview() {
        FileGuardVerdict v = FileGuardDecision.decide("课件.docx", 1024, bytes('P', 'K', 0x03, 0x04, 0x14, 0x00),
            FileReviewCategory.whitelistedExtensions(), MAX_BYTES,
            ext -> Optional.of(new RiskFinding("OFFICE_MACRO", "检测到 VBA 宏存储（vbaProject.bin）")));
        assertThat(v.action()).isEqualTo(GuardAction.REVIEW);
        assertThat(v.rule()).isEqualTo("OFFICE_MACRO");
        assertThat(v.userMessage()).contains("人工审核");
    }

    @Test
    void cleanFilePasses() {
        FileGuardVerdict v = FileGuardDecision.decide("photo.JPG", 2048, bytes(0xFF, 0xD8, 0xFF, 0xE0),
            FileReviewCategory.whitelistedExtensions(), MAX_BYTES, NO_RISK);
        assertThat(v.action()).isEqualTo(GuardAction.PASS);
        assertThat(v.rule()).isEqualTo("CLEAN");
    }

    @Test
    void oversizeSkipsDeepScanWithoutCallingIt() {
        AtomicInteger deepScanCalls = new AtomicInteger();
        FileGuardVerdict v = FileGuardDecision.decide("big.pdf", 60L * 1024 * 1024, "%PDF-1.7".getBytes(),
            FileReviewCategory.whitelistedExtensions(), MAX_BYTES,
            ext -> {
                deepScanCalls.incrementAndGet();
                return Optional.of(new RiskFinding("PDF_JAVASCRIPT", "不该被扫到"));
            });
        // 500MB 上传上限时代的内存闸：超限只做 L1/L2，深扫函数根本不调用
        assertThat(v.action()).isEqualTo(GuardAction.PASS);
        assertThat(v.rule()).isEqualTo("DEEP_SCAN_SKIPPED");
        assertThat(deepScanCalls.get()).isZero();
    }

    @Test
    void wmvWithoutSignatureSkipsL2ButL1Holds() {
        // wmv 在白名单（VIDEO 类）但无魔数判据 → L2 跳过不误杀
        assertThat(FileGuardDecision.decide("clip.wmv", 100, bytes('M', 'Z'),
            FileReviewCategory.whitelistedExtensions(), MAX_BYTES, NO_RISK).action()).isEqualTo(GuardAction.PASS);
        // 但 wmv 不在白名单配置里时 L1 仍拒（分层不互相干扰）
        assertThat(FileGuardDecision.decide("clip.wmv", 100, bytes('M', 'Z'),
            java.util.Set.of("jpg"), MAX_BYTES, NO_RISK).action()).isEqualTo(GuardAction.DENY);
    }

    @Test
    void svgRoutingWiresRealScanner() {
        // 模拟接线层的真实路由：svg → SvgXssScanner
        Function<String, Optional<RiskFinding>> router = ext ->
            "svg".equals(ext) ? SvgXssScanner.scan("<svg onload=\"alert(1)\"/>") : Optional.empty();
        FileGuardVerdict v = FileGuardDecision.decide("icon.svg", 100, new byte[8],
            FileReviewCategory.whitelistedExtensions(), MAX_BYTES, router);
        assertThat(v.action()).isEqualTo(GuardAction.REVIEW);
        assertThat(v.rule()).isEqualTo("SVG_XSS");
    }

    @Test
    void extensionExtraction() {
        assertThat(FileGuardDecision.extractExtension("A.JPG")).isEqualTo("jpg");
        assertThat(FileGuardDecision.extractExtension("archive.tar.gz")).isEqualTo("gz");
        assertThat(FileGuardDecision.extractExtension("README")).isEmpty();
        assertThat(FileGuardDecision.extractExtension(null)).isEmpty();
        assertThat(FileGuardDecision.extractExtension("trailing.")).isEmpty();
    }
}
