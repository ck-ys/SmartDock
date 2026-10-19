package com.smartdock.profile.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdock.profile.engine.DimensionEvaluator.AggConfig;
import com.smartdock.profile.operator.MetricSpec;
import com.smartdock.profile.operator.ScoreSpec;
import com.smartdock.profile.operator.WindowSpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * calc_config 配置模型 + safeKey 白名单单测(V1)。
 *
 * <p>用原生 Jackson 反序列化(与运行时 JsonUtils 同一底层), 不依赖 Spring 上下文。</p>
 *
 * @author smartdock
 */
@Tag("local")
class AggConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 内置 completion 维度种子配置的往返: 必须可解析且默认值正确。
     */
    @Test
    void parsesBuiltinCompletionConfig() throws Exception {
        String json = """
            {"metric":{"operator":"RATE",
                       "numerator":{"eventType":"data.audit_passed"},
                       "denominator":{"eventType":"task.published"}},
             "window":{"type":"all"},
             "score":{"type":"linear","lo":0,"hi":1,"direction":"higher"},
             "missing":"neutral"}
            """;
        AggConfig cfg = MAPPER.readValue(json, AggConfig.class);
        assertThat(cfg.getMetric().getOperator()).isEqualTo("RATE");
        assertThat(cfg.getMetric().getNumerator().getEventType()).isEqualTo("data.audit_passed");
        assertThat(cfg.getMetric().getDenominator().getEventType()).isEqualTo("task.published");
        assertThat(cfg.getWindow().isRolling()).isFalse();
        assertThat(cfg.getScore().getDirection()).isEqualTo("higher");
        assertThat(cfg.getScore().getLo()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cfg.getScore().getHi()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(cfg.missingPolicy().name()).isEqualTo("NEUTRAL");
    }

    /**
     * 极简配置(只给 operator+target): 其余字段吃默认值, 不炸。
     */
    @Test
    void parsesMinimalConfigWithDefaults() throws Exception {
        String json = """
            {"metric":{"operator":"COUNT","target":{"eventType":"file.uploaded"}}}
            """;
        AggConfig cfg = MAPPER.readValue(json, AggConfig.class);
        assertThat(cfg.getScore().getType()).isEqualTo("linear");
        assertThat(cfg.getWindow().getType()).isEqualTo("all");
        assertThat(cfg.missingPolicy()).isEqualTo(com.smartdock.profile.operator.MissingPolicy.NEUTRAL);
    }

    /**
     * rolling 窗口 + bucket 打分(negative 口径)解析。
     */
    @Test
    void parsesRollingBucketConfig() throws Exception {
        String json = """
            {"metric":{"operator":"COUNT","target":{"eventType":"data.audit_rejected"}},
             "window":{"type":"rolling","days":30},
             "score":{"type":"bucket","buckets":[{"lte":0,"score":100},{"lte":2,"score":70},
                                                {"lte":5,"score":40},{"score":0}]},
             "missing":"neutral"}
            """;
        AggConfig cfg = MAPPER.readValue(json, AggConfig.class);
        assertThat(cfg.getWindow().isRolling()).isTrue();
        assertThat(cfg.getWindow().getDays()).isEqualTo(30);
        List<ScoreSpec.Bucket> buckets = cfg.getScore().getBuckets();
        assertThat(buckets).hasSize(4);
        assertThat(buckets.get(3).getLte()).isNull(); // 兜底档
        assertThat(buckets.get(0).getScore()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    /**
     * props 等值过滤键解析。
     */
    @Test
    void parsesPropFilter() throws Exception {
        String json = """
            {"metric":{"operator":"COUNT","target":{"eventType":"chat.messaged","propFilter":{"channelType":"group"}}}}
            """;
        AggConfig cfg = MAPPER.readValue(json, AggConfig.class);
        Map<String, String> filter = cfg.getMetric().getTarget().getPropFilter();
        assertThat(filter).containsEntry("channelType", "group");
    }

    /**
     * safeKey 白名单: 防注入铁律——非 [A-Za-z0-9_] 键一律拒绝。
     */
    @Test
    void safeKeyRejectsInjectionPatterns() {
        assertThat(DimensionEvaluator.safeKey("responseHours")).isEqualTo("responseHours");
        assertThat(DimensionEvaluator.safeKey("a")).isEqualTo("a");
        assertThatThrownBy(() -> DimensionEvaluator.safeKey("k'; DROP TABLE profile_event;--"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DimensionEvaluator.safeKey("bad-key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DimensionEvaluator.safeKey("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DimensionEvaluator.safeKey(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DimensionEvaluator.safeKey("1abc")).isInstanceOf(IllegalArgumentException.class);
    }
}
