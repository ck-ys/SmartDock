package org.dromara.web.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.encrypt.core.EncryptContext;
import org.dromara.common.encrypt.core.EncryptorManager;
import org.dromara.common.encrypt.enumd.AlgorithmType;
import org.dromara.common.encrypt.enumd.EncodeType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 历史密文自动换锁（2026-11-02 密钥整改）。
 *
 * <p>旧版把密钥写死在源码里，库里已经有一批用旧钥匙加密的 {@code ENC_} 行。换新密钥后
 * 这些行必须重新加密，否则 AI 配置/MCP 请求头全部读不出来。</p>
 *
 * <p>做法：启动时扫三张表，逐行“先试旧钥匙、再试新钥匙”——旧钥匙能解 → 用新钥匙重新加密
 * 写回；新钥匙能解 → 说明已迁过，跳过；两边都解不开或解出来不像正常值 → 跳过并告警，
 * 绝不写坏数据。旧钥匙只在这里用于解密历史数据，永远不用于新写入。</p>
 *
 * <p>注意：必须走原生 SQL（JdbcTemplate）绕开 MyBatis 加解密拦截器，否则读出来已是明文、
 * 写回去又会被二次加密。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class EncryptKeyMigrationRunner implements ApplicationRunner {

    /** 旧版 AI 配置密钥（只读历史密文用） */
    static final String LEGACY_AI_KEY = "DcsAiCfgEncKey16";
    /** 旧版 MCP 请求头密钥（只读历史密文用） */
    static final String LEGACY_MCP_KEY = "DcsMcpHeadersK16";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Environment environment;
    private final DataSource dataSource;
    private final ObjectProvider<EncryptorManager> encryptorManagerProvider;

    public EncryptKeyMigrationRunner(Environment environment, DataSource dataSource,
                                     ObjectProvider<EncryptorManager> encryptorManagerProvider) {
        this.environment = environment;
        this.dataSource = dataSource;
        this.encryptorManagerProvider = encryptorManagerProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        Boolean enabled = environment.getProperty("smartdock.encrypt.migration-enabled", Boolean.class, Boolean.TRUE);
        if (!Boolean.TRUE.equals(enabled)) {
            log.info("[密钥迁移] 已关闭（smartdock.encrypt.migration-enabled=false），跳过历史密文换锁");
            return;
        }
        EncryptorManager manager = encryptorManagerProvider.getIfAvailable();
        if (manager == null) {
            // 启动自检已挡；这里防御性退出
            return;
        }
        String aiKey = environment.getProperty(EncryptKeyStartupRunner.AI_KEY_PROP, "");
        String mcpKey = environment.getProperty(EncryptKeyStartupRunner.MCP_KEY_PROP, "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        migrate(jdbc, manager, new Target("dcs_ai_config", "config_id", "api_key", aiKey, LEGACY_AI_KEY, ValueKind.API_KEY));
        migrate(jdbc, manager, new Target("dcs_ai_provider_access", "id", "api_key", aiKey, LEGACY_AI_KEY, ValueKind.API_KEY));
        migrate(jdbc, manager, new Target("dcs_mcp_server", "id", "headers", mcpKey, LEGACY_MCP_KEY, ValueKind.HEADERS_JSON));
    }

    private void migrate(JdbcTemplate jdbc, EncryptorManager manager, Target target) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("select " + target.idColumn + " as pk, " + target.column + " as v from "
                + target.table + " where starts_with(" + target.column + ", 'ENC_')");
        } catch (Exception e) {
            log.warn("[密钥迁移] 跳过 {}（查询失败）：{}", target.table, e.getMessage());
            return;
        }
        int migrated = 0, alreadyCurrent = 0, skipped = 0;
        for (Map<String, Object> row : rows) {
            Object id = row.get("pk");
            String cipher = row.get("v") == null ? null : String.valueOf(row.get("v"));
            if (cipher == null || cipher.isBlank()) {
                continue;
            }
            // 1) 旧钥匙能解 → 换新锁
            String plain = tryDecrypt(manager, cipher, target.legacyKey, target.kind);
            if (plain != null) {
                if (plain.startsWith("ENC_")) {
                    skipped++;
                    log.warn("[密钥迁移] {}.id={} 明文本身以 ENC_ 开头，跳过以免误存明文", target.table, id);
                    continue;
                }
                String reEncrypted = manager.encrypt(plain, context(target.currentKey));
                jdbc.update("update " + target.table + " set " + target.column + " = ? where " + target.idColumn + " = ?",
                    reEncrypted, id);
                migrated++;
                continue;
            }
            // 2) 新钥匙能解 → 已经迁过
            if (tryDecrypt(manager, cipher, target.currentKey, target.kind) != null) {
                alreadyCurrent++;
                continue;
            }
            // 3) 两边都解不开 → 不猜、不写，留给人看
            skipped++;
            log.warn("[密钥迁移] {}.id={} 新旧钥匙都解不开，已跳过（请人工检查该行）", target.table, id);
        }
        if (migrated > 0 || skipped > 0) {
            log.info("[密钥迁移] {}：换锁 {} 行，已是最新 {} 行，跳过 {} 行",
                target.table, migrated, alreadyCurrent, skipped);
        } else {
            log.info("[密钥迁移] {}：无历史密文（{} 行已是最新）", target.table, alreadyCurrent);
        }
        encryptPlaintextRows(jdbc, manager, target);
    }

    /**
     * 兜底：历史遗留的明文行（早期版本或手写 SQL 造成）也一并加密。
     * 只加密“看起来正常”的值，异常值跳过并告警，绝不写坏。
     */
    private void encryptPlaintextRows(JdbcTemplate jdbc, EncryptorManager manager, Target target) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("select " + target.idColumn + " as pk, " + target.column + " as v from "
                + target.table + " where " + target.column + " is not null and " + target.column + " <> ''"
                + " and not starts_with(" + target.column + ", 'ENC_')");
        } catch (Exception e) {
            log.warn("[密钥迁移] 跳过 {} 的明文兜底（查询失败）：{}", target.table, e.getMessage());
            return;
        }
        int encrypted = 0, skipped = 0;
        for (Map<String, Object> row : rows) {
            Object id = row.get("pk");
            String plain = row.get("v") == null ? null : String.valueOf(row.get("v"));
            if (!isValid(plain, target.kind)) {
                skipped++;
                log.warn("[密钥迁移] {}.{}={} 明文值形状异常，跳过（请人工检查）", target.table, target.idColumn, id);
                continue;
            }
            jdbc.update("update " + target.table + " set " + target.column + " = ? where " + target.idColumn + " = ?",
                manager.encrypt(plain, context(target.currentKey)), id);
            encrypted++;
        }
        if (encrypted > 0 || skipped > 0) {
            log.info("[密钥迁移] {}：补加密历史明文 {} 行，跳过 {} 行", target.table, encrypted, skipped);
        }
    }

    /**
     * 解密并校验：解不开返回 null；解出来不像正常值也返回 null（防止错误钥匙的极小概率误判）。
     */
    private String tryDecrypt(EncryptorManager manager, String cipher, String key, ValueKind kind) {
        try {
            String plain = manager.decrypt(cipher, context(key));
            return isValid(plain, kind) ? plain : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isValid(String plain, ValueKind kind) {
        if (plain == null || plain.isBlank() || plain.length() > 4096) {
            return false;
        }
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        if (kind == ValueKind.HEADERS_JSON) {
            try {
                JsonNode node = JSON.readTree(plain);
                return node != null && node.isObject();
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private static EncryptContext context(String key) {
        EncryptContext ctx = new EncryptContext();
        ctx.setAlgorithm(AlgorithmType.AES);
        ctx.setEncode(EncodeType.BASE64);
        ctx.setPassword(key);
        return ctx;
    }

    private record Target(String table, String idColumn, String column, String currentKey, String legacyKey,
                          ValueKind kind) {
    }

    public enum ValueKind {
        API_KEY, HEADERS_JSON
    }
}
