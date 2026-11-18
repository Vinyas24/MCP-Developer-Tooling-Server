package com.mcpdev.tooling.tools.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mcpdev.tooling.registry.Tool;
import com.mcpdev.tooling.registry.ToolExecutionException;
import com.mcpdev.tooling.registry.ToolResult;
import org.springframework.stereotype.Component;

/**
 * Column/PK/FK description of one table via JDBC metadata. Default schema = the
 * connection's current schema.
 */
@Component
public class DescribeTableTool implements Tool<DescribeTableTool.Args> {

    public record Args(String table, Optional<String> schema) {
    }

    private record Column(String name, String type, String nullable, String defaultValue) {
    }

    private final DbClient client;

    public DescribeTableTool(DbClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "describe_table";
    }

    @Override
    public String description() {
        return "Describes a table: columns, types, nullability, defaults, primary and "
                + "foreign keys. Defaults to the connection's current schema.";
    }

    @Override
    public Class<Args> requestType() {
        return Args.class;
    }

    @Override
    public ToolResult execute(Args args) {
        return client.withConnection(connection -> {
            String schema = args.schema().orElseGet(() -> currentSchema(connection));
            try {
                List<Column> columns = readColumns(connection, schema, args.table());
                if (columns.isEmpty()) {
                    return ToolResult.text("table not found: " + schema + "." + args.table());
                }
                List<String> pk = readPrimaryKeys(connection, schema, args.table());
                Map<String, String> fks = readForeignKeys(connection, schema, args.table());

                var out = new StringBuilder();
                out.append("table ").append(schema).append('.').append(args.table()).append('\n');
                if (!pk.isEmpty()) {
                    out.append("primary key: ").append(String.join(", ", pk)).append('\n');
                }
                if (!fks.isEmpty()) {
                    out.append("foreign keys:\n");
                    fks.forEach((col, ref) -> out.append("  ").append(col).append(" -> ").append(ref).append('\n'));
                }
                out.append(String.format("%-28s %-24s %-9s %s%n", "column", "type", "nullable", "default"));
                for (Column c : columns) {
                    out.append(String.format("%-28s %-24s %-9s %s%n",
                            c.name(), c.type(), c.nullable(),
                            c.defaultValue() == null ? "" : c.defaultValue()));
                }
                return ToolResult.text(out.toString());
            } catch (SQLException e) {
                throw new ToolExecutionException("metadata query failed: " + e.getMessage());
            }
        });
    }

    private String currentSchema(Connection connection) {
        try {
            String s = connection.getSchema();
            return s == null || s.isBlank() ? "public" : s;
        } catch (SQLException e) {
            return "public";
        }
    }

    private List<Column> readColumns(Connection connection, String schema, String table)
            throws SQLException {
        var columns = new ArrayList<Column>();
        try (ResultSet rs = connection.getMetaData().getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                String nullable = rs.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNoNulls
                        ? "NO" : "YES";
                columns.add(new Column(rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        nullable,
                        rs.getString("COLUMN_DEF")));
            }
        }
        return columns;
    }

    private List<String> readPrimaryKeys(Connection connection, String schema, String table)
            throws SQLException {
        var cols = new LinkedHashMap<String, Integer>();
        try (ResultSet rs = connection.getMetaData().getPrimaryKeys(null, schema, table)) {
            while (rs.next()) {
                cols.put(rs.getString("COLUMN_NAME"), rs.getInt("KEY_SEQ"));
            }
        }
        return cols.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<String, String> readForeignKeys(Connection connection, String schema, String table)
            throws SQLException {
        var fks = new LinkedHashMap<String, String>();
        try (ResultSet rs = connection.getMetaData().getImportedKeys(null, schema, table)) {
            while (rs.next()) {
                fks.put(rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKTABLE_NAME") + "." + rs.getString("PKCOLUMN_NAME"));
            }
        }
        return fks;
    }
}
