package com.mcpdev.tooling.integration;

import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.mcpdev.tooling.config.DatabaseProperties;
import com.mcpdev.tooling.integration.support.PostgresFixture;
import com.mcpdev.tooling.registry.ToolExecutionException;
import com.mcpdev.tooling.registry.ToolResult;
import com.mcpdev.tooling.tools.db.DbClient;
import com.mcpdev.tooling.tools.db.DescribeTableTool;
import com.mcpdev.tooling.tools.db.ListTablesTool;
import com.mcpdev.tooling.tools.db.RunQueryTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests against a real PostgreSQL container.
 *
 * <p>These exist for the claims unit tests with mocks cannot make:
 * <ul>
 *   <li>the read-only ROLE actually refuses writes (defense in depth behind the parser)</li>
 *   <li>statement timeout, row cap and truncation are enforced by the driver</li>
 *   <li>grants, not metadata visibility, decide what can be read</li>
 *   <li>the pool returns every connection under sequential and concurrent use</li>
 * </ul>
 */
class PostgresIntegrationIT {

    private DbClient client;
    private RunQueryTool runQuery;
    private ListTablesTool listTables;
    private DescribeTableTool describeTable;

    @BeforeEach
    void setUp() {
        client = new DbClient(PostgresFixture.readOnlyProperties());
        runQuery = new RunQueryTool(client);
        listTables = new ListTablesTool(client);
        describeTable = new DescribeTableTool(client);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private static DatabaseProperties propsWithTimeout(int seconds) {
        DatabaseProperties base = PostgresFixture.readOnlyProperties();
        return new DatabaseProperties(base.url(), base.username(), base.password(),
                base.maxPoolSize(), seconds, base.maxRows(),
                base.maxRenderRows(), base.maxCellLength());
    }

    private long userCount() {
        ToolResult result = runQuery.execute(new RunQueryTool.Args(
                "SELECT count(*) AS n FROM app.users"));
        assertThat(result.isError()).isFalse();
        @SuppressWarnings("unchecked")
        List<List<String>> rows = (List<List<String>>) ((Map<String, Object>) result.structured())
                .get("rows");
        return Long.parseLong(rows.get(0).get(0));
    }

    @Test
    @DisplayName("run_query reads real rows and returns structured content")
    void readsRealRows() {
        ToolResult result = runQuery.execute(new RunQueryTool.Args(
                "SELECT id, email FROM app.users WHERE id <= 2 ORDER BY id"));

        assertThat(result.isError()).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structured();
        assertThat(structured.get("columns")).isEqualTo(List.of("id", "email"));
        assertThat(structured.get("rowCount")).isEqualTo(2);
        assertThat(structured.get("truncated")).isEqualTo(false);
        assertThat(result.text()).contains("user1@example.com");
    }

    @Test
    @DisplayName("row cap truncates at maxRows and says so")
    void rowCapTruncates() {
        ToolResult result = runQuery.execute(new RunQueryTool.Args(
                "SELECT * FROM app.users ORDER BY id"));

        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structured();
        assertThat(structured.get("rowCount")).isEqualTo(5);
        assertThat(structured.get("truncated")).isEqualTo(true);
        assertThat(result.text()).contains("limited to 5 rows");
    }

    @Test
    @DisplayName("the pool is read-only: PostgreSQL reports transaction_read_only=on")
    void sessionIsReadOnly() {
        ToolResult result = runQuery.execute(new RunQueryTool.Args(
                "SELECT current_setting('transaction_read_only') AS ro"));

        @SuppressWarnings("unchecked")
        List<List<String>> rows = (List<List<String>>) ((Map<String, Object>) result.structured())
                .get("rows");
        assertThat(rows.get(0).get(0)).isEqualTo("on");
    }

    @Test
    @DisplayName("statement timeout is enforced by the driver, not by a sleeping thread")
    void statementTimeout() {
        DbClient shortTimeout = new DbClient(propsWithTimeout(1));
        try {
            RunQueryTool tool = new RunQueryTool(shortTimeout);
            long start = System.nanoTime();

            assertThatThrownBy(() -> tool.execute(new RunQueryTool.Args("SELECT pg_sleep(10)")))
                    .isInstanceOf(ToolExecutionException.class)
                    .hasMessageContaining("timed out after 1s");

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).isLessThan(5_000);
        } finally {
            shortTimeout.close();
        }
    }

