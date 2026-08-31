package dev.s7a.strata.modifier;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import kotlin.Unit;
import org.junit.jupiter.api.Test;

final class ActivationExtensionsJavaApiTest {
    @Test
    void bothActivationOverloadsCompileFromJava() {
        Modifier empty = Modifier.Companion.getEmpty();
        Modifier enabled = ActivationExtensionsKt.onActivate(empty, () -> Unit.INSTANCE);
        Modifier disabled = ActivationExtensionsKt.onActivate(empty, false, () -> Unit.INSTANCE);

        assertNotSame(empty, enabled);
        assertSame(empty, disabled);
    }
}
