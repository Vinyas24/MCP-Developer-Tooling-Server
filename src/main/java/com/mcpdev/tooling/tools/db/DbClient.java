package com.mcpdev.tooling.tools.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.mcpdev.tooling.config.DatabaseProperties;
import com.mcpdev.tooling.registry.ToolExecutionException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Owns the connection pool (lazily created: FS-only servers never pay for it) and
 * maps SQLExceptions to client-safe messages.
 *
 * <p><b>DB backpressure is explicit since Phase 6.</b> Pool size bounds real database
 * work; a permit of the same size is taken BEFORE {@code getConnection()}, so a spike
 * of 1,000 concurrent queries cannot bury 1,000 virtual threads inside Hikari's queue
 * for five seconds. Callers that cannot get a permit within
 * {@value #QUEUE_WAIT_MILLIS} ms fail fast with "database busy" — the load-shedding
 * story is visible in the audit log as TOOL_ERROR, not as a timeout cascade.
 *
 * <p>Connections are closed on every path (try-with-resources); a leak here would
 * exhaust the pool permanently.
 *
 * <p>{@code readOnly=true} at pool level: Postgres sessions start read-only even if the
 * DB role was misconfigured with write grants.
 */
@Component
public class DbClient implements AutoCloseable {

    /** Short, honest wait: the pool is the throughput limit, not a parking lot. */
    static final long QUEUE_WAIT_MILLIS = 2_000;

    private final DatabaseProperties props;
    private final Supplier<DataSource> poolSupplier;
    private final Semaphore dbPermits;
    private volatile DataSource pool;

    @Autowired
    public DbClient(DatabaseProperties props) {
        this(props, null);
    }

    /** Test seam: inject a fake DataSource. */
    public DbClient(DatabaseProperties props, Supplier<DataSource> poolSupplier) {
        this.props = props;
        this.poolSupplier = poolSupplier != null ? poolSupplier : this::createHikariPool;
        this.dbPermits = new Semaphore(Math.max(1, props.maxPoolSize()), true);
    }

    public boolean enabled() {
        return props.enabled();
    }

    public DatabaseProperties props() {
        return props;
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** Pool pressure snapshot for load-test evidence and leak checks. */
    public record Stats(int inUse, int max) {
    }

    public Stats stats() {
        int max = Math.max(1, props.maxPoolSize());
        return new Stats(max - dbPermits.availablePermits(), max);
    }

    public <T> T withConnection(SqlWork<T> work) {
        if (!enabled()) {
            throw new ToolExecutionException("Database tools are disabled: "
                    + "set MCP_DB_URL, MCP_DB_USER and MCP_DB_PASSWORD");
        }
        boolean acquired;
        try {
            acquired = dbPermits.tryAcquire(QUEUE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("database call aborted while waiting for a connection");
        }
        if (!acquired) {
            throw new ToolExecutionException("database busy: " + props.maxPoolSize()
                    + " queries in flight, try again");
        }
        try (Connection connection = dataSource().getConnection()) {
            return work.apply(connection);
        } catch (SQLTimeoutException e) {
            throw new ToolExecutionException("query timed out after "
                    + props.queryTimeoutSeconds() + "s");
        } catch (SQLTransientConnectionException e) {
            throw new ToolExecutionException("database busy: connection pool exhausted or "
                    + "unavailable, try again");
        } catch (SQLException e) {
            if ("57014".equals(e.getSQLState())) { // query_canceled_due_to_statement_timeout
                throw new ToolExecutionException("query timed out after "
                        + props.queryTimeoutSeconds() + "s");
            }
            // Message may name objects/hosts but never credentials; SQLState aids triage.
            throw new ToolExecutionException("database error [SQLState=" + e.getSQLState()
                    + "]: " + e.getMessage());
        } finally {
            dbPermits.release();
        }
    }

    /**
     * Closes the pool if it was ever created. Spring calls this on shutdown; tests
     * call it directly so pools do not accumulate across IT classes.
     */
    @PreDestroy
    @Override
    public void close() {
        DataSource local = pool;
        pool = null;
        if (local instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                // shutdown path: a pool that refuses to close must not mask the real error
            }
        }
    }

    private DataSource dataSource() {
        DataSource local = pool;
        if (local == null) {
            synchronized (this) {
                if (pool == null) {
                    pool = poolSupplier.get();
                }
                local = pool;
            }
        }
        return local;
    }

    private DataSource createHikariPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.url());
        config.setUsername(props.username());
        config.setPassword(props.password());
        config.setMaximumPoolSize(props.maxPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        // pgjdbc only applies Connection.setReadOnly inside an explicit transaction,
        // and our statements run in autocommit — so set the session default instead.
        // Every implicit transaction is then read-only, which is what makes a
        // side-effectful function inside SELECT fail as well.
        config.setReadOnly(true);
        config.setConnectionInitSql(
                "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
        config.setPoolName("mcp-dev-tooling-db");
        return new HikariDataSource(config);
    }
}
