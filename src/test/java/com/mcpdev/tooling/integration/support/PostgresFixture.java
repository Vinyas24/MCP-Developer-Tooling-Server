package com.mcpdev.tooling.integration.support;

import java.time.Duration;

import com.mcpdev.tooling.config.DatabaseProperties;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One PostgreSQL container per test JVM, shared by every IT class.
 *
 * <p>Singleton rather than {@code @Container} per class: starting Postgres costs
 * seconds, and the schema is read-only for the server under test, so isolation
 * between classes is safe. Ryuk removes the container if the JVM dies.
 *
 * <p>The server always connects as {@code mcp_ro} — a role with SELECT on
 * {@code app} only. That role, not the SQL parser, is the authoritative write
 * boundary; tests below rely on it being genuinely restricted.
 */
public final class PostgresFixture {

    static final String DATABASE = "mcp_test";
    static final String READ_ONLY_USER = "mcp_ro";
    static final String READ_ONLY_PASSWORD = "ropass";

    private static PostgreSQLContainer<?> container;

    private PostgresFixture() {
    }

    public static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            PostgreSQLContainer<?> started = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName(DATABASE)
                    .withUsername("test")
                    .withPassword("test")
                    .withInitScript("db/init.sql");
            started.start();
            container = started;
            Runtime.getRuntime().addShutdownHook(new Thread(started::stop, "pg-fixture-stop"));
        }
        return container;
    }

    /** Production-shaped properties: small pool, short statement timeout, 5-row cap. */
    public static DatabaseProperties readOnlyProperties() {
        return new DatabaseProperties(
                container().getJdbcUrl(), READ_ONLY_USER, READ_ONLY_PASSWORD,
                3, 3, 5, 50, 120);
    }

    /** Environment for a spawned server process. */
    public static void applyServerEnv(java.util.Map<String, String> env) {
        env.put("MCP_DB_URL", container().getJdbcUrl());
        env.put("MCP_DB_USER", READ_ONLY_USER);
        env.put("MCP_DB_PASSWORD", READ_ONLY_PASSWORD);
        env.put("MCP_DB_MAX_POOL_SIZE", "3");
    }

    public static Duration startupBudget() {
        return Duration.ofSeconds(60);
    }
}
