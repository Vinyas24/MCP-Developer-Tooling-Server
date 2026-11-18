package com.mcpdev.tooling.tools.db;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mcpdev.tooling.registry.Permission;
import com.mcpdev.tooling.registry.Tool;
import com.mcpdev.tooling.registry.ToolResult;
import com.mcpdev.tooling.security.QueryValidator;
import org.springframework.stereotype.Component;

/**
 * Runs a validated read-only query. Statement-level caps: maxRows (driver-side stop),
 * queryTimeout (driver-side cancel). Results render as a text table for the LLM plus
 * structuredContent {columns, rows, truncated} for programmatic clients.
 */
@Component
public class RunQueryTool implements Tool<RunQueryTool.Args> {

    public record Args(String sql) {
    }

    private static final int MAX_COLUMN_WIDTH = 40;
    private static final int MAX_TABLE_WIDTH = 4000;

    private final DbClient client;

    public RunQueryTool(DbClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "run_query";
    }

    @Override
    public String description() {
        return "Runs a single read-only SQL statement (SELECT, EXPLAIN, VALUES, TABLE, SHOW, "
                + "WITH...SELECT) against the connected PostgreSQL database. Row limit and "
                + "timeout apply. Writes are impossible.";
    }

    @Override
    public Class<Args> requestType() {
        return Args.class;
    }

    @Override
    public Permission requiredPermission() {
        return Permission.QUERY;
    }

    @Override
    public ToolResult execute(Args args) {
        String sql = QueryValidator.validate(args.sql());
        return client.withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.setMaxRows(client.props().maxRows() + 1); // +1 = truncation detector
                statement.setQueryTimeout(client.props().queryTimeoutSeconds());
                boolean hasResultSet = statement.execute(sql);
                if (!hasResultSet) {
                    return ToolResult.error(
                            "statement produced no result set (only SELECT-like queries are allowed)");
                }
                try (ResultSet rs = statement.getResultSet()) {
                    return render(rs);
                }
            }
        });
    }

    private ToolResult render(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int columnCount = md.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columns.add(md.getColumnLabel(i));
        }

        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= client.props().maxRows()) {
                truncated = true;
                break;
            }
            var row = new ArrayList<String>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                Object v = rs.getObject(i);
                row.add(v == null ? "NULL" : cell(v.toString()));
            }
            rows.add(row);
        }

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("columns", columns);
        structured.put("rows", rows);
        structured.put("rowCount", rows.size());
        structured.put("truncated", truncated);

        return ToolResult.structured(renderTable(columns, rows, truncated), structured);
    }

    private String renderTable(List<String> columns, List<List<String>> rows, boolean truncated) {
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = Math.min(columns.get(i).length(), MAX_COLUMN_WIDTH);
        }
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                widths[i] = Math.max(widths[i], Math.min(row.get(i).length(), MAX_COLUMN_WIDTH));
            }
        }

        var out = new StringBuilder();
        var line = new StringBuilder();
        for (int w : widths) {
            line.append("-".repeat(w + 2)).append('+');
        }
        String separator = line.toString();

        appendRow(out, separator, columns, widths);
        out.append('\n').append(separator).append('\n');
        for (List<String> row : rows) {
            appendRow(out, separator, row, widths);
        }
        if (truncated) {
            out.append("… [limited to ").append(client.props().maxRows()).append(" rows]\n");
        }
        out.append(rows.size()).append(" row(s)\n");
        return out.toString();
    }

    private static void appendRow(StringBuilder out, String separator, List<String> cells,
                                  int[] widths) {
        if (out.length() > MAX_TABLE_WIDTH) {
            return; // output budget: keep the envelope, drop body rows
        }
        for (int i = 0; i < widths.length; i++) {
            String cell = i < cells.size() ? cells.get(i) : "";
            out.append(' ').append(pad(cell, widths[i])).append(' ').append('|');
        }
        out.append('\n');
    }

    private static String pad(String s, int width) {
        if (s.length() > width) {
            return s.substring(0, width - 1) + "…";
        }
        return s + " ".repeat(width - s.length());
    }

    private String cell(String raw) {
        String flat = raw.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= client.props().maxCellLength()
                ? flat : flat.substring(0, client.props().maxCellLength()) + "…";
    }
}
