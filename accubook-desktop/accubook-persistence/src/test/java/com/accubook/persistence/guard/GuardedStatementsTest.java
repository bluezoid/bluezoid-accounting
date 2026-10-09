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
    @DisplayName("reads, transaction control and schema operations are not gated")
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
    @DisplayName("null and blank are handled without throwing")
    void handlesNullAndBlank() {
        assertFalse(GuardedStatements.isMutating(null));
        assertFalse(GuardedStatements.isMutating("   "));
        assertTrue(GuardedStatements.targetTable(null).isEmpty());
    }
}
