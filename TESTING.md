# Testing

Two test layers, split by what they need to run. The split is enforced by plugin
configuration, not convention: surefire runs `**/*Test.java`, failsafe runs
`**/*IT.java`.

| Command | Runs | Needs Docker | Time (measured) |
| --- | --- | --- | --- |
| `mvn test` | 162 unit tests (JUnit 5 + Mockito) | no | ~13 s |
| `mvn verify` | unit tests + 32 integration tests + JaCoCo gates | yes | ~55 s |

`mvn verify` builds the jar before failsafe, then:

- starts one shared `postgres:16-alpine` container (Testcontainers), initialised with
  `src/test/resources/db/init.sql` — schema `app` granted to the restricted role
  `mcp_ro`, schema `secret` deliberately **not** granted;
- spawns the packaged server as a separate process and drives it over stdio exactly as
  Claude Desktop would (no in-process shortcuts).

## Why the integration layer exists

Unit tests with mocks prove the code paths; they cannot prove the security boundary
actually holds. These can only be tested against the real thing:

- the read-only **role** refuses writes even when the SQL parser is bypassed entirely;
- `transaction_read_only=on` is really set by the pool (pgjdbc ignores
  `Connection.setReadOnly` in autocommit — `DbClient` sets the session default);
- statement timeout, row cap and truncation are enforced by the driver;
- grants, not metadata visibility, decide what data is readable;
- the pool returns every connection under sequential and concurrent load;
- the packaged wiring (env → config → guards → audit) preserves the invariants.

## Security invariant matrix

