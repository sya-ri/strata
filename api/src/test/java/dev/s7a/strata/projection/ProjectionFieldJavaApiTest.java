package dev.s7a.strata.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.s7a.strata.render.DrawImage;
import dev.s7a.strata.text.UiText;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Passes the caller's actual Java array to the public vararg API without a Kotlin spread copy.
 */
final class ProjectionFieldJavaApiTest {
    @Test
    void snapshotsDirectVarargArraysBeforeReturningTheDeclaration() {
        ProjectionScope scope = new Scope();
        for (int count : new int[] {0, 1, 2, 4, 8, 128}) {
            ProjectionValue[] fields = new ProjectionValue[count];
            Arrays.fill(fields, new ProjectionValue.Text("shared"));
            ProjectionValue.Sequence expected = new ProjectionValue.Sequence(Arrays.asList(fields));
            DeclarationProjection<ProjectionValue.Sequence> declaration = BuiltinProjection.Grid.properties(fields);
            Arrays.fill(fields, ProjectionValue.Absent.INSTANCE);
            assertEquals(expected, declaration.encode(scope));
            assertEquals(expected.hashCode(), declaration.encode(scope).hashCode());
            if (1 < count) {
                ProjectionValue.Sequence value = (ProjectionValue.Sequence) declaration.encode(scope);
                assertSame(value.getValues().get(0), value.getValues().get(count - 1));
            }
            assertEquals(new ProjectionValue.Sequence(Arrays.asList(fields)),
                    BuiltinProjection.Grid.properties(fields).encode(scope));
            assertEquals(expected, declaration.encode(scope));
        }
    }

    /**
     * An array snapshot has no dependency on any server projection capability.
     */
    private static final class Scope implements ProjectionScope {
        @Override
        public long action(ProjectionAction<?> action, ProjectionValue key) {
            throw new AssertionError("Unexpected action");
        }

        @Override
        public <T> ProjectionValue binding(ProjectionBinding<T> binding) {
            throw new AssertionError("Unexpected binding");
        }

        @Override
        public ProjectionValue image(DrawImage image) {
            throw new AssertionError("Unexpected image");
        }

        @Override
        public ProjectionValue text(UiText text) {
            throw new AssertionError("Unexpected text");
        }

        @Override
        public void requireType(ProjectionType type) {
            throw new AssertionError("Unexpected nested type");
        }
    }
}
