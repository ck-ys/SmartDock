package com.smartdock.dcs.domain;

import com.smartdock.dcs.support.DcsJsons;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体便捷语义守卫: jsonb 助手 / 审核人判定 / 响应时效锚点。
 *
 * @author smartdock
 */
@Tag("local")
class DcsTaskTest {

    @Test
    void jsonListHelpersTolerant() {
        DcsTask t = new DcsTask();
        t.setAssigneeUserIds(DcsJsons.toJson(java.util.List.of(1L, 2L)));
        assertThat(t.assigneeList()).containsExactly(1L, 2L);
        assertThat(t.assignedTo(2L)).isTrue();
        assertThat(t.assignedTo(3L)).isFalse();
        // 坏 JSON 容错为空表, 不炸读链
        t.setAssigneeUserIds("not-json");
        assertThat(t.assigneeList()).isEmpty();
    }

    @Test
    void auditorModeSemantics() {
        DcsTask t = new DcsTask();
        t.setPublisherUserId(9L);
        t.setAuditMode(DcsTask.AUDIT_PUBLISHER);
        assertThat(t.auditorIs(9L)).isTrue();
        assertThat(t.auditorIs(8L)).isFalse();

        t.setAuditMode(DcsTask.AUDIT_ASSIGNED);
        t.setAuditorUserIds(DcsJsons.toJson(java.util.List.of(8L)));
        assertThat(t.auditorIs(8L)).isTrue();
        // 指定审核人模式下发布人不再是审核人
        assertThat(t.auditorIs(9L)).isFalse();
    }

    @Test
    void hoursSincePublishAnchor() {
        DcsTask t = new DcsTask();
        t.setPublishTime(new Date(System.currentTimeMillis() - 7_200_000L)); // 2h 前
        java.math.BigDecimal h = t.hoursSincePublish(new Date());
        assertThat(h).isEqualByComparingTo("2.00");
        // 未发布返回 null
        t.setPublishTime(null);
        assertThat(t.hoursSincePublish(new Date())).isNull();
    }
}