    @Test
    @DisplayName("defense in depth: the read-only role refuses writes even with the parser bypassed")
    void writesImpossibleWithoutTheParser() throws Exception {
        long before = userCount();

        assertThatThrownBy(() -> client.withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate("DELETE FROM app.users");
            }
        })).isInstanceOf(ToolExecutionException.class);

        assertThatThrownBy(() -> client.withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(
                        "INSERT INTO app.users (id, email) VALUES (999, 'intruder@example.com')");
            }
        })).isInstanceOf(ToolExecutionException.class);

        assertThatThrownBy(() -> client.withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.execute("CREATE TABLE app.pwned (id int)");
            }
        })).isInstanceOf(ToolExecutionException.class);

        assertThat(userCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("grants decide access: metadata shows the schema, SELECT is refused (42501)")
    void grantsAreTheBoundary() {
        ToolResult tables = listTables.execute(new ListTablesTool.Args(
                Optional.empty(), Optional.empty()));
        assertThat(tables.text()).contains("secret.api_keys");

        assertThatThrownBy(() -> runQuery.execute(new RunQueryTool.Args(
                "SELECT * FROM secret.api_keys")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("42501")
                .hasMessageNotContaining("sk-live");
    }

    @Test
    @DisplayName("metadata tools: real schema described, system schemas hidden")
    void describesSchema() {
        ToolResult tables = listTables.execute(new ListTablesTool.Args(
                Optional.empty(), Optional.empty()));
        assertThat(tables.text())
                .contains("app.users")
                .contains("app.orders")
                .doesNotContain("pg_catalog")
                .doesNotContain("information_schema");

        ToolResult described = describeTable.execute(
                new DescribeTableTool.Args("orders", Optional.of("app")));
        assertThat(described.text())
                .contains("primary key: id")
                .contains("user_id")
                .contains("users.id")
                .contains("total");
    }

    @Test
    @DisplayName("metadata filters are parameters, not SQL: injection strings are inert")
    void metadataInjectionIsInert() {
        ToolResult byPattern = listTables.execute(new ListTablesTool.Args(
                Optional.empty(), Optional.of("x' OR '1'='1")));
        assertThat(byPattern.text()).startsWith("no tables found");

        ToolResult bySchema = listTables.execute(new ListTablesTool.Args(
                Optional.of("app'; DROP TABLE app.users; --"), Optional.empty()));
        assertThat(bySchema.text()).startsWith("no tables found");

        assertThat(userCount()).isEqualTo(20); // still there
    }

    @Test
    @DisplayName("pool size 2 survives 40 sequential queries: no connection leak")
    void sequentialUseDoesNotLeakConnections() {
        DatabaseProperties small = new DatabaseProperties(
                PostgresFixture.readOnlyProperties().url(),
                PostgresFixture.readOnlyProperties().username(),
                PostgresFixture.readOnlyProperties().password(),
                2, 3, 5, 50, 120);
        DbClient pool = new DbClient(small);
        try {
            RunQueryTool tool = new RunQueryTool(pool);
            for (int i = 0; i < 40; i++) {
                assertThat(tool.execute(new RunQueryTool.Args("SELECT " + i + " AS n"))
                        .isError()).isFalse();
            }
            assertThat(pool.stats().inUse()).isZero();
        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("concurrent queries stay inside the pool and release every permit")
    void concurrentUseDoesNotLeakConnections() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(10);
        try {
            List<Callable<Boolean>> jobs = new java.util.ArrayList<>();
            for (int i = 0; i < 30; i++) {
                int n = i;
                jobs.add(() -> !runQuery.execute(
                        new RunQueryTool.Args("SELECT " + n + " AS n")).isError());
            }
            for (Future<Boolean> future : workers.invokeAll(jobs)) {
                assertThat(future.get(20, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(client.stats().inUse()).isZero();
        } finally {
            workers.shutdownNow();
        }
    }
}
