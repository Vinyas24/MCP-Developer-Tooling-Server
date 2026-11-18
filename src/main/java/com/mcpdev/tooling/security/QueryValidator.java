package com.mcpdev.tooling.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.mcpdev.tooling.registry.ToolInputException;

/**
 * Read-only SQL gate for {@code run_query}. PostgreSQL-aware lexer + structural rules.
 * Defense in depth only — the authoritative boundary is the read-only database role,
 * because side-effectful functions inside SELECT are undetectable by parsing.
 *
 * <p>Rules (every rejection is a fail-closed safe-deny):</p>
 * <ol>
 *   <li>exactly one statement: no ';' outside literals/comments</li>
 *   <li>first verb allow-list: SELECT, EXPLAIN, VALUES, TABLE, SHOW, WITH</li>
 *   <li>EXPLAIN ANALYZE rejected — it EXECUTES the statement (writes included)</li>
 *   <li>WITH must resolve to a SELECT main clause — blocks data-modifying CTEs
 *       ({@code WITH x AS (...) INSERT ...})</li>
 *   <li>INTO rejected anywhere — SELECT INTO creates tables</li>
 *   <li>FOR UPDATE / FOR SHARE rejected — row locks</li>
 * </ol>
 *
 * <p>Lexer covers: '--' comments, nested {@code /* *&#47;} comments, '...' strings with
 * '' doubling (standard_conforming_strings=on; exotic E'...' strings may over-reject —
 * safe direction), $tag$ dollar quotes, "quoted identifiers".</p>
 */
public final class QueryValidator {

    private static final Set<String> ALLOWED_FIRST =
            Set.of("select", "explain", "values", "table", "show", "with");

    private static final int MAX_SQL_LENGTH = 10_000;

    private QueryValidator() {
    }

