package com.smartdock.fileReview;

import com.smartdock.enums.FileReviewCategory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件审核七类映射单测（12b，合同#7 成文表的验收依据）。
 *
 * @author smartdock
 */
class FileReviewCategoryUnitTest {

    @Test
    @Tag("dev")
    void classifiesSevenCategories() {
        // 每类至少 2 个代表扩展名（含最常见与易漏项）
        assertThat(FileReviewCategory.classify(".jpg")).isEqualTo(FileReviewCategory.IMAGE);
        assertThat(FileReviewCategory.classify(".png")).isEqualTo(FileReviewCategory.IMAGE);
        assertThat(FileReviewCategory.classify(".webp")).isEqualTo(FileReviewCategory.IMAGE);
        assertThat(FileReviewCategory.classify(".mp4")).isEqualTo(FileReviewCategory.VIDEO);
        assertThat(FileReviewCategory.classify(".mov")).isEqualTo(FileReviewCategory.VIDEO);
        assertThat(FileReviewCategory.classify(".mp3")).isEqualTo(FileReviewCategory.AUDIO);
        assertThat(FileReviewCategory.classify(".flac")).isEqualTo(FileReviewCategory.AUDIO);
        assertThat(FileReviewCategory.classify(".docx")).isEqualTo(FileReviewCategory.DOCUMENT);
        assertThat(FileReviewCategory.classify(".xlsx")).isEqualTo(FileReviewCategory.DOCUMENT);
        assertThat(FileReviewCategory.classify(".pdf")).isEqualTo(FileReviewCategory.DOCUMENT);
        assertThat(FileReviewCategory.classify(".md")).isEqualTo(FileReviewCategory.TEXT);
        assertThat(FileReviewCategory.classify(".csv")).isEqualTo(FileReviewCategory.TEXT);
        assertThat(FileReviewCategory.classify(".zip")).isEqualTo(FileReviewCategory.ARCHIVE);
        assertThat(FileReviewCategory.classify(".7z")).isEqualTo(FileReviewCategory.ARCHIVE);
    }

    @Test
    @Tag("dev")
    void classifiesWithoutLeadingDot() {
        // 上传钩子传入的 suffix 含点（SysOssServiceImpl 惯例），但 classify 须两头都兼容
        assertThat(FileReviewCategory.classify("jpeg")).isEqualTo(FileReviewCategory.IMAGE);
        assertThat(FileReviewCategory.classify("PDF")).isEqualTo(FileReviewCategory.DOCUMENT);
    }

    @Test
    @Tag("dev")
    void classifiesCaseInsensitive() {
        // Windows 上传的 .JPG / .PNG 大写扩展名必须归对类，否则勾选"图片"会漏审
        assertThat(FileReviewCategory.classify(".JPG")).isEqualTo(FileReviewCategory.IMAGE);
        assertThat(FileReviewCategory.classify(".PNG")).isEqualTo(FileReviewCategory.IMAGE);
        assertThat(FileReviewCategory.classify(".Mp4")).isEqualTo(FileReviewCategory.VIDEO);
        assertThat(FileReviewCategory.classify(".Zip")).isEqualTo(FileReviewCategory.ARCHIVE);
    }

    @Test
    @Tag("dev")
    void unknownOrEmptyFallsBackToOther() {
        // null/空白/无扩展名/未知扩展 → OTHER 兜底（勾选"其他"才审这类）
        assertThat(FileReviewCategory.classify(null)).isEqualTo(FileReviewCategory.OTHER);
        assertThat(FileReviewCategory.classify("")).isEqualTo(FileReviewCategory.OTHER);
        assertThat(FileReviewCategory.classify("   ")).isEqualTo(FileReviewCategory.OTHER);
        assertThat(FileReviewCategory.classify(".")).isEqualTo(FileReviewCategory.OTHER);
        assertThat(FileReviewCategory.classify(".exe")).isEqualTo(FileReviewCategory.OTHER);
        assertThat(FileReviewCategory.classify(".xyz123")).isEqualTo(FileReviewCategory.OTHER);
        assertThat(FileReviewCategory.classify("无扩展名")).isEqualTo(FileReviewCategory.OTHER);
    }
}
