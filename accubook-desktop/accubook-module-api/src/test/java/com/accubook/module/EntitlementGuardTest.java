package com.accubook.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The use-case layer's guard.
 *
 * <p>This layer catches the common case with a good error message; the
 * persistence guard behind it catches what a developer forgets to route through
 * here.
 */
class EntitlementGuardTest {

    private static final ModuleId PAYROLL = ModuleId.of("payroll");

    private static EntitlementGuard guardReturning(ModuleState state) {
        return new EntitlementGuard(moduleId -> state);
    }

    @Test
    @DisplayName("an ACTIVE module may mutate")
    void activePermitsMutation() {
        EntitlementGuard guard = guardReturning(ModuleState.ACTIVE);

        guard.requireWritable(PAYROLL, "run payroll");

        assertTrue(guard.isWritable(PAYROLL));
    }

    @ParameterizedTest
    @EnumSource(value = ModuleState.class, names = {"READ_ONLY", "DORMANT", "ABSENT"})
    @DisplayName("every non-active state refuses mutation")
    void nonActiveRefusesMutation(ModuleState state) {
        EntitlementGuard guard = guardReturning(state);

        ModuleNotWritableException e = assertThrows(ModuleNotWritableException.class,
                () -> guard.requireWritable(PAYROLL, "run payroll"));

        assertAll(
                () -> assertEquals(state, e.state()),
                () -> assertEquals(PAYROLL, e.moduleId()),
                () -> assertEquals("run payroll", e.operation()),
                () -> assertFalse(guard.isWritable(PAYROLL)));
    }

    @ParameterizedTest
    @EnumSource(value = ModuleState.class, names = {"ACTIVE", "READ_ONLY", "DORMANT"})
    @DisplayName("reading stays available in every state but ABSENT - history is never withheld")
    void readingIsNotGated(ModuleState state) {
        assertTrue(guardReturning(state).isReadable(PAYROLL));
    }

    @Test
    @DisplayName("an ABSENT module has nothing to read")
    void absentIsNotReadable() {
        assertFalse(guardReturning(ModuleState.ABSENT).isReadable(PAYROLL));
    }

    @Test
    @DisplayName("the message names the operation and says the data is safe")
    void messageIsActionable() {
        EntitlementGuard guard = guardReturning(ModuleState.READ_ONLY);

        ModuleNotWritableException e = assertThrows(ModuleNotWritableException.class,
                () -> guard.requireWritable(PAYROLL, "run payroll for September"));

        assertAll(
                () -> assertTrue(e.getMessage().contains("run payroll for September"), e.getMessage()),
                () -> assertTrue(e.getMessage().contains("remain available"), e.getMessage()));
    }

    @Test
    @DisplayName("a downgraded module points at the records viewer, not at data loss")
    void dormantMessageMentionsViewer() {
        EntitlementGuard guard = guardReturning(ModuleState.DORMANT);

        ModuleNotWritableException e = assertThrows(ModuleNotWritableException.class,
                () -> guard.requireWritable(PAYROLL, "run payroll"));

        assertTrue(e.getMessage().contains("records viewer"), e.getMessage());
    }

    @Test
    @DisplayName("stateOf reports the state for display")
    void reportsState() {
        assertEquals(ModuleState.READ_ONLY, guardReturning(ModuleState.READ_ONLY).stateOf(PAYROLL));
    }
}
