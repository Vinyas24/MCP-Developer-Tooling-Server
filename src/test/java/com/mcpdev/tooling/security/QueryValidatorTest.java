package com.mcpdev.tooling.security;

import com.mcpdev.tooling.registry.ToolInputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every accepted case must be genuinely read-only; every rejection must fail closed.
 * Attacks collected from real-world "LLM with SQL access" incident patterns.
 */
class QueryValidatorTest {

    private void accepts(String sql) {
        assertThatCode(() -> QueryValidator.validate(sql)).doesNotThrowAnyException();
    }

    private void rejects(String sql) {
        assertThatThrownBy(() -> QueryValidator.validate(sql)).isInstanceOf(ToolInputException.class);
    }

    // --- allowed --------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM users",
            "select id, name from public.users where id = 1",
            "EXPLAIN SELECT * FROM users",
            "EXPLAIN (COSTS TRUE) SELECT 1",
            "VALUES (1, 'a'), (2, 'b')",
            "TABLE users",
            "SHOW search_path",
            "SHOW ALL",
            "WITH active AS (SELECT * FROM users WHERE active) SELECT * FROM active",
            "WITH a AS (SELECT 1), b AS (SELECT 2) SELECT * FROM a JOIN b ON true",
            "WITH x AS MATERIALIZED (SELECT 1) SELECT * FROM x",
            "WITH x AS NOT MATERIALIZED (SELECT 1) SELECT * FROM x",
            "WITH x (id) AS (SELECT 1) SELECT * FROM x",
            // literal and comment content must never affect the verdict
            "SELECT 'DROP TABLE users; --' AS text",
            "SELECT \"weird;name\" FROM t",
            "SELECT 1 /* ; DROP TABLE x; */",
            "SELECT 1 -- ; DROP TABLE x\n",
            "SELECT E'\\\\; ' AS tricky",
            "SELECT $$a; b$$ || 'c;d'",
            "SELECT $tag$multi\n;line$tag$",
    })
    void allowsReadOnly(String sql) {
        accepts(sql);
    }

    @Test
    @DisplayName("documented over-rejection: E-strings with escaped quotes fail closed")
    void eStringEscapedQuoteOverRejection() {
        // PG would treat this as one harmless string; our lexer ends it at \' and sees
        // a stray quote. Over-rejection is the safe direction; documented behavior.
        rejects("SELECT E'\\'; ' AS tricky");
    }

    // --- multiple statements / smuggling ---------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1; DROP TABLE users",
            "SELECT 1 ;",
            "; DROP TABLE users",
            "SELECT ';'; SELECT 2",           // semicolon between two literals
            "SELECT 1\n;\nDROP TABLE users",  // newlines around the smuggled statement
            "/* comment */ ; SELECT 1",
    })
    void rejectsMultiStatement(String sql) {
        rejects(sql);
    }

    // --- verbs -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "DROP TABLE users",
            "DELETE FROM users",
            "INSERT INTO users VALUES (1)",
            "UPDATE users SET name = 'x'",
            "TRUNCATE users",
            "ALTER TABLE users ADD COLUMN x int",
            "CREATE TABLE t (id int)",
            "GRANT ALL ON users TO attacker",
            "COPY users TO '/tmp/steal.csv'",
            "VACUUM",
            "CALL do_stuff()",
    })
    void rejectsWriteVerbsAndNoise(String sql) {
        rejects(sql);
    }

    // --- the sneaky holes --------------------------------------------------------

    @Test
    @DisplayName("EXPLAIN ANALYZE executes: rejected in both syntaxes")
    void explainAnalyze() {
        rejects("EXPLAIN ANALYZE SELECT * FROM users");
        rejects("EXPLAIN  ANALYZE  SELECT 1");
        rejects("EXPLAIN (ANALYZE) SELECT * FROM users");
        rejects("EXPLAIN (ANALYZE, FORMAT JSON) DELETE FROM users");
        rejects("explain analyze select 1");
        // plain EXPLAIN stays allowed, even of a DELETE (only plans, executes nothing)
        accepts("EXPLAIN DELETE FROM users");
    }

    @Test
    @DisplayName("data-modifying CTEs are rejected everywhere; reading CTEs pass")
    void cteWrites() {
        rejects("WITH del AS (DELETE FROM users RETURNING *) SELECT * FROM del");
        rejects("WITH ins AS (INSERT INTO t VALUES (1) RETURNING *) SELECT * FROM ins");
        rejects("with upd as (update t set a=1) select * from upd");
        rejects("WITH x AS (SELECT 1) INSERT INTO t SELECT * FROM x");
        rejects("WITH a AS (SELECT 1), b AS (UPDATE t SET a=1) SELECT * FROM a JOIN b ON true");
        // nested WITH inside a derived table
        rejects("SELECT * FROM (WITH y AS (UPDATE t SET a=1) SELECT * FROM y) sub");
        accepts("SELECT * FROM (WITH y AS (SELECT 1) SELECT * FROM y) sub");
        accepts("WITH x AS (SELECT 1) SELECT * FROM x");
        accepts("WITH x AS (WITH y AS (SELECT 1) SELECT * FROM y) SELECT * FROM x");
    }

    @Test
    @DisplayName("SELECT INTO creates tables: rejected anywhere")
    void selectInto() {
        rejects("SELECT * INTO evil FROM users");
        rejects("select 1 into pg_catalog.pg Evil");
        rejects("WITH x AS (SELECT * INTO evil FROM users) SELECT * FROM x");
    }

    @Test
    @DisplayName("row locking is rejected")
    void locking() {
        rejects("SELECT * FROM users WHERE id = 1 FOR UPDATE");
        rejects("SELECT * FROM users FOR SHARE");
        rejects("SELECT * FROM users FOR NO KEY UPDATE");
        rejects("SELECT * FROM users FOR KEY SHARE");
        rejects("WITH x AS (SELECT * FROM users FOR UPDATE) SELECT * FROM x");
    }

    @Test
    @DisplayName("length cap")
    void lengthCap() {
        rejects("SELECT " + "'x' ||".repeat(3000) + " 1");
    }

    @Test
    @DisplayName("stripper handles PG lexing exactly: nesting, dollar tags, doubling")
    void lexer() {
        assertThat(QueryValidator.strip("/* a /* b */ c */ SELECT 1"))
                .isEqualTo("  SELECT 1"); // nested comment collapses to one space
        assertThat(QueryValidator.strip("SELECT 'a''b;c'")).isEqualTo("SELECT ?");
        assertThat(QueryValidator.strip("SELECT $$x;y$$, $tag$ z $tag$")).isEqualTo("SELECT ?, ?");
        assertThat(QueryValidator.strip("SELECT \"col;name\"")).isEqualTo("SELECT ?");
        assertThat(QueryValidator.strip("SELECT E'a\\'' ;")).isEqualTo("SELECT E?");
    }
}
