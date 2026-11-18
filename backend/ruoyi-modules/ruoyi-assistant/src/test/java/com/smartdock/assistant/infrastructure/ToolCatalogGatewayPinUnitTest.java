package com.smartdock.assistant.infrastructure;

import com.smartdock.assistant.domain.gateway.ToolCatalogGateway;
import com.smartdock.assistant.domain.gateway.ToolCatalogGateway.McpServerView;
import com.smartdock.assistant.domain.gateway.ToolCatalogGateway.PlatformTool;
import com.smartdock.assistant.domain.gateway.ToolCatalogGateway.TenantGate;
import com.smartdock.assistant.service.ToolViewerService;
import com.smartdock.assistant.legacy.core.ToolRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gateway 实现的单测钉（2026-11-18 结构批 2 前置 P7）：批 1 的两个 infrastructure
 * 实现此前只有「编译过 + 三接口活体一致」，没有单测——批 2 是全仓移动，没有钉子
 * 分不清「真没坏」和「看起来没坏」。本类钉三处行为：
 *
 * <ol>
 *   <li>{@code mcpCallCounts} 空租户短路：不碰账本直接回空 Map（null 依赖构造，
 *       实现若去查库会先 NPE——用 null 当探针）；</li>
 *   <li>{@code platformTools} 映射：与注册表一一对应、字段非空、空闸下生效档=内置门槛、
 *       租户闸 off 覆盖能穿透到 effective；</li>
 *   <li>外部服务行「次数」三态：有账本=真实次数 / 读失败=null→-1（前端显示「—」）/
 *       没调用过=0——经 {@link ToolViewerService#list} 公共入口用假 gateway 钉。</li>
 * </ol>
 */
@Tag("local")
class ToolCatalogGatewayPinUnitTest {

    private static ToolCatalogGatewayImpl implWithNullDeps() {
        // null 依赖是故意的：这三个方法不应碰任何协作对象，碰了就 NPE 自己证伪
        return new ToolCatalogGatewayImpl(null, null, null, null);
    }

    // ── 钉 ① 空租户短路：不查库直接回空 Map ─────────────────────────

    @Test
    void 空租户不查库直接回空Map() {
        var impl = implWithNullDeps();
        assertThat(impl.mcpCallCounts("")).isEmpty();
        assertThat(impl.mcpCallCounts(null)).isEmpty();
        assertThat(impl.mcpCallCounts("   ")).isEmpty();
    }

    // ── 钉 ② platformTools 映射 ─────────────────────────────────────

    @Test
    void 平台工具与注册表一一对应且字段非空() {
        var out = implWithNullDeps().platformTools(Map.of());
        assertThat(out).hasSize(ToolRegistry.SPECS_VIEW().size());
        Map<String, Integer> expected = new HashMap<>();
        for (var spec : ToolRegistry.SPECS_VIEW()) {
            expected.put(spec.toolNames().stream().sorted().findFirst().orElse(""), spec.minRoleLevel());
        }
        for (PlatformTool t : out) {
            assertThat(t.key()).as("key 应为 spec 字典序首名").isIn(expected.keySet());
            assertThat(t.displayName()).isNotBlank();
            assertThat(t.userDesc()).isNotBlank();
            assertThat(t.category()).isNotBlank();
            assertThat(t.riskLevel()).isIn("low", "mid", "high");
            // 空闸（没有任何覆盖）下生效档必须等于内置门槛
            assertThat(t.effective()).as("工具 %s 空闸生效档", t.key()).isEqualTo(expected.get(t.key()));
        }
    }

    @Test
    void 租户闸off能穿透到生效档() {
        String firstName = ToolRegistry.SPECS_VIEW().get(0).toolNames().stream().sorted().findFirst().orElse("");
        var out = implWithNullDeps().platformTools(Map.of(firstName, "off"));
        var row = out.stream().filter(t -> t.key().equals(firstName)).findFirst().orElseThrow();
        assertThat(row.effective()).isEqualTo(-1);
    }

    // ── 钉 ③ 外部服务行「次数」三态（经公共入口） ─────────────────────

    /** 假 gateway：五个方法按字段返回，记录传进来的租户 */
    static class FakeCatalog implements ToolCatalogGateway {
        Map<String, Long> counts = Map.of();
        String askedTenant;

        public TenantGate tenantGate(String tenantId) {
            return new TenantGate(Map.of(), true, false);
        }

        public List<PlatformTool> platformTools(Map<String, String> gate) {
            return List.of();
        }

        public boolean webSearchKeyReady(String tenantId) {
            return true;
        }

        public List<McpServerView> mcpServers() {
            return List.of(new McpServerView("srv_a", "展示名", "备注", "1", "1"));
        }

        public Map<String, Long> mcpCallCounts(String tenantId) {
            this.askedTenant = tenantId;
            return counts;
        }
    }

    private static ToolViewerService.ToolRow mcpRowOf(FakeCatalog fake, String tenantId) {
        var rows = new ToolViewerService(fake).list(tenantId);
        return rows.stream().filter(r -> "mcp:srv_a".equals(r.key())).findFirst().orElseThrow();
    }

    @Test
    void 次数三态_有账本是真实次数() {
        var fake = new FakeCatalog();
        fake.counts = Map.of("srv_a", 5L);
        assertThat(mcpRowOf(fake, "000000").calls()).isEqualTo(5L);
        // service 必须把展示租户原样传给 gateway（错传会把别人的账本当自己的）
        assertThat(fake.askedTenant).isEqualTo("000000");
    }

    @Test
    void 次数三态_读失败是负一前端显示杠() {
        var fake = new FakeCatalog();
        fake.counts = null;
        assertThat(mcpRowOf(fake, "000000").calls()).isEqualTo(-1L);
    }

    @Test
    void 次数三态_没调用过是零() {
        var fake = new FakeCatalog();
        fake.counts = Map.of();
        assertThat(mcpRowOf(fake, "000000").calls()).isEqualTo(0L);
    }
}
