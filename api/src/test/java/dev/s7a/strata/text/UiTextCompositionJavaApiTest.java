package dev.s7a.strata.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Calls the public varargs ABI directly so caller array ownership is checked without Kotlin spread copies.
 */
final class UiTextCompositionJavaApiTest {
    @Test
    void constructorsAndFactoriesDetachTheCallerArray() {
        UiText first = new UiText.Literal("first");
        UiText second = new UiText.Literal("second");
        UiText[] source = {first, second, first};
        UiText.Concatenated constructed = new UiText.Concatenated(source);
        UiText.Concatenated combined = (UiText.Concatenated) UiText.Companion.concat(source);
        List<UiText> expected = List.of(first, second, first);
        int hash = constructed.hashCode();
        String text = constructed.toString();
        source[0] = second;
        source[1] = first;

        assertEquals(expected, constructed.getParts());
        assertEquals(expected, combined.getParts());
        assertSame(first, constructed.getParts().get(0));
        assertSame(first, combined.getParts().get(2));
        assertEquals(constructed, combined);
        assertEquals(hash, constructed.hashCode());
        assertEquals(text, constructed.toString());
    }

    @Test
    void singletonConstructorDetachesTheCallerArray() {
        UiText first = new UiText.Literal("first");
        UiText[] source = {first};
        UiText.Concatenated constructed = new UiText.Concatenated(source);
        UiText combined = UiText.Companion.concat(source);
        source[0] = new UiText.Literal("replacement");

        assertSame(first, constructed.getParts().get(0));
        assertSame(first, combined);
    }
}
