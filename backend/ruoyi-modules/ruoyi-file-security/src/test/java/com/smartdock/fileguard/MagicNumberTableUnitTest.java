package com.smartdock.fileguard;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 魔数表单测（file-guard 批一，合同#2 伪装拦截的验收依据）。
 *
 * @author smartdock
 */
@Tag("dev")
class MagicNumberTableUnitTest {

    private static byte[] bytes(int... values) {
        byte[] arr = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            arr[i] = (byte) values[i];
        }
        return arr;
    }

    @Test
    void commonSignaturesMatch() {
        assertThat(MagicNumberTable.matches("jpg", bytes(0xFF, 0xD8, 0xFF, 0x00, 0x10))).isTrue();
        assertThat(MagicNumberTable.matches("pdf", "%PDF-1.7 rest...".getBytes())).isTrue();
        assertThat(MagicNumberTable.matches("docx", bytes('P', 'K', 0x03, 0x04, 0x14, 0x00))).isTrue();
        assertThat(MagicNumberTable.matches("doc", bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1))).isTrue();
        assertThat(MagicNumberTable.matches("rar", bytes('R', 'a', 'r', '!', 0x1A, 0x07, 0x00))).isTrue();
        assertThat(MagicNumberTable.matches("7z", bytes('7', 'z', 0xBC, 0xAF, 0x27, 0x1C))).isTrue();
        assertThat(MagicNumberTable.matches("gz", bytes(0x1F, 0x8B, 0x08))).isTrue();
        assertThat(MagicNumberTable.matches("rtf", bytes('{', '\\', 'r', 't', 'f', '1'))).isTrue();
        assertThat(MagicNumberTable.matches("gif", bytes('G', 'I', 'F', '8', '9', 'a'))).isTrue();
        assertThat(MagicNumberTable.matches("bmp", bytes('B', 'M', 0x00))).isTrue();
    }

    @Test
    void riffAndFtypFamiliesMatch() {
        // RIFF 头 + 偏移 8 格式标记
        byte[] webp = bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P');
        assertThat(MagicNumberTable.matches("webp", webp)).isTrue();
        assertThat(MagicNumberTable.matches("wav", bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'))).isTrue();
        // webp 的头配 wav 扩展名 = 标记错位 → 不符
        assertThat(MagicNumberTable.matches("wav", webp)).isFalse();

        // ftyp 在偏移 4
        byte[] mp4 = bytes(0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm');
        assertThat(MagicNumberTable.matches("mp4", mp4)).isTrue();
        assertThat(MagicNumberTable.matches("mov", mp4)).isTrue();
        assertThat(MagicNumberTable.matches("mp4", bytes('M', 'Z', 0x90, 0x00, 'f', 't'))).isFalse();
    }

    @Test
    void mp3DualSignatures() {
        assertThat(MagicNumberTable.matches("mp3", bytes('I', 'D', '3', 0x03))).isTrue();
        assertThat(MagicNumberTable.matches("mp3", bytes(0xFF, 0xFB, 0x90, 0x00))).isTrue();
        assertThat(MagicNumberTable.matches("mp3", bytes('A', 'B', 'C', 'D'))).isFalse();
    }

    @Test
    void disguisedFileRejected() {
        // exe(MZ) 改名 .pdf —— 拍板页威胁表场景
        byte[] exeHead = bytes('M', 'Z', 0x90, 0x00, 0x03, 0x00);
        assertThat(MagicNumberTable.matches("pdf", exeHead)).isFalse();
    }

    @Test
    void noSignatureFormatsSkipL2() {
        // 无判据格式：不比对、直接放行 L2（fail-open 防误报）
        assertThat(MagicNumberTable.hasSignature("wmv")).isFalse();
        assertThat(MagicNumberTable.hasSignature("txt")).isFalse();
        assertThat(MagicNumberTable.hasSignature("svg")).isFalse();
        assertThat(MagicNumberTable.hasSignature("tar")).isFalse();
        assertThat(MagicNumberTable.matches("wmv", bytes('M', 'Z', 0x90))).isTrue();
    }

    @Test
    void blankOrNullHeadFailsClosed() {
        assertThat(MagicNumberTable.matches("pdf", new byte[0])).isFalse();
        assertThat(MagicNumberTable.matches("pdf", null)).isFalse();
        // 文件比魔数还短 → 不符
        assertThat(MagicNumberTable.matches("png", bytes(0x89, 'P'))).isFalse();
    }

    @Test
    void caseInsensitiveExtension() {
        assertThat(MagicNumberTable.hasSignature(".PDF")).isTrue();
        assertThat(MagicNumberTable.matches(".Pdf", "%PDF-".getBytes())).isTrue();
    }
}
