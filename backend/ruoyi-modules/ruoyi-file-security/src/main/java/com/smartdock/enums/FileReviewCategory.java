package com.smartdock.enums;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 文件审核七类映射（12b，合同#7 成文表——勾选语义的验收依据）
 *
 * <p>分类只看扩展名（一期拍板 Q5：简单映射；magic bytes 防伪装二期再评估——
 * 审核员本来就会打开文件看内容，伪装扩展名骗不过人眼）。未知扩展一律 OTHER 兜底。</p>
 *
 * <p>映射表（小写）：
 * IMAGE=jpg,jpeg,png,gif,bmp,webp,svg,ico｜VIDEO=mp4,avi,mov,wmv,flv,mkv,m4v｜
 * AUDIO=mp3,wav,aac,flac,ogg,wma｜DOCUMENT=doc,docx,xls,xlsx,ppt,pptx,pdf,rtf｜
 * TEXT=txt,md,log,json,xml,csv｜ARCHIVE=zip,rar,7z,tar,gz｜其余=OTHER</p>
 *
 * @author smartdock
 */
public enum FileReviewCategory {

    /** 图片 */
    IMAGE("图片"),
    /** 视频 */
    VIDEO("视频"),
    /** 音频 */
    AUDIO("音频"),
    /** 文档 */
    DOCUMENT("文档"),
    /** 文本 */
    TEXT("文本"),
    /** 压缩包 */
    ARCHIVE("压缩包"),
    /** 其他（未知扩展兜底） */
    OTHER("其他");

    private final String label;

    FileReviewCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 扩展名 → 类别静态映射（build 一次只读） */
    private static final Map<String, FileReviewCategory> EXT_MAP = buildExtMap();

    private static Map<String, FileReviewCategory> buildExtMap() {
        Map<String, FileReviewCategory> m = new HashMap<>();
        put(m, IMAGE, "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico");
        put(m, VIDEO, "mp4", "avi", "mov", "wmv", "flv", "mkv", "m4v");
        put(m, AUDIO, "mp3", "wav", "aac", "flac", "ogg", "wma");
        put(m, DOCUMENT, "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "rtf");
        put(m, TEXT, "txt", "md", "log", "json", "xml", "csv");
        put(m, ARCHIVE, "zip", "rar", "7z", "tar", "gz");
        return Map.copyOf(m);
    }

    private static void put(Map<String, FileReviewCategory> m, FileReviewCategory cat, String... exts) {
        for (String ext : exts) {
            m.put(ext, cat);
        }
    }

    /**
     * 按扩展名归类（纯函数，供单测）。
     *
     * @param suffix 扩展名（可带可不带点，大小写不敏感；null/空 → OTHER）
     * @return 七类之一，未知一律 OTHER
     */
    public static FileReviewCategory classify(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return OTHER;
        }
        String ext = suffix.trim().toLowerCase(Locale.ROOT);
        if (ext.startsWith(".")) {
            ext = ext.substring(1);
        }
        return EXT_MAP.getOrDefault(ext, OTHER);
    }

    /**
     * 白名单扩展名全集（小写不带点）。
     * file-guard 批（2026-09-18）L1 默认白名单的单一事实源——扩展名收进哪类
     * 就自动进白名单（html 不在映射表内，天然被剔除，grill Q4=A 拍板口径）。
     */
    public static java.util.Set<String> whitelistedExtensions() {
        return EXT_MAP.keySet();
    }
}