Every invariant is mapped to at least one test. For the four core boundaries, a
mutation was applied to production code to prove the tests are not vacuous; see
[Mutation evidence](#mutation-evidence).

| Invariant | Unit test | Integration test | Mutation that breaks it |
| --- | --- | --- | --- |
| Paths are normalized and confined to allowed roots | `PathSandboxTest` (traversal, absolute outside, nested roots, no roots) | `SecurityBoundaryIT#dotDotTraversalDenied`, `#absoluteOutsidePathDenied` | — |
| Symlink escapes are rejected on the real path | `PathSandboxTest#symlinkEscape` | `SecurityBoundaryIT#symlinkEscapeDenied` | remove the real-path containment check |
| Null bytes never reach the OS | `PathSandboxTest#hostileInput` | `SecurityBoundaryIT#nullByteDenied` | — |
| File size / line / entry / match caps | `FsToolsTest#readFileBasics`, `#listDirCap`, `#searchCaps` | — (temp filesystem unit tests) | — |
| SQL is a single statement | `QueryValidatorTest` (44 cases) | `SecurityBoundaryIT#sqlInjectionDenied` (stacked statements) | disable `QueryValidator.validate` |
| SQL verb allow-list; no `SELECT INTO`, `FOR UPDATE`, `EXPLAIN ANALYZE` | `QueryValidatorTest` | `SecurityBoundaryIT#sqlInjectionDenied` | disable `QueryValidator.validate` |
| Data-modifying CTEs are rejected | `QueryValidatorTest` | `SecurityBoundaryIT#sqlInjectionDenied` | disable `QueryValidator.validate` |
| Writes are impossible even without the parser (role is the boundary) | — | `PostgresIntegrationIT#writesImpossibleWithoutTheParser` | — |
| Session is read-only | — | `PostgresIntegrationIT#sessionIsReadOnly` | drop `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY` |
| Row cap truncates and reports it | `DbToolsTest` | `PostgresIntegrationIT#rowCapTruncates` | — |
| Statement timeout is driver-enforced | `DbToolsTest` | `PostgresIntegrationIT#statementTimeout` | — |
| Grants decide access (unprivileged schema refuses `42501`) | — | `PostgresIntegrationIT#grantsAreTheBoundary` | — |
| Metadata filters are parameters, not SQL | `DbToolsTest` | `PostgresIntegrationIT#metadataInjectionIsInert` | — |
| Connection pool does not leak | — | `PostgresIntegrationIT#sequentialUseDoesNotLeakConnections`, `#concurrentUseDoesNotLeakConnections` | — |
| Role gates per tool, deny by default | `RoleToolMatrixTest` (all roles × all tools), `EffectivePermissionsTest`, `IdentityGuardTest` | `SecurityBoundaryIT#viewerCannotQuery` | disable the role check in `PermissionGuard` |
| Overrides may only narrow; unknown roles fail startup | `EffectivePermissionsTest` | `SecurityBoundaryIT#wideningOverrideFailsStartup`, `#narrowingOverrideDeniesViewer`, `#unknownRoleFailsStartup` | — |
| Every terminal call writes exactly one audit line | `AuditLogTest`, `McpToolAdapterTest`, `McpToolAdapterAsyncTest` | `ProtocolConformanceIT#missingRequiredArgumentIsAudited`, `#auditCorrelationMatchesRequestId` | — |
| Audit contains no argument values (SHA-256 prefix only) | `AuditLogTest#escapesHostileFields` | `SecurityBoundaryIT#auditStoresNoArgumentValues` | return raw arguments instead of the hash |
| Handshake negotiates the protocol version | — | `ProtocolConformanceIT#handshake` | — |
| Malformed JSON → `-32700`, session survives | — | `ProtocolConformanceIT#malformedJsonDoesNotKillTheSession`, `#batchArrayRejected` | — |
| Unknown method → `-32601`; unknown tool → `-32602` | — | `ProtocolConformanceIT#unknownMethod`, `#unknownTool` | — |
| Oversized input is bounded before allocation | — | `ProtocolConformanceIT#oversizedRequestLine` | — |
| Notifications stay silent; ids echoed | — | `ProtocolConformanceIT#notificationsProduceNoResponse`, `#stringRequestIdIsEchoed` | — |
| stdout is JSON-RPC only (logs to stderr) | — | `McpServerProcess#assertStdoutPure` in both IT classes | — |

## Coverage gates (critical paths only)

Line coverage is not a goal in itself, so the gate covers only code where a miss is a
security bug. Configured in `pom.xml`, executed by `mvn verify`:

| Scope | Line | Branch | Measured |
| --- | --- | --- | --- |
| `com.mcpdev.tooling.security` (package) | ≥ 90% | ≥ 80% | 95.1% / 82.0% |
| `McpToolAdapter` | ≥ 75% | — | 79.7% |
| `ToolArgsBinder` | ≥ 75% | — | 75.6% |
| `ToolExecutor` | ≥ 75% | — | 85.3% |
| `DbClient` | ≥ 75% | — | 83.3% |

Note on package patterns: JaCoCo matches **package** names with dots
(`com.mcpdev.tooling.security`) and **class** names with slashes
(`com/mcpdev/tooling/registry/McpToolAdapter`). A slash-form package pattern matches
nothing and the rule silently passes; this was caught while wiring the gate.

## Mutation evidence

Each mutation was applied alone, the jar rebuilt, the named IT run (failed), the code
restored, and the same IT run again (passed):

| Mutation | Test | Mutated | Restored |
| --- | --- | --- | --- |
| Real-path containment replaced by "first root wins" | `SecurityBoundaryIT#symlinkEscapeDenied` | fails (secret is read) | passes |
| `QueryValidator.validate` short-circuited | `SecurityBoundaryIT#sqlInjectionDenied` | fails (`DELETE FROM app.users` executes) | passes |
| Role check in `PermissionGuard` disabled | `SecurityBoundaryIT#viewerCannotQuery` | fails (VIEWER runs `run_query`) | passes |
| Audit writes raw arguments instead of SHA-256 prefix | `SecurityBoundaryIT#auditStoresNoArgumentValues` | fails (`sk-live…` appears in the log) | passes |

Reproduce with [`scripts/mutation_evidence.py`](scripts/mutation_evidence.py) (about four
minutes, needs Docker): it applies each mutation, rebuilds the jar, runs the named IT,
restores the code, and reruns the same IT. `--dry-run` only checks the anchors.

## CI

`.github/workflows/ci.yml` runs two jobs on JDK 21 (temurin):

1. **unit** — `mvn -B -ntp test`; no Docker, fails fast.
2. **integration** — `mvn -B -ntp verify`; Testcontainers Postgres, the spawned jar,
   and the JaCoCo gates. Test and coverage reports are uploaded on failure too.

`concurrency.cancel-in-progress` stops superseded runs on the same ref.
