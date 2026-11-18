package com.mcpdev.tooling.security;

/**
 * One authorization/limit decision. Deny carries the precise audit outcome and a
 * client-safe message; the chain short-circuits on the first deny.
 */
@FunctionalInterface
public interface Guard {

    Result check(GuardContext context);

    record Result(boolean allowed, AuditLog.Outcome outcome, String clientMessage) {

        public static Result allow() {
            return new Result(true, AuditLog.Outcome.OK, null);
        }

        public static Result deny(AuditLog.Outcome outcome, String clientMessage) {
            return new Result(false, outcome, clientMessage);
        }
    }
}
