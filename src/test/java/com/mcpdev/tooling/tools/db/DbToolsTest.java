package com.mcpdev.tooling.tools.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import com.mcpdev.tooling.config.DatabaseProperties;
import com.mcpdev.tooling.registry.ToolExecutionException;
import com.mcpdev.tooling.registry.ToolInputException;
import com.mcpdev.tooling.registry.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JDBC is mocked with Mockito; real-database behavior is verified manually/Phase 7
 * (Testcontainers). Focus here: validator-before-execution ordering, caps, mapping.
 */
class DbToolsTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private DbClient client;
    private RunQueryTool runQuery;
    private ListTablesTool listTables;
    private DescribeTableTool describeTable;

    @BeforeEach
    void setUp() throws SQLException {
        // maxRows=2 so truncation is testable with short mock sequences
        DatabaseProperties props = new DatabaseProperties(
                "jdbc:postgresql://localhost/test", "ro_user", "secret", 5, 10, 2, 50, 120);
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        client = new DbClient(props, () -> dataSource);
        runQuery = new RunQueryTool(client);
        listTables = new ListTablesTool(client);
        describeTable = new DescribeTableTool(client);
    }

    // --- run_query -------------------------------------------------------------

    @Test
    @DisplayName("run_query: validator runs first — invalid SQL never touches JDBC")
    void validatorBeforeExecution() throws SQLException {
        assertThatThrownBy(() -> runQuery.execute(new RunQueryTool.Args("SELECT 1; DROP TABLE x")))
                .isInstanceOf(ToolInputException.class);
        verify(dataSource, never()).getConnection();
    }

    @Test
    @DisplayName("run_query happy path: text table + structured content + caps")
    void runQueryHappyPath() throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(statement.getResultSet()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(2);
        when(md.getColumnLabel(1)).thenReturn("id");
        when(md.getColumnLabel(2)).thenReturn("name");
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getObject(1)).thenReturn(1, 2);
        when(rs.getObject(2)).thenReturn("ada", null);

        ToolResult result = runQuery.execute(new RunQueryTool.Args("SELECT id, name FROM users"));

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("id", "name", "ada", "NULL", "2 row(s)");
        var structured = (java.util.Map<?, ?>) result.structured();
        assertThat(structured.get("columns")).isEqualTo(List.of("id", "name"));
        assertThat(structured.get("rows")).isEqualTo(List.of(List.of("1", "ada"), List.of("2", "NULL")));
        assertThat(structured.get("truncated")).isEqualTo(false);

        verify(statement).setMaxRows(3); // maxRows(2) + 1
        verify(statement).setQueryTimeout(10);
    }

    @Test
    @DisplayName("run_query: row cap detected via maxRows+1 and marked truncated")
    void runQueryTruncation() throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(statement.getResultSet()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(1);
        when(md.getColumnLabel(1)).thenReturn("x");
        // maxRows=2: 3 truthy nexts -> 2 rows buffered, 3rd hits the cap -> truncated
        when(rs.next()).thenReturn(true, true, true);
        when(rs.getObject(anyInt())).thenReturn(1);

        ToolResult result = runQuery.execute(new RunQueryTool.Args("SELECT x FROM big"));

        assertThat(result.text()).contains("limited to 2 rows");
        var structured = (java.util.Map<?, ?>) result.structured();
        assertThat(structured.get("truncated")).isEqualTo(true);
        assertThat(structured.get("rowCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("run_query: no result set -> explicit error (write-looking execution)")
    void noResultSet() throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(false);

        ToolResult result = runQuery.execute(new RunQueryTool.Args("SELECT 1"));
        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("no result set");
    }

    @Test
    @DisplayName("run_query: SQLException -> client-safe ToolExecutionException")
    void sqlErrorMapping() throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        var se = new SQLException("relation \"missing\" does not exist", "42P01");
        when(statement.execute(anyString())).thenThrow(se);

        assertThatThrownBy(() -> runQuery.execute(new RunQueryTool.Args("SELECT * FROM missing")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("42P01")
                .hasMessageContaining("does not exist");
    }

    // --- disabled mode ---------------------------------------------------------

    @Test
    @DisplayName("disabled client: every DB tool fails closed without touching JDBC")
    void disabled() throws SQLException {
        DbClient off = new DbClient(DatabaseProperties.DISABLED, () -> dataSource);
        var rq = new RunQueryTool(off);
        var lt = new ListTablesTool(off);
        var dt = new DescribeTableTool(off);

        assertThatThrownBy(() -> rq.execute(new RunQueryTool.Args("SELECT 1")))
                .isInstanceOf(ToolExecutionException.class).hasMessageContaining("disabled");
        assertThatThrownBy(() -> lt.execute(new ListTablesTool.Args(Optional.empty(), Optional.empty())))
                .isInstanceOf(ToolExecutionException.class).hasMessageContaining("disabled");
        assertThatThrownBy(() -> dt.execute(new DescribeTableTool.Args("users", Optional.empty())))
                .isInstanceOf(ToolExecutionException.class).hasMessageContaining("disabled");
        verify(dataSource, never()).getConnection();
    }

    // --- list_tables -----------------------------------------------------------

    @Test
    @DisplayName("list_tables: metadata rows rendered, system schemas hidden")
    void listTables() throws SQLException {
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(md);
        ResultSet rs = mock(ResultSet.class);
        when(md.getTables(any(), any(), any(), any())).thenReturn(rs);

        // deterministic per-row stubbing: cursor = current row, -1 before first next()
        String[][] rows = {
                {"public", "users", "TABLE", ""},
                {"pg_catalog", "pg_class", "SYSTEM TABLE", ""},
                {"app", "orders", "VIEW", "customer orders"},
        };
        int[] cursor = {-1};
        when(rs.next()).thenAnswer(inv -> {
            cursor[0]++;
            return cursor[0] < rows.length;
        });
        when(rs.getString(anyString())).thenAnswer(inv -> {
            String col = inv.getArgument(0);
            int row = cursor[0];
            if (row < 0 || row >= rows.length) return null;
            return switch (col) {
                case "TABLE_SCHEM" -> rows[row][0];
                case "TABLE_NAME" -> rows[row][1];
                case "TABLE_TYPE" -> rows[row][2];
                case "REMARKS" -> rows[row][3];
                default -> null;
            };
        });

        String out = listTables.execute(
                new ListTablesTool.Args(Optional.empty(), Optional.empty())).text();

        assertThat(out).contains("public.users").contains("app.orders  (VIEW — customer orders)");
        assertThat(out).doesNotContain("pg_class").doesNotContain("pg_catalog");
    }

    // --- describe_table --------------------------------------------------------

    @Test
    @DisplayName("describe_table: columns, pk order, fk refs; unknown table message")
    void describeTable() throws SQLException {
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(md);
        when(connection.getSchema()).thenReturn("public");

        ResultSet cols = mock(ResultSet.class);
        when(md.getColumns(any(), any(), any(), any())).thenReturn(cols);
        when(cols.next()).thenReturn(true, true, false);
        when(cols.getString("COLUMN_NAME")).thenReturn("id", "email");
        when(cols.getString("TYPE_NAME")).thenReturn("int8", "varchar");
        when(cols.getInt("NULLABLE")).thenReturn(java.sql.DatabaseMetaData.columnNoNulls,
                java.sql.DatabaseMetaData.columnNullable);
        when(cols.getString("COLUMN_DEF")).thenReturn(null, null);

        ResultSet pk = mock(ResultSet.class);
        when(md.getPrimaryKeys(any(), any(), any())).thenReturn(pk);
        when(pk.next()).thenReturn(true, false);
        when(pk.getString("COLUMN_NAME")).thenReturn("id");
        when(pk.getInt("KEY_SEQ")).thenReturn(1);

        ResultSet fks = mock(ResultSet.class);
        when(md.getImportedKeys(any(), any(), any())).thenReturn(fks);
        when(fks.next()).thenReturn(true, false);
        when(fks.getString("FKCOLUMN_NAME")).thenReturn("email");
        when(fks.getString("PKTABLE_NAME")).thenReturn("users");
        when(fks.getString("PKCOLUMN_NAME")).thenReturn("email");

        String out = describeTable.execute(
                new DescribeTableTool.Args("orders", Optional.empty())).text();
        assertThat(out).contains("table public.orders")
                .contains("primary key: id")
                .contains("email -> users.email")
                .contains("int8").contains("NO").contains("YES");

        ResultSet empty = mock(ResultSet.class);
        when(md.getColumns(any(), any(), any(), any())).thenReturn(empty);
        when(empty.next()).thenReturn(false);
        assertThat(describeTable.execute(new DescribeTableTool.Args("ghost", Optional.empty())).text())
                .contains("not found");
    }
}
