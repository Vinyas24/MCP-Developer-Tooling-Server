package com.mcpdev.tooling.tools.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.mcpdev.tooling.config.DatabaseProperties;
import com.mcpdev.tooling.registry.Tool;
import com.mcpdev.tooling.registry.ToolExecutionException;
import com.mcpdev.tooling.registry.ToolResult;
import org.springframework.stereotype.Component;

/**
 * Lists user tables/views via JDBC metadata (parameterized, injection-safe).
 * System schemas (pg_catalog, information_schema, pg_*) are hidden — schema
 * arguments are honored, so the client can still target a schema it created.
 */
@Component
public class ListTablesTool implements Tool<ListTablesTool.Args> {

    public record Args(Optional<String> schema, Optional<String> tableLike) {
    }

    private final DbClient client;

    public ListTablesTool(DbClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "list_tables";
    }

    @Override
    public String description() {
        return "Lists tables and views in the connected PostgreSQL database. "
                + "System schemas are hidden. Optional schema and name-pattern filters.";
    }

    @Override
    public Class<Args> requestType() {
        return Args.class;
    }

    @Override
    public ToolResult execute(Args args) {
        return client.withConnection(connection -> {
            var md = connection.getMetaData();
            try (ResultSet rs = md.getTables(null,
                    args.schema().orElse(null),
                    args.tableLike().orElse("%"),
                    new String[]{"TABLE", "VIEW"})) {

                var out = new StringBuilder();
                int count = 0;
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    if (isSystemSchema(schema)) {
                        continue;
                    }
                    String table = rs.getString("TABLE_NAME");
                    String type = rs.getString("TABLE_TYPE");
                    String remarks = rs.getString("REMARKS");
                    out.append(schema).append('.').append(table).append("  (").append(type);
                    if (remarks != null && !remarks.isBlank()) {
                        out.append(" — ").append(trim(remarks, 80));
                    }
                    out.append(")\n");
                    if (++count >= 500) {
                        out.append("… truncated at 500 tables\n");
                        break;
                    }
                }
                if (count == 0) {
                    return ToolResult.text("no tables found"
                            + (args.schema().map(s -> " in schema '" + s + "'").orElse("")));
                }
                return ToolResult.text(out.toString());
            } catch (SQLException e) {
                throw new ToolExecutionException("metadata query failed: " + e.getMessage());
            }
        });
    }

    static boolean isSystemSchema(String schema) {
        return schema == null || schema.startsWith("pg_") || schema.equals("information_schema");
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
