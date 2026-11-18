#!/usr/bin/env python3
"""Mutation evidence: break one invariant, show its IT fails, restore, show it passes.

Each mutation is applied alone to production code, the jar rebuilt, the named
integration test run (must fail), the code restored, and the same test run again
(must pass). This is the evidence behind the "four core boundaries" claim in
TESTING.md and BENCHMARKS.md.

Run from anywhere:
  python3 scripts/mutation_evidence.py            # full run (~4 minutes)
  python3 scripts/mutation_evidence.py --dry-run  # only verify the anchors exist
"""
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOG = pathlib.Path(tempfile.mkdtemp(prefix="mcp-mutation-"))

MUTATIONS = [
    {
        "name": "path-real-containment",
        "file": "src/main/java/com/mcpdev/tooling/security/PathSandbox.java",
        "old": """        Root realRoot = roots.stream()
                .filter(r -> real.startsWith(r.real()))
                .findFirst()
                .orElseThrow(() -> new ToolExecutionException(
                        "path escapes the allowed filesystem roots through symbolic links"));""",
        "new": """        Root realRoot = roots.stream()
                .filter(r -> real.startsWith(r.real()))
                .findFirst()
                .orElse(roots.get(0)); // MUTATION: symlink containment removed""",
        "test": "SecurityBoundaryIT#symlinkEscapeDenied",
    },
    {
        "name": "sql-validator",
        "file": "src/main/java/com/mcpdev/tooling/security/QueryValidator.java",
        "old": """    public static String validate(String sql) {
        if (sql == null || sql.isBlank()) {""",
        "new": """    public static String validate(String sql) {
        if (true) { return sql; } // MUTATION: allow-list disabled
        if (sql == null || sql.isBlank()) {""",
        "test": "SecurityBoundaryIT#sqlInjectionDenied",
    },
    {
        "name": "permission-gate",
        "file": "src/main/java/com/mcpdev/tooling/security/PermissionGuard.java",
        "old": "        if (!actual.meets(required)) {",
        "new": "        if (false && !actual.meets(required)) { // MUTATION: role gate disabled",
        "test": "SecurityBoundaryIT#viewerCannotQuery",
    },
    {
        "name": "audit-argument-hashing",
        "file": "src/main/java/com/mcpdev/tooling/registry/McpToolAdapter.java",
        "old": "            return hex.toString();",
        "new": "            return canonical; // MUTATION: raw arguments written to the audit log",
        "test": "SecurityBoundaryIT#auditStoresNoArgumentValues",
    },
]


def run(cmd, logfile):
    with open(logfile, "w") as f:
        return subprocess.run(cmd, cwd=ROOT, stdout=f, stderr=subprocess.STDOUT,
                              text=True).returncode


def build_jar(tag):
    return run(["mvn", "-B", "-ntp", "-q", "package", "-DskipTests"],
               LOG / (tag + "-build.log"))


def run_it(test, tag):
    return run(["mvn", "-B", "-ntp", "failsafe:integration-test", "failsafe:verify",
                "-Dit.test=" + test, "-DfailIfNoSpecifiedTests=false"],
               LOG / (tag + "-it.log"))


def main():
    dry_run = "--dry-run" in sys.argv
    problems = []

    for mutation in MUTATIONS:
        path = ROOT / mutation["file"]
        original = path.read_text()
        if mutation["old"] not in original:
            print("SKIP %s: anchor not found" % mutation["name"])
            problems.append(mutation["name"])
            continue
        if dry_run:
            print("anchor ok: %s -> %s" % (mutation["name"], mutation["test"]))
            continue

        try:
            path.write_text(original.replace(mutation["old"], mutation["new"], 1))
            if build_jar(mutation["name"]) != 0:
                problems.append(mutation["name"] + " (mutated build failed)")
                continue
            code = run_it(mutation["test"], mutation["name"] + "-mutated")
            log = (LOG / (mutation["name"] + "-mutated-it.log")).read_text()
            detected = code != 0 and "FAILURE!" in log
            print("%s: mutated exit=%s detected=%s"
                  % (mutation["name"], code, detected))
            if not detected:
                problems.append(mutation["name"] + " (mutation not caught)")
        finally:
            path.write_text(original)

        if build_jar(mutation["name"] + "-restored") != 0:
            problems.append(mutation["name"] + " (restored build failed)")
            continue
        code = run_it(mutation["test"], mutation["name"] + "-restored")
        print("%s: restored exit=%s" % (mutation["name"], code))
        if code != 0:
            problems.append(mutation["name"] + " (restored still failing)")

    print("logs: %s" % LOG)
    if problems:
        print("PROBLEMS: %s" % problems)
        return 1
    print("RESULT: all mutations caught" if not dry_run else "RESULT: anchors ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
