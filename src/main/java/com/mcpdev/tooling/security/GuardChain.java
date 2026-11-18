package com.mcpdev.tooling.security;

import java.util.List;

/**
 * Ordered guards, first deny wins. Order is the contract from the design:
 * identity -> permission -> rate -> size. Domain validation (sandbox/query) runs
 * after argument binding, as a later stage of the same funnel.
 */
public class GuardChain {

    private final List<Guard> guards;

    public GuardChain(List<Guard> guards) {
        this.guards = List.copyOf(guards);
    }

    public Guard.Result check(GuardContext context) {
        for (Guard guard : guards) {
            Guard.Result result = guard.check(context);
            if (!result.allowed()) {
                return result;
            }
        }
        return Guard.Result.allow();
    }
}
