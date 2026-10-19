package com.smartdock.fileguard;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * file-guard 判定协调器（纯函数、无 Spring 依赖——单测友好；批二接线层负责流供应与配置注入）。
 *
 * <p>分层序（合同#1-#8）：L1 白名单拒传 → L2 魔数拒传 → 超深扫上限放行（跳过）
 * → L3 内容深扫进审 → 干净放行。上传上限放开 500MB 后，深扫上限（默认 50MB，
 * 界面可配）是内存必选闸：超过者只做 L1/L2。</p>
 *
 * @author smartdock
 */
public final class FileGuardDecision {

    private FileGuardDecision() {
    }

    /** 从文件名提取小写扩展名（不带点；无扩展名/空文件名返回空串） */
    public static String extractExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.trim();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        // 仅取最后一段，路径分隔符后的部分无效防护（上传名由 Spring 提供，此处只做解析）
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 完整判定（严格序：白名单 → 魔数 → 深扫上限 → 内容深扫）。
     *
     * @param fileName        原始文件名
     * @param fileSize        文件字节数
     * @param head            文件头样本（建议读 ≥32 字节，供 L2 魔数比对）
     * @param whitelist       白名单扩展名集合（小写不带点；来自租户配置或默认并集）
     * @param deepScanMaxBytes 深扫大小上限（字节；超过仅做 L1/L2）
     * @param deepScan        深扫函数：入参扩展名 → 命中项（接线层按扩展名路由到
     *                        PdfJsScanner/SvgXssScanner/OfficeMacroScanner 并供应全新流）
     * @return 判定结果（非 null）
     */
    public static FileGuardVerdict decide(String fileName, long fileSize, byte[] head,
                                          Set<String> whitelist, long deepScanMaxBytes,
                                          Function<String, Optional<RiskFinding>> deepScan) {
        // L1 扩展名白名单（确定性 → 拒传）
        String ext = extractExtension(fileName);
        if (ext.isEmpty() || whitelist == null || !whitelist.contains(ext)) {
            return FileGuardVerdict.deny("EXTENSION_WHITELIST",
                "文件类型不允许上传" + (ext.isEmpty() ? "（缺少扩展名）" : "：." + ext));
        }

        // L2 魔数校验（确定性 → 拒传；无判据格式跳过）
        if (MagicNumberTable.hasSignature(ext) && !MagicNumberTable.matches(ext, head)) {
            return FileGuardVerdict.deny("MAGIC_MISMATCH", "文件内容与扩展名不符（疑似伪装文件），已拦截");
        }

        // 深扫上限（500MB 时代的内存闸：超限只做 L1/L2）
        if (fileSize > deepScanMaxBytes) {
            return FileGuardVerdict.passSkippedDeepScan();
        }

        // L3 内容深扫（灰度 → 强制进审）
        Optional<RiskFinding> hit = deepScan.apply(ext);
        if (hit.isPresent()) {
            RiskFinding finding = hit.get();
            return FileGuardVerdict.review(finding.rule(), "安全检测：" + finding.message() + "，需人工审核");
        }
        return FileGuardVerdict.pass();
    }
}
