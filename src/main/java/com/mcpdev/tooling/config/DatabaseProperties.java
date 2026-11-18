package com.mcpdev.tooling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Database tooling configuration. Empty url = DB tools disabled (deny by default),
 * FS-only server. Credentials come from env — never from files in the repo.
 */
@ConfigurationProperties(prefix = "mcp.db")
public record DatabaseProperties(
        String url,
        String username,
        String password,
        int maxPoolSize,
        int queryTimeoutSeconds,
        int maxRows,
        int maxRenderRows,
        int maxCellLength) {

    public static final DatabaseProperties DISABLED =
            new DatabaseProperties("", "", "", 5, 10, 500, 50, 120);

    /** Test/programmatic factory with production defaults. */
    public static DatabaseProperties withDefaults(String url, String username, String password) {
        return new DatabaseProperties(url, username, password, 5, 10, 500, 50, 120);
    }

    public DatabaseProperties {
        url = url == null ? "" : url.trim();
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        if (maxPoolSize < 1) maxPoolSize = 5;
        if (queryTimeoutSeconds < 1) queryTimeoutSeconds = 10;
        if (maxRows < 1) maxRows = 500;
        if (maxRenderRows < 1) maxRenderRows = 50;
        if (maxCellLength < 10) maxCellLength = 120;
    }

    public boolean enabled() {
        return !url.isEmpty();
    }
}
