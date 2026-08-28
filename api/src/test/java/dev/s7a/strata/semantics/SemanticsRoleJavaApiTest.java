package dev.s7a.strata.semantics;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

final class SemanticsRoleJavaApiTest {
    @Test
    void multilineRoleIsTypedAndDistinctFromTheLegacySingleLineRole() {
        SemanticsRole role = SemanticsRole.TextArea.INSTANCE;

        assertSame(SemanticsRole.TextArea.INSTANCE, role);
        assertNotEquals(SemanticsRole.TextField.INSTANCE, role);
    }
}
