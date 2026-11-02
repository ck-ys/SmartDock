package org.dromara.assistant.job;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「问数据」批·题6② 口径钉死: 已交判定 / 幂等键 / 截止倒计时人话 / 名单截断。
 * 纯函数层——定时任务与租户遍历的集成行为靠活体验收（手动触发一枪看 dcs_notice 落行）。
 */
@Tag("local")
class AssistantDailyDigestJobTest {

    private static final ZoneId TZ = ZoneId.of("Asia/Shanghai");

    private static Date date(int y, int m, int d) {
        return Date.from(LocalDate.of(y, m, d).atStartOfDay(TZ).toInstant());
    }

    @Test
    void countsAsSubmittedOnlyPendingOrApproved() {
        assertThat(AssistantDailyDigestJob.countsAsSubmitted("PENDING_AUDIT")).isTrue();
        assertThat(AssistantDailyDigestJob.countsAsSubmitted("APPROVED")).isTrue();
        // 被退回待重提=没交; 从未提交=没交
        assertThat(AssistantDailyDigestJob.countsAsSubmitted("REJECTED")).isFalse();
        assertThat(AssistantDailyDigestJob.countsAsSubmitted(null)).isFalse();
    }

    @Test
    void dedupKeyPerTenantPerUserPerDay() {
        String key = AssistantDailyDigestJob.dedupKey("000000", 1L, LocalDate.of(2026, 11, 3));
        assertThat(key).isEqualTo("digest:000000:1:20261103");
        // 不同日不同键（次日还会发）; 同日同键（锁漂移重跑不重复落通知）
        assertThat(AssistantDailyDigestJob.dedupKey("000000", 1L, LocalDate.of(2026, 11, 4)))
            .isNotEqualTo(key);
        assertThat(AssistantDailyDigestJob.dedupKey("000000", 1L, LocalDate.of(2026, 11, 3)))
            .isEqualTo(key);
    }

    @Test
    void deadlineTextCoversAllFourStates() {
        LocalDate today = LocalDate.of(2026, 11, 3);
        assertThat(AssistantDailyDigestJob.deadlineText(null, today)).isEqualTo("未设截止时间");
        assertThat(AssistantDailyDigestJob.deadlineText(date(2026, 11, 3), today)).isEqualTo("今天截止");
        assertThat(AssistantDailyDigestJob.deadlineText(date(2026, 11, 6), today)).isEqualTo("离截止还有 3 天");
        assertThat(AssistantDailyDigestJob.deadlineText(date(2026, 10, 31), today)).isEqualTo("已过期 3 天");
    }

    @Test
    void describeTaskListsUpToFiveNamesThenEtAl() {
        LocalDate today = LocalDate.of(2026, 11, 3);
        String few = AssistantDailyDigestJob.describeTask("宿舍填报", date(2026, 11, 5), today,
            2, List.of("张三", "李四"));
        assertThat(few).isEqualTo("「宿舍填报」还有 2 人未交（张三、李四），离截止还有 2 天");

        List<String> many = List.of("a", "b", "c", "d", "e", "f", "g");
        String more = AssistantDailyDigestJob.describeTask("宿舍填报", date(2026, 11, 5), today, 7, many);
        assertThat(more).isEqualTo("「宿舍填报」还有 7 人未交（a、b、c、d、e 等 7 人），离截止还有 2 天");

        // 审查席 P2：名字查不全（用户被删）时「等 N 人」用未交总数，不跟名字条数走
        String sparse = AssistantDailyDigestJob.describeTask("宿舍填报", date(2026, 11, 5), today, 9, List.of("a", "b", "c"));
        assertThat(sparse).isEqualTo("「宿舍填报」还有 9 人未交（a、b、c 等 9 人），离截止还有 2 天");

        String none = AssistantDailyDigestJob.describeTask("匿名任务", null, today, 3, List.of());
        assertThat(none).isEqualTo("「匿名任务」还有 3 人未交，未设截止时间");
    }
}
