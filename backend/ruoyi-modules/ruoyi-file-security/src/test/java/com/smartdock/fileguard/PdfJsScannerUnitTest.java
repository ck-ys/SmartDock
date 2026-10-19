package com.smartdock.fileguard;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 内嵌 JavaScript 扫描单测（file-guard 批一，合同#5 的验收依据）。
 * 恶意样本用 PDFBox 现场构造（OpenAction JS），不落真病毒文件。
 *
 * @author smartdock
 */
@Tag("dev")
class PdfJsScannerUnitTest {

    private static byte[] blankPdf() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    private static byte[] pdfWithOpenActionJs() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert(1)"));
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    @Test
    void cleanPdfPasses() throws IOException {
        assertThat(PdfJsScanner.scan(blankPdf())).isEmpty();
    }

    @Test
    void openActionJavaScriptHits() throws IOException {
        Optional<RiskFinding> hit = PdfJsScanner.scan(pdfWithOpenActionJs());
        assertThat(hit).hasValueSatisfying(f -> {
            assertThat(f.rule()).isEqualTo("PDF_JAVASCRIPT");
            assertThat(f.message()).contains("OpenAction");
        });
    }

    @Test
    void unparseablePdfGoesToReview() {
        // %PDF 开头但结构损坏 → PARSE_ANOMALY 灰度进审（iemp 原版 fail-closed 拒传，本版按 Q2=C 改进审）
        byte[] broken = "%PDF-1.4\nthis is not a real pdf\n".getBytes();
        assertThat(PdfJsScanner.scan(broken)).hasValueSatisfying(f -> {
            assertThat(f.rule()).isEqualTo("PARSE_ANOMALY");
            assertThat(f.message()).contains("解析异常");
        });
    }

    @Test
    void nullOrEmptyFailsSafe() {
        // 空字节组：Loader 抛异常 → 进审（不是放行，也不是拒传）
        assertThat(PdfJsScanner.scan(new byte[0])).isPresent();
        assertThat(PdfJsScanner.scan(null)).isPresent();
    }
}
