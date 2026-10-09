package com.accubook.persistence.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Statement classification, which decides what the guard even looks at. */
class GuardedStatementsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO payroll_runs (id) VALUES (1)",
            "insert into payroll_runs (id) values (1)",
            "  \n UPDATE payroll_runs SET x = 1",
            "DELETE FROM payroll_runs WHERE id = 1",
            "REPLACE INTO payroll_runs VALUES (1)",
            "INSERT OR REPLACE INTO payroll_runs VALUES (1)"})
    @DisplayName("mutations are recognised regardless of case, spacing or conflict clause")
    void recognisesMutations(String sql) {
        assertTrue(GuardedStatements.isMutating(sql), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM payroll_runs",
            "BEGIN IMMEDIATE",
            "COMMIT",
            "ROLLBACK",
            "PRAGMA foreign_keys = ON",
            "CREATE TABLE payroll_runs (id INTEGER)",
            "VACUUM INTO '/tmp/x.db'",
            "ALTER TABLE payroll_runs ADD COLUMN x TEXT"})
    @DisplayName("reads, transaction control and schema operations are not data mutations")
    void ignoresNonMutations(String sql) {
        assertFalse(GuardedStatements.isMutating(sql), sql);
    }

    @Test
    @DisplayName("transaction control must never be filtered - a swallowed ROLLBACK would commit a half-written voucher")
    void transactionControlIsNeverMutating() {
        assertFalse(GuardedStatements.isMutating("ROLLBACK"));
        assertFalse(GuardedStatements.isMutating("COMMIT"));
        assertFalse(GuardedStatements.isMutating("BEGIN IMMEDIATE"));
    }

    @ParameterizedTest
    @CsvSource({
            "'INSERT INTO payroll_runs (id) VALUES (1)', payroll_runs",
            "'INSERT OR IGNORE INTO payroll_runs VALUES (1)', payroll_runs",
            "'UPDATE  Payroll_Runs  SET x = 1', payroll_runs",
            "'DELETE FROM \"payroll_runs\" WHERE id = 1', payroll_runs",
            "'REPLACE INTO payroll_runs VALUES (1)', payroll_runs"})
    @DisplayName("the target table is extracted and normalised to lower case")
    void extractsTable(String sql, String expected) {
        assertEquals(expected, GuardedStatements.targetTable(sql).orElseThrow());
    }

    @Test
    @DisplayName("a comment or a WITH clause cannot hide a mutation or its table")
    void seesThroughCommentsAndCtes() {
        assertMutationOf("/* note */ INSERT INTO payroll_runs VALUES (1)", "payroll_runs");
        assertMutationOf("-- why\n  DELETE FROM payroll_runs", "payroll_runs");
        assertMutationOf("WITH x AS (SELECT 1) INSERT INTO payroll_runs SELECT * FROM x", "payroll_runs");
        assertMutationOf("WITH x AS (SELECT 1) UPDATE payroll_runs SET a = 1", "payroll_runs");
    }

    private static void assertMutationOf(String sql, String table) {
        assertTrue(GuardedStatements.isMutating(sql), sql);
        assertEquals(table, GuardedStatements.targetTable(sql).orElseThrow(), sql);
    }

    @Test
    @DisplayName("a read-only WITH query is not a mutation")
    void readOnlyCteIsNotMutating() {
        assertFalse(GuardedStatements.isMutating("WITH x AS (SELECT 1) SELECT * FROM x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DROP TABLE payroll_runs",
            "/* x */ ALTER TABLE payroll_runs ADD COLUMN y TEXT",
            "CREATE TRIGGER t AFTER INSERT ON a BEGIN DELETE FROM payroll_runs; END",
            "ATTACH DATABASE '/tmp/other.db' AS other",
            "COMMIT",
            "BEGIN",
            "ROLLBACK",
            "PRAGMA query_only = 0",
            "PRAGMA foreign_keys = OFF",
            "PRAGMA query_only(0)",
            "VACUUM",
            "SOMETHING_NEW payroll_runs"})
    @DisplayName("statements a guarded unit of work may never run are refused")
    void forbidsEscapes(String sql) {
        assertTrue(GuardedStatements.forbiddenReason(sql).isPresent(), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1",
            "INSERT INTO invoices VALUES (1)",
            "SAVEPOINT s1",
            "RELEASE s1",
            "ROLLBACK TO s1",
            "PRAGMA table_info(invoices)",
            "PRAGMA foreign_key_check",
            "PRAGMA user_version"})
    @DisplayName("ordinary reads, writes, savepoints and reporting pragmas are not forbidden")
    void permitsOrdinaryStatements(String sql) {
        assertTrue(GuardedStatements.forbiddenReason(sql).isEmpty(), sql);
    }

    @Test
    @DisplayName("null and blank are handled without throwing")
    void handlesNullAndBlank() {
        assertFalse(GuardedStatements.isMutating(null));
        assertFalse(GuardedStatements.isMutating("   "));
        assertTrue(GuardedStatements.targetTable(null).isEmpty());
    }
}
