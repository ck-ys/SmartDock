package org.dromara.assistant.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.jdbc.dialect.vendor.PostgresDialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * 助手会话状态存储装配（批A Task A3，2026-10-03）。
 *
 * <p>P0 T10 实证配方（坑65/#3213）：表由 migration 预建于 agentscope schema，
 * 代码侧 autoCreateTable=false——受控账号（无 DDL）也不会因框架默认
 * CREATE TABLE IF NOT EXISTS 被拒。PostgresDialect 裸表名 DML 靠 search_path
 * 定位，而业务连接池 search_path=public——用 {@link SearchPathDataSource}
 * 包装代理只在借出的连接上 SET search_path，业务连接零侵入。</p>
 *
 * <p>三审回修（glm53 P2-1/dspro P2-2）：归还连接前把 search_path 还原成默认
 * （Hikari 不重置 session 变量，残留的 agentscope 前置路径会泄漏给下一个
 * 业务借用者；今天靠两 schema 零重名撑着，明天谁建同名表就静默改道）。
 * close() 用 JDK 动态代理包装，避免手写 Connection 全量委托。</p>
 */
@Slf4j
@Configuration
public class AssistantStateStoreConfig {

    /** agentscope 状态表所在 schema（migration_2026_09_23 预建） */
    static final String SCHEMA = "agentscope";

    @Bean
    public AgentStateStore dcsAssistantStateStore(DataSource dataSource) {
        JdbcAgentStateStore store = new JdbcAgentStateStore(
            new SearchPathDataSource(dataSource), new PostgresDialect(), false);
        log.info("[dcs-assistant] 会话状态存储已装配：schema={}，autoCreateTable=false", SCHEMA);
        return store;
    }

    /**
     * 代理 DataSource：每次借出连接后执行 SET search_path TO agentscope, public；
     * 连接归还（close）前还原默认 search_path。只给状态存储用，不注册为业务
     * DataSource 候选（@Bean 名唯一、按类型注入处仅此一家）。
     */
    static class SearchPathDataSource implements DataSource {

        private final DataSource delegate;

        SearchPathDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        private Connection withSearchPath(Connection conn) throws SQLException {
            try (var stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO " + SCHEMA + ", public");
            }
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                AssistantStateStoreConfig.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                        try (var stmt = conn.createStatement()) {
                            stmt.execute("SET search_path TO \"$user\", public");
                        } catch (SQLException restoreFailed) {
                            // 还原失败也必须归还连接，不能把池连接吞掉
                        } finally {
                            conn.close();
                        }
                        return null;
                    }
                    try {
                        return method.invoke(conn, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        }

        @Override
        public Connection getConnection() throws SQLException {
            return withSearchPath(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return withSearchPath(delegate.getConnection(username, password));
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
