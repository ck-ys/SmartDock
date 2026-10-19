package com.smartdock.fileguard;

import org.dromara.common.core.exception.ServiceException;
import com.smartdock.domain.DcsFileGuardLog;
import com.smartdock.domain.DcsFileReviewConfig;
import com.smartdock.mapper.DcsFileGuardLogMapper;
import com.smartdock.service.IDcsFileReviewConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 命中记录落库矩阵（弹窗改版 Q4=A，计划 GUARD_CONFIG_UI_REDESIGN_20260918 批A）
 *
 * <p>三种处置的翻译 + 落库失败不阻断上传主流程（合同：检测记录是运营抓手，不是交易一致性环节）。</p>
 *
 * @author smartdock
 */
@Tag("dev")
class DcsFileGuardLogPersistUnitTest {

    private final IDcsFileReviewConfigService configService = mock(IDcsFileReviewConfigService.class);
    private final DcsFileGuardLogMapper guardLogMapper = mock(DcsFileGuardLogMapper.class);

    private DcsFileGuardCheckerImpl checker(String mode) {
        DcsFileReviewConfig cfg = new DcsFileReviewConfig();
        cfg.setEnabled("0");
        cfg.setCategories("");
        cfg.setGuardMode(mode);
        when(configService.getEffective()).thenReturn(cfg);
        return new DcsFileGuardCheckerImpl(configService, guardLogMapper);
    }

    /** 带宏 docx（zip 内含 word/vbaProject.bin，触发 OFFICE_MACRO → REVIEW） */
    private static MockMultipartFile macroDocx(String name) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("word/vbaProject.bin"));
            zos.write(new byte[]{1, 2, 3, 4});
            zos.closeEntry();
        }
        return new MockMultipartFile("file", name, "application/octet-stream", bos.toByteArray());
    }

    /** 伪装 exe（MZ 头挂 .pdf 名，触发 MAGIC_MISMATCH → DENY） */
    private static MockMultipartFile fakePdfExe() {
        return new MockMultipartFile("file", "hack.pdf", "application/pdf",
            new byte[]{'M', 'Z', (byte) 0x90, 0});
    }

    @Test
    void enforceDenyLoggedAsDenied() {
        assertThatThrownBy(() -> checker("ENFORCE").checkUpload(fakePdfExe()))
            .isInstanceOf(ServiceException.class);
        ArgumentCaptor<DcsFileGuardLog> captor = ArgumentCaptor.forClass(DcsFileGuardLog.class);
        verify(guardLogMapper, times(1)).insert(captor.capture());
        DcsFileGuardLog row = captor.getValue();
        assertThat(row.getDisposition()).isEqualTo("DENIED");
        assertThat(row.getMode()).isEqualTo("ENFORCE");
        assertThat(row.getRuleCode()).isEqualTo("MAGIC_MISMATCH");
        assertThat(row.getFileName()).isEqualTo("hack.pdf");
        assertThat(row.getDetail()).isNotBlank();
    }

    @Test
    void enforceReviewLoggedAsReviewed() throws IOException {
        String risk = checker("ENFORCE").checkUpload(macroDocx("macro.docx"));
        assertThat(risk).isNotBlank();
        ArgumentCaptor<DcsFileGuardLog> captor = ArgumentCaptor.forClass(DcsFileGuardLog.class);
        verify(guardLogMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getDisposition()).isEqualTo("REVIEWED");
        assertThat(captor.getValue().getRuleCode()).isEqualTo("OFFICE_MACRO");
    }

    @Test
    void observeHitLoggedAsObserved() throws IOException {
        String risk = checker("OBSERVE").checkUpload(macroDocx("macro.docx"));
        assertThat(risk).isNull();
        ArgumentCaptor<DcsFileGuardLog> captor = ArgumentCaptor.forClass(DcsFileGuardLog.class);
        verify(guardLogMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getDisposition()).isEqualTo("OBSERVED");
        assertThat(captor.getValue().getMode()).isEqualTo("OBSERVE");
    }

    @Test
    void persistFailureNeverBlocksUpload() throws IOException {
        doThrow(new RuntimeException("db down")).when(guardLogMapper).insert(any(DcsFileGuardLog.class));
        // OBSERVE 命中：落库炸了也要放行（返回 null）
        assertThatCode(() -> {
            String risk = checker("OBSERVE").checkUpload(macroDocx("macro.docx"));
            assertThat(risk).isNull();
        }).doesNotThrowAnyException();
        // ENFORCE 拒传：落库炸了照样拒（抛 ServiceException 而不是 RuntimeException）
        assertThatThrownBy(() -> checker("ENFORCE").checkUpload(fakePdfExe()))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("伪装");
    }
}
