package dev.s7a.strata.runtime.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.s7a.strata.projection.ProjectionValue;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Checks actual Java-array isolation at the internal remote property boundary and its binary codec.
 */
final class RemotePropertyArrayTest {
    @Test
    void recordDoesNotRetainTheCallerArrayOrChangeEncodedBytes() {
        RemoteValueCodec codec = new RemoteValueCodec();
        ProjectionValue nested = new ProjectionValue.Sequence(Arrays.asList(
                new ProjectionValue.Integer(7), new ProjectionValue.Text("nested")));
        for (int count : new int[] {0, 1, 2, 4, 8, 128}) {
            ProjectionValue[] fields = new ProjectionValue[count];
            Arrays.fill(fields, nested);
            ProjectionValue expected = new ProjectionValue.Sequence(Arrays.asList(fields));
            ProjectionValue old = RemoteProperties.INSTANCE.record(fields);
            byte[] expectedBytes = codec.encode(expected);
            Arrays.fill(fields, ProjectionValue.Absent.INSTANCE);
            ProjectionValue fresh = RemoteProperties.INSTANCE.record(fields);
            assertEquals(expected, old);
            assertArrayEquals(expectedBytes, codec.encode(old));
            assertEquals(old, codec.decode(expectedBytes));
            assertEquals(new ProjectionValue.Sequence(Arrays.asList(fields)), fresh);
            if (1 < count) {
                ProjectionValue.Sequence sequence = (ProjectionValue.Sequence) old;
                assertSame(sequence.getValues().get(0), sequence.getValues().get(count - 1));
            }
            assertEquals(expected, old);
        }
    }
}
