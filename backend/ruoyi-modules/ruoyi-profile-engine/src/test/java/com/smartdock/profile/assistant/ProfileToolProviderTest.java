package com.smartdock.profile.assistant;

import com.smartdock.assistant.spi.BizToolSpec;
import com.smartdock.profile.domain.ProfileSegment;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 画像助手工具纯函数层直测(画像方向第三批): 权限三态/limit 归一/分群解析/spec 声明。
 * execute 链路(租户/超时/行级脱分)由活体双视角覆盖, 不在此起 Spring。
 */
@Tag("local")
class ProfileToolProviderTest {

    // ---------------------------------------------------------------- 权限三态

    @Test
    void 权限三态_通配与精确串放行() {
        assertThat(ProfileToolProvider.canSee(Set.of("*"), "profile:pii:score")).isTrue();
        assertThat(ProfileToolProvider.canSee(Set.of("*:*:*"), "profile:pii:score")).isTrue();
        assertThat(ProfileToolProvider.canSee(Set.of("profile:pii:score"), "profile:pii:score")).isTrue();
    }

    @Test
    void 权限三态_null与不含拒() {
        assertThat(ProfileToolProvider.canSee(null, "profile:pii:score")).isFalse();
        assertThat(ProfileToolProvider.canSee(Set.of("analytics:center:query"), "profile:pii:score")).isFalse();
        // 前缀不算数——分群查询串不等于分数串
        assertThat(ProfileToolProvider.canSee(Set.of("profile:segment:query"), "profile:pii:score")).isFalse();
    }

    // ---------------------------------------------------------------- limit 归一

    @Test
    void limit归一_空取缺省_越界夹取() {
        assertThat(ProfileToolProvider.clampLimit(null, 5, 20)).isEqualTo(5);
        assertThat(ProfileToolProvider.clampLimit(3, 5, 20)).isEqualTo(3);
        assertThat(ProfileToolProvider.clampLimit(0, 5, 20)).isEqualTo(1);
        assertThat(ProfileToolProvider.clampLimit(999, 5, 20)).isEqualTo(20);
        assertThat(ProfileToolProvider.clampLimit(150, 20, 100)).isEqualTo(100);
    }

    // ---------------------------------------------------------------- 分群解析

    private ProfileSegment seg(long id, String name, String builtin) {
        ProfileSegment s = new ProfileSegment();
        s.setSegmentId(id);
        s.setSegmentName(name);
        s.setBuiltin(builtin);
        s.setSource("dcs");
        return s;
    }

    @Test
    void 分群解析_纯数字走ID精确() {
        List<ProfileSegment> all = List.of(seg(101, "高价值", "1"), seg(102, "沉睡用户", "0"));
        assertThat(ProfileToolProvider.resolveSegment(all, "102").getSegmentName()).isEqualTo("沉睡用户");
        assertThat(ProfileToolProvider.resolveSegment(all, "999")).isNull();
    }

    @Test
    void 分群解析_数字串ID未命中回落名称匹配() {
        // 三审 dspro P3-1: 名字恰好是数字的分群不能被 ID 分支提前拦死
        List<ProfileSegment> all = List.of(seg(1, "2024", "0"), seg(2, "高价值", "1"));
        assertThat(ProfileToolProvider.resolveSegment(all, "2024").getSegmentId()).isEqualTo(1L);
    }

    @Test
    void execute空租户fail_closed人话拒绝() {
        // 三审 dspro P2-1: 空租户直执行=插件不注入租户条件(越权开口), 必须在入口拒绝
        com.smartdock.assistant.spi.BizToolCall call = new com.smartdock.assistant.spi.BizToolCall();
        call.setToolName("profile_query_subject");
        call.setTenantId("  ");
        com.smartdock.assistant.spi.BizToolResult result =
            new ProfileToolProvider(null, null, null, null, null).execute(call);
        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).contains("租户");
    }

    @Test
    void 分群解析_名称精确优先于包含() {
        List<ProfileSegment> all = List.of(seg(1, "沉睡预警", "0"), seg(2, "沉睡", "1"));
        assertThat(ProfileToolProvider.resolveSegment(all, "沉睡").getSegmentId()).isEqualTo(2L);
        assertThat(ProfileToolProvider.resolveSegment(all, "不存在的分群")).isNull();
    }

    @Test
    void 分群解析_包含匹配兜底() {
        List<ProfileSegment> all = List.of(seg(1, "90天未活跃用户", "0"), seg(2, "高价值", "1"));
        assertThat(ProfileToolProvider.resolveSegment(all, "未活跃").getSegmentId()).isEqualTo(1L);
    }

    // ---------------------------------------------------------------- spec 声明(权限门与只读)

    @Test
    void spec声明_四只读工具_权限串与菜单同源() {
        List<BizToolSpec> specs = new ProfileToolProvider(null, null, null, null, null).tools();
        assertThat(specs).hasSize(4);

        BizToolSpec query = specs.stream()
            .filter(s -> ProfileToolProvider.TOOL_QUERY_SUBJECT.equals(s.getName())).findFirst().orElseThrow();
        assertThat(query.isReadOnly()).as("查画像必须只读(不出确认卡)").isTrue();
        assertThat(query.getRequiredPermission()).isEqualTo("profile:score:query");
        assertThat(query.getDisplayName()).isEqualTo("查画像");
        assertThat(query.getParams()).isNotEmpty();

        BizToolSpec segment = specs.stream()
            .filter(s -> ProfileToolProvider.TOOL_SEGMENT_MEMBERS.equals(s.getName())).findFirst().orElseThrow();
        assertThat(segment.isReadOnly()).as("圈人必须只读").isTrue();
        assertThat(segment.getRequiredPermission()).isEqualTo("profile:segment:query");
        assertThat(segment.getDisplayName()).isEqualTo("圈人");
    }

    @Test
    void spec声明_批9迁入两工具_原名保留且补上权限门() {
        List<BizToolSpec> specs = new ProfileToolProvider(null, null, null, null, null).tools();

        BizToolSpec overview = specs.stream()
            .filter(s -> ProfileToolProvider.TOOL_SEGMENT_OVERVIEW.equals(s.getName())).findFirst().orElseThrow();
        assertThat(overview.isReadOnly()).isTrue();
        assertThat(overview.getRequiredPermission()).as("批9 无门迁入后必须补分群权限门").isEqualTo("profile:segment:query");

        BizToolSpec review = specs.stream()
            .filter(s -> ProfileToolProvider.TOOL_REVIEW_OVERVIEW.equals(s.getName())).findFirst().orElseThrow();
        assertThat(review.isReadOnly()).isTrue();
        assertThat(review.getRequiredPermission()).as("批9 无门迁入后必须补复核权限门").isEqualTo("profile:review:query");
    }

    @Test
    void spec声明_工具名带namespace前缀防撞名() {
        for (BizToolSpec spec : new ProfileToolProvider(null, null, null, null, null).tools()) {
            assertThat(spec.getName()).startsWith("profile_");
        }
    }
}
