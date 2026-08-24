package dev.s7a.strata.screen;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ScreensJavaApiTest {
    @Test
    void staticFacadePresentsWithoutKotlinObjectAccess() {
        ScreenDefinition definition = new ScreenDefinition("java", false, scope -> null);

        assertThrows(ScreenRuntimeUnavailableException.class, () -> Screens.open(definition));

        definition.close();
    }
}
