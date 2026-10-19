package com.smartdock.fileguard;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 扩展名 → 文件头魔数比对表（file-guard L2 防伪装层）。
 *
 * <p>只收录"魔数可靠且 DCS 常见上传"的格式；无把握的一律 {@link #hasSignature} 返回 false
 * 跳过校验（L2 对奇异格式 fail-open，白名单层 L1 仍然生效）——误报拒传比漏报更伤
 * （grill Q6 误报炸群教训）。iemp 原实现的 checkMagicNumber 是写了没接线的死代码，本表为救活补全版。</p>
 *
 * <p>特殊结构：RIFF 家族（webp/wav/avi）= 头部 "RIFF" + 偏移 8 的格式标记；
 * MP4 家族（mp4/m4v/mov）= 偏移 4 的 "ftyp"；MP3 = "ID3" 或帧同步头。</p>
 *
 * @author smartdock
 */
public final class MagicNumberTable {

    private MagicNumberTable() {
    }

    /** 偏移 0 起始的魔数（每扩展名多签任一命中即通过，如 zip 家族的 PK 三种开号） */
    private static final Map<String, byte[][]> SIGNATURES = Map.ofEntries(
        entry("jpg", new byte[][]{{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}}),
        entry("jpeg", new byte[][]{{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}}),
        entry("png", new byte[][]{{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}}),
        entry("gif", new byte[][]{{'G', 'I', 'F', '8'}}),
        entry("bmp", new byte[][]{{'B', 'M'}}),
        entry("ico", new byte[][]{{0x00, 0x00, 0x01, 0x00}}),
        entry("pdf", new byte[][]{{'%', 'P', 'D', 'F', '-'}}),
        // OLE2 老格式（doc/xls/ppt 可原生携带宏，正是 L3 宏扫描的对象）
        entry("doc", new byte[][]{{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}}),
        entry("xls", new byte[][]{{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}}),
        entry("ppt", new byte[][]{{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}}),
        // zip 家族（docx/xlsx/pptx 本质是 zip；PK 三种合法开号）
        entry("docx", pkSignatures()),
        entry("xlsx", pkSignatures()),
        entry("pptx", pkSignatures()),
        entry("zip", pkSignatures()),
        entry("rar", new byte[][]{{'R', 'a', 'r', '!', 0x1A, 0x07}}),
        entry("7z", new byte[][]{{'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C}}),
        entry("gz", new byte[][]{{(byte) 0x1F, (byte) 0x8B}}),
        entry("rtf", new byte[][]{{'{', '\\', 'r', 't', 'f'}}),
        entry("flac", new byte[][]{{'f', 'L', 'a', 'C'}}),
        entry("ogg", new byte[][]{{'O', 'g', 'g', 'S'}}),
        entry("mkv", new byte[][]{{(byte) 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3}})
    );

    /** RIFF 容器家族：头 4 字节 "RIFF" + 偏移 8 的 4 字节格式标记 */
    private static final Map<String, String> RIFF_MARKS = Map.of(
        "webp", "WEBP",
        "wav", "WAVE",
        "avi", "AVI "
    );

    /** MP4 家族：偏移 4 处为 "ftyp" */
    private static final Set<String> FTYP_FAMILY = Set.of("mp4", "m4v", "mov");

    private static byte[][] pkSignatures() {
        return new byte[][]{
            {'P', 'K', 0x03, 0x04},
            {'P', 'K', 0x05, 0x06},
            {'P', 'K', 0x07, 0x08}
        };
    }

    private static Map.Entry<String, byte[][]> entry(String ext, byte[][] sigs) {
        return Map.entry(ext, sigs);
    }

    /**
     * 该扩展名是否有可靠的魔数判据（无判据的格式 L2 跳过，不比对）。
     * 入参大小写不敏感、可带前导点（与 {@link #matches} 口径一致）。
     */
    public static boolean hasSignature(String ext) {
        if (ext == null) {
            return false;
        }
        String key = ext.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith(".")) {
            key = key.substring(1);
        }
        return SIGNATURES.containsKey(key) || RIFF_MARKS.containsKey(key) || FTYP_FAMILY.contains(key) || "mp3".equals(key);
    }

    /**
     * 文件头样本与扩展名魔数比对。
     *
     * @param ext  小写扩展名（可带点）
     * @param head 文件头样本（建议读 ≥32 字节；空/过短视为不符）
     * @return true=相符（或该格式无判据）；false=不符（伪装嫌疑）
     */
    public static boolean matches(String ext, byte[] head) {
        if (ext == null || head == null || head.length == 0) {
            return false;
        }
        String key = ext.toLowerCase(Locale.ROOT);
        if (key.startsWith(".")) {
            key = key.substring(1);
        }

        // 偏移 0 的常规魔数
        byte[][] candidates = SIGNATURES.get(key);
        if (candidates != null) {
            for (byte[] sig : candidates) {
                if (startsWith(head, sig)) {
                    return true;
                }
            }
            return false;
        }

        // RIFF 家族：RIFF@0 + 格式标记@8
        String riffMark = RIFF_MARKS.get(key);
        if (riffMark != null) {
            return head.length >= 12
                && startsWith(head, new byte[]{'R', 'I', 'F', 'F'})
                && startsWith(head, 8, riffMark.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }

        // MP4 家族：ftyp@4
        if (FTYP_FAMILY.contains(key)) {
            return head.length >= 8
                && startsWith(head, 4, new byte[]{'f', 't', 'y', 'p'});
        }

        // MP3 双判据：ID3v2 头或帧同步（0xFF + 高 3 位 111）
        if ("mp3".equals(key)) {
            return startsWith(head, new byte[]{'I', 'D', '3'})
                || (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0);
        }

        // 无判据格式：不做 L2 比对
        return true;
    }

    /** head 从偏移 0 是否以 sig 开头 */
    private static boolean startsWith(byte[] head, byte[] sig) {
        return startsWith(head, 0, sig);
    }

    /** head 从偏移 offset 是否以 sig 开头 */
    private static boolean startsWith(byte[] head, int offset, byte[] sig) {
        if (head.length < offset + sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if (head[offset + i] != sig[i]) {
                return false;
            }
        }
        return true;
    }
}
