package com.accubook.accounting.posting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q1 and Q7: the boundary itself.
 *
 * <p>The reference application's posting helper was public, so calling it was a
 * choice - ten call sites, six of which wrote {@code voucher} themselves. These
 * tests assert the Java equivalent cannot happen.
 */
class PostingBoundaryTest {

    @Test
    @DisplayName("the internal package is not exported, so the writer cannot be reached")
    void internalPackageIsNotExported() {
        Module module = PostingService.class.getModule();

        assertAll(
                () -> assertTrue(module.isNamed(), "the core must be a named module"),
                () -> assertTrue(module.isExported("com.accubook.accounting.posting"),
                        "the API must be usable"),
                () -> assertFalse(module.isExported("com.accubook.accounting.posting.internal"),
                        "the writer must not be reachable from outside the module"));
    }

    @Test
    @DisplayName("the writer's methods are not public, so they are unusable even if reached")
    void writerMethodsAreNotPublic() throws Exception {
        // Note what this test can and cannot show. It runs *inside*
        // accubook.accounting, so setAccessible succeeds here and asserting
        // otherwise would be asserting a falsehood - measured, not assumed.
        //
        // The real barriers are: the package is unexported (previous test), so
        // an outside module cannot compile against it or reflect into it without
        // --add-opens; and the P4 persistence guard refuses the write even if it
        // somehow did. What is checked here is the third, cheapest layer: no
        // method on the writer is public, so reaching the class is not enough.
        Class<?> writer = Class.forName("com.accubook.accounting.posting.internal.VoucherWriter");

        List<String> publicMethods = Arrays.stream(writer.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .toList();

        assertTrue(publicMethods.isEmpty(),
                "VoucherWriter must expose no public methods, found: " + publicMethods);
    }

    @Test
    @DisplayName("the public API offers no way to write a voucher other than post/reverse and their in-transaction forms")
    void publicApiIsMinimal() {
        List<String> mutators = Arrays.stream(PostingService.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .filter(name -> !name.equals("equals") && !name.equals("hashCode")
                        && !name.equals("toString"))
                .sorted()
                .toList();

        // postWithin/reverseWithin (P7c) move only the transaction boundary to
        // the caller, so a document and its stock movement commit together.
        // They run the same entitlement check and the same sealed writer.
        assertEqualsIgnoringOrder(List.of("post", "postWithin", "reverse", "reverseWithin"), mutators);
    }

    @Test
    @DisplayName("TransactionalPosting offers only the two in-transaction writes")
    void transactionalApiIsMinimal() {
        List<String> mutators = Arrays.stream(TransactionalPosting.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .sorted()
                .toList();

        assertEqualsIgnoringOrder(List.of("postWithin", "reverseWithin"), mutators);
    }

    @Test
    @DisplayName("the core does not depend on the licensing module")
    void coreDoesNotKnowAboutLicensing() {
        // It receives an EntitlementGuard and never learns that licences,
        // signatures or subscriptions exist - the P4 rule, still holding.
        var requires = PostingService.class.getModule().getDescriptor().requires().stream()
                .map(java.lang.module.ModuleDescriptor.Requires::name)
                .toList();

        assertAll(
                () -> assertFalse(requires.contains("accubook.licensing"),
                        "the posting core must not depend on licensing: " + requires),
                () -> assertFalse(requires.stream().anyMatch(r -> r.startsWith("javafx")),
                        "the posting core must not depend on JavaFX: " + requires));
    }

    private static void assertEqualsIgnoringOrder(List<String> expected, List<String> actual) {
        assertTrue(expected.containsAll(actual) && actual.containsAll(expected),
                "expected " + expected + " but was " + actual);
    }
}
