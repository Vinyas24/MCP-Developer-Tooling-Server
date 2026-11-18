package com.mcpdev.tooling.security;

import com.mcpdev.tooling.tools.db.RunQueryTool;
import com.mcpdev.tooling.tools.fs.ListDirTool;
import com.mcpdev.tooling.tools.fs.ReadFileTool;
import com.mcpdev.tooling.tools.fs.SearchCodeTool;

/**
 * Domain-validation stage of the funnel: re-runs sandbox/query checks on the BOUND
 * arguments, before execution, so denials are uniform (audit outcome DENIED_DOMAIN)
 * and ordered after the generic guards. Tools keep their own internal checks — they
 * must stay fail-closed when called directly (unit tests, future callers).
 *
 * <p>Type-matching is on the args record classes, so protection travels with the
 * argument shape; a new Path- or SQL-bearing tool needs a case here, and the switch
 * keeps that discoverable.</p>
 */
public final class DomainChecks {

    private final PathSandbox sandbox;

    public DomainChecks(PathSandbox sandbox) {
        this.sandbox = sandbox;
    }

    public void validate(Object boundArgs) {
        switch (boundArgs) {
            case ListDirTool.Args a -> sandbox.resolve(a.path());
            case ReadFileTool.Args a -> sandbox.resolve(a.path());
            case SearchCodeTool.Args a -> a.path().ifPresent(sandbox::resolve);
            case RunQueryTool.Args a -> QueryValidator.validate(a.sql());
            default -> {
                // tools without domain-specific validation pass through
            }
        }
    }
}
