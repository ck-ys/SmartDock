package com.smartdock.assistant.support;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 助手记忆表结构启动自检（批 7 前置 D1 多审 P1，2026-11-20；二轮席3 加固）。
 *
 * <p>背景：D1 并发栅栏靠库层部分唯一索引 {@code uk_dcs_asst_memory_active_topic} 兜底——
 * 索引不存在时保存口的撞索引重试永远不会触发，并发同主题保存会退回「两条 ACTIVE」的原病，
 * 且全程无任何报错可追（漏跑迁移 = 整个修复静默失效）。本仓没有 Flyway/Liquibase，
 * migration 靠手工执行，所以照 {@code EncryptKeyStartupRunner} 的姿势在启动阶段 fail-fast。</p>
 *
 * <p>二轮加固（席3）：① 校验「有效且唯一」的真索引——join {@code pg_index} 断言
 * {@code indisvalid AND indisunique} 并带表名，防止 CONCURRENTLY 失败残骸/同名异表蒙混
 * （名字级校验会假通过）；② 同款待遇补上 pref 表 (tenant_id,user_id) 唯一键自检——
 * 缺了它记忆开关的 UPSERT 会 42P10/500，只是发作时机推迟到用户第一次点开关。</p>
 */
@Slf4j
@Component
public class AssistantMemoryFenceStartupCheck {

    static final String FENCE_INDEX = "uk_dcs_asst_memory_active_topic";
    static final String FENCE_MIGRATION_FILE = "backend/script/sql/migration/2026-11-20_memory_active_topic_unique.sql";
    static final String PREF_MIGRATION_FILE = "backend/script/sql/migration/2026-11-20_memory_pref_constraints.sql";

    private final JdbcTemplate jdbcTemplate;

    public AssistantMemoryFenceStartupCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void check() {
        checkFenceIndex();
        checkPrefUnique();
        log.info("[记忆栅栏自检] 通过：唯一索引 {} 与 pref 唯一键均已就位", FENCE_INDEX);
    }

    /** 同主题栅栏：必须是 dcs_assistant_memory 上有效且唯一的真索引（名字级不算数） */
    private void checkFenceIndex() {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes i"
                + " JOIN pg_index x ON x.indexrelid = (i.schemaname || '.' || i.indexname)::regclass"
                + " WHERE i.schemaname='public' AND i.tablename='dcs_assistant_memory'"
                + " AND i.indexname='" + FENCE_INDEX + "' AND x.indisvalid AND x.indisunique",
            Integer.class);
        if (n == null || n == 0) {
            throw new IllegalStateException("[记忆栅栏自检] 缺少有效唯一索引 " + FENCE_INDEX
                + "：同主题并发保存会留下两条生效中记忆且无任何报错（批 7 前置 D1）。"
                + "请先执行 " + FENCE_MIGRATION_FILE + " 再启动（低峰/停写；psql 直接执行，禁止 -1/--single-transaction）。");
        }
    }

    /** pref 唯一键：记忆开关 UPSERT 的 ON CONFLICT (tenant_id,user_id) 靠它仲裁，缺了 42P10/500 */
    private void checkPrefUnique() {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes i"
                + " JOIN pg_index x ON x.indexrelid = (i.schemaname || '.' || i.indexname)::regclass"
                + " WHERE i.schemaname='public' AND i.tablename='dcs_assistant_memory_pref'"
                + " AND x.indisvalid AND x.indisunique"
                + " AND i.indexdef LIKE '%(tenant_id, user_id)%'",
            Integer.class);
        if (n == null || n == 0) {
            throw new IllegalStateException("[助手表自检] dcs_assistant_memory_pref 缺少 (tenant_id, user_id)"
                + " 有效唯一索引：记忆开关的 UPSERT 会 42P10/500。请先执行 " + PREF_MIGRATION_FILE + " 再启动。");
        }
    }
}