    /** Validates and returns the original SQL on success. Throws ToolInputException. */
    public static String validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new ToolInputException("sql is required");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            throw new ToolInputException("sql exceeds " + MAX_SQL_LENGTH + " characters");
        }

        String stripped = strip(sql);
        if (stripped.indexOf(';') >= 0) {
            throw new ToolInputException(
                    "only a single read-only statement is allowed (no ';')");
        }

        List<String> tokens = tokenize(stripped);
        if (tokens.isEmpty()) {
            throw new ToolInputException("no statement found");
        }

        if (!readOnlyStructure(tokens, 0, tokens.size())) {
            throw new ToolInputException("only read-only statements (SELECT, EXPLAIN, VALUES, "
                    + "TABLE, SHOW, WITH ... SELECT) are allowed");
        }
        return sql;
    }

    /**
     * Structural check of tokens[from, to). In PostgreSQL grammar, data-modifying
     * statements can only appear (a) as the top-level statement, (b) as CTE bodies,
     * or (c) as the statement of a nested WITH (derived table / scalar subquery:
     * {@code SELECT * FROM (WITH x AS (UPDATE ...) SELECT 1) t}) — everything else
     * (plain subqueries, EXISTS, IN (...)) only admits SELECT/VALUES/TABLE.
     * So: every WITH body must itself be structurally read-only (recursive), and
     * any parenthesized group starting with WITH is validated recursively too.
     */
    private static boolean readOnlyStructure(List<String> tokens, int from, int to) {
        if (from >= to) {
            return false;
        }
        String first = tokens.get(from);

        if (first.equals("with")) {
            return withIsReadOnly(tokens, from, to);
        }
        if (!ALLOWED_FIRST.contains(first)) {
            return false;
        }
        if (first.equals("explain") && isExplainAnalyze(tokens, from, to)) {
            return false;
        }
        for (int i = from; i < to; i++) {
            String t = tokens.get(i);
            if (t.equals("into")) {
                return false;
            }
            if (t.equals("for") && i + 1 < to
                    && Set.of("update", "share", "no", "key").contains(tokens.get(i + 1))) {
                return false;
            }
            // nested WITH inside a derived table / scalar subquery: "(WITH ... SELECT)"
            if (t.equals("with") && i > from && tokens.get(i - 1).equals("(")) {
                int close = findClosing(tokens, i - 1, to);
                if (close < 0 || !withIsReadOnly(tokens, i, close)) {
                    return false;
                }
                i = close;
            }
        }
        return true;
    }

    /** WITH: all CTE bodies read-only + main clause structurally read-only. */
    private static boolean withIsReadOnly(List<String> tokens, int from, int to) {
        int main = withMainClauseIndex(tokens, from, to);
        if (main < 0 || !tokens.get(main).equals("select")) {
            return false;
        }
        return readOnlyStructure(tokens, main, to);
    }

    /** Validates all CTE bodies recursively; returns the main-clause token index, or -1. */
    private static int withMainClauseIndex(List<String> tokens, int from, int to) {
        int i = from + 1;
        while (true) {
            if (i >= to || !isIdentifier(tokens.get(i))) return -1;
            i++;
            if (i < to && tokens.get(i).equals("(")) {
                i = skipBalanced(tokens, i, to); // optional column list
                if (i < 0) return -1;
            }
            if (i >= to || !tokens.get(i).equals("as")) return -1;
            i++;
            if (i < to && tokens.get(i).equals("not")) i++;
            if (i < to && tokens.get(i).equals("materialized")) i++;
            if (i >= to || !tokens.get(i).equals("(")) return -1;
            int open = i;
            int close = findClosing(tokens, i, to);
            if (close < 0) return -1;
            // the CTE body must itself be structurally read-only — this is the hole
            // my first version missed: WITH x AS (UPDATE ...) SELECT * FROM x
            if (!readOnlyStructure(tokens, open + 1, close)) {
                return -1;
            }
            i = close + 1;
            if (i < to && tokens.get(i).equals(",")) {
                i++;
                continue;
            }
            break;
        }
        return i;
    }

    /** EXPLAIN [ ( options ) ] [ANALYZE] — detect in both syntaxes within bounds. */
    private static boolean isExplainAnalyze(List<String> tokens, int from, int to) {
        int i = from + 1;
        if (i < to && tokens.get(i).equals("(")) {
            int depth = 0;
            while (i < to) {
                String t = tokens.get(i);
                if (t.equals("(")) depth++;
                else if (t.equals(")")) depth--;
                else if (t.equals("analyze") && depth == 1) return true;
                i++;
                if (depth == 0) break;
            }
        }
        return i < to && tokens.get(i).equals("analyze");
    }

    /** Returns index after the matching ')' of the '(' at from, or -1 if unbalanced. */
    private static int skipBalanced(List<String> tokens, int from, int to) {
        int close = findClosing(tokens, from, to);
        return close < 0 ? -1 : close + 1;
    }

    /** Returns index of the ')' matching the '(' at from, or -1. */
    private static int findClosing(List<String> tokens, int from, int to) {
        int depth = 0;
        for (int i = from; i < to; i++) {
            String t = tokens.get(i);
            if (t.equals("(")) depth++;
            else if (t.equals(")")) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static boolean isIdentifier(String t) {
        return !t.equals("(") && !t.equals(")") && !t.equals(",")
                && t.matches("[a-z_][a-z0-9_$]*");
    }

    private static List<String> tokenize(String stripped) {
        List<String> tokens = new ArrayList<>();
        var word = new StringBuilder();
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                word.append(Character.toLowerCase(c));
            } else {
                if (!word.isEmpty()) tokens.add(word.toString());
                word.setLength(0);
                if (c == '(' || c == ')' || c == ',') tokens.add(String.valueOf(c));
            }
        }
        if (!word.isEmpty()) tokens.add(word.toString());
        return tokens;
    }

    /** Replaces string/identifier literals with '?' and comments with space. */
    static String strip(String sql) {
        var out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';

            if (c == '-' && next == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
                out.append(' ');
                continue;
            }
            if (c == '/' && next == '*') {
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
                    if (sql.charAt(i) == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                        depth++;
                        i += 2;
                    } else if (sql.charAt(i) == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                out.append(' ');
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2; // '' stays inside the string
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                out.append('?');
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n && sql.charAt(i) != '"') i++;
                i++; // closing quote (doubling irrelevant: whole literal is masked)
                out.append('?');
                continue;
            }
            if (c == '$') {
                String tag = dollarTag(sql, i);
                if (tag != null) {
                    int close = sql.indexOf(tag, i + tag.length());
                    i = close < 0 ? n : close + tag.length();
                    out.append('?');
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Matches $tag$ (possibly empty tag) at pos; returns the full delimiter or null. */
    private static String dollarTag(String sql, int pos) {
        int i = pos + 1;
        while (i < sql.length()
                && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        if (i < sql.length() && sql.charAt(i) == '$') {
            return sql.substring(pos, i + 1);
        }
        return null;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
