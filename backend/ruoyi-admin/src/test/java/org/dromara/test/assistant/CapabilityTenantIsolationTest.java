package org.dromara.test.assistant;

import org.dromara.assistant.service.CapabilityService;
import org.dromara.assistant.service.CapabilityService.CapabilityVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 能力目录租户隔离连库测试（P2 修复 Q2，2026-10-09）。
 *
 * <p>判据（六席审查 P2 → P2 拍板 Q2:A）：
 * ①SEED 行（tenant 000000）仍全局共享；
 * ②超管（租户 000000）做的 MANUAL 覆盖行<b>不得</b>经共享 OR 分支泄漏给其它租户——
 * 修复前 findCandidates 谓词 {@code (tenant_id=我 OR tenant_id='000000')} 不过滤 source，
 * 000000 的 MANUAL 会被所有租户当最高优先候选读到（一次租户级操作静默变全平台生效）；
 * ③000000 自己读到自家 MANUAL 属正常（本租户行）。</p>
 *
 * <p>夹具：独占 provider=ZZCAPISO 两条行（J1 同款裸 SQL + @AfterEach 按 provider 整删，
 * 不碰 SEED 真实行）；雪花段 919915... 独占，避开 J1 的 91991x 段与批1 seed 段。</p>
 */
@Tag("p0probe")
@SpringBootTest
class CapabilityTenantIsolationTest {

    private static final String PROBE_PROVIDER = "ZZCAPISO";
    private static final String OTHER_TENANT = "979000";
    private static final String PLATFORM_TENANT = "000000";

    @Autowired
    private CapabilityService capabilityService;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void seedFixtures() throws Exception {
        cleanFixtures();
        exec("INSERT INTO dcs_ai_model_capability (id, tenant_id, provider, model_pattern, tier_kind,"
            + " tier_values, semantic_map, source, rule_order) VALUES ("
            + "9199150000000001, '000000', 'ZZCAPISO', '^zz-seed-.*$', 'TIERS',"
            + " '[\"low\",\"high\",\"max\"]', '{\"FAST\":\"low\",\"BALANCED\":\"high\",\"DEEP\":\"max\"}',"
            + " 'SEED', 50)");
        exec("INSERT INTO dcs_ai_model_capability (id, tenant_id, provider, model_pattern, tier_kind,"
            + " tier_values, semantic_map, source, rule_order) VALUES ("
            + "9199150000000002, '000000', 'ZZCAPISO', '^zz-leak-.*$', 'SWITCH',"
            + " '[\"disabled\",\"enabled\"]', '{\"FAST\":\"disabled\",\"BALANCED\":\"enabled\",\"DEEP\":\"enabled\"}',"
            + " 'MANUAL', 10)");
    }

    @AfterEach
    void cleanFixtures() throws Exception {
        exec("DELETE FROM dcs_ai_model_capability WHERE provider='ZZCAPISO'");
    }

    /** ①SEED 共享不受收紧影响：其它租户仍命中 000000 的 SEED 行 */
    @Test
    void seedRowsStillShared() {
        CapabilityVo vo = capabilityService.matchCapability(OTHER_TENANT, PROBE_PROVIDER, "zz-seed-1");
        assertEquals("SEED", vo.getSource(), "SEED 行必须继续全局共享");
        assertEquals("TIERS", vo.getTierKind());
    }

    /** ②核心判据：000000 的 MANUAL 覆盖不得泄漏给其它租户（修复前=返回 MANUAL，泄漏实锤）。
     * 无命中时落到全局兜底行（provider='*'）属正常——断言只锁「绝不命中泄漏行」。 */
    @Test
    void platformManualOverrideMustNotLeak() {
        CapabilityVo vo = capabilityService.matchCapability(OTHER_TENANT, PROBE_PROVIDER, "zz-leak-1");
        org.junit.jupiter.api.Assertions.assertNotEquals("MANUAL", vo.getSource(),
            "其它租户命中 MANUAL 即 000000 覆盖泄漏（Q2 病灶回归）");
        org.junit.jupiter.api.Assertions.assertFalse(
            String.valueOf(vo.getMatchedPattern()).contains("zz-leak"),
            "匹配到泄漏行 pattern：" + vo.getMatchedPattern());
    }

    /** ③000000 自家 MANUAL 正常生效（本租户行为，非泄漏） */
    @Test
    void platformManualWorksForOwnTenant() {
        CapabilityVo vo = capabilityService.matchCapability(PLATFORM_TENANT, PROBE_PROVIDER, "zz-leak-1");
        assertEquals("MANUAL", vo.getSource());
        assertEquals("disabled", vo.getSemanticMap().get("FAST"));
    }

    private void exec(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
