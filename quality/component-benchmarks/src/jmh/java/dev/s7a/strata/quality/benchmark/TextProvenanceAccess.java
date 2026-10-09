package dev.s7a.strata.quality.benchmark;

import dev.s7a.strata.component.TextStyle;
import dev.s7a.strata.component.UiScope;
import dev.s7a.strata.element.Element;
import dev.s7a.strata.resource.ResourceId;
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile;
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory;
import dev.s7a.strata.text.TextLayout;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import dev.s7a.strata.text.UiText;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * Prebinds unchanged synthetic content contracts from the actually selected Minecraft runtime.
 * Timed calls use typed method handles; method discovery and retained-membership inspection are untimed.
 * The bridge owns no renderer, source state, cursor or content history.
 */
public final class TextProvenanceAccess {
    private final MethodHandle create;
    private final MethodHandle fontAt;
    private final MethodHandle slice;
    private final MethodHandle equivalent;
    private final MethodHandle renderer;
    private final MethodHandle evaluator;
    private final MethodHandle layout;
    private final MethodHandle run;
    private final Field fonts;
    private final Field starts;

    /** Selects exact existing runtime methods once, before any sampled operation. */
    public TextProvenanceAccess() {
        try {
            Class<?> type = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextContent");
            Object companion = type.getDeclaredField("Companion").get(null);
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            create = handle(lookup, method(companion.getClass(), "create", 3))
                    .bindTo(companion)
                    .asType(MethodType.methodType(Object.class, UiText.class, ResourceId.class, boolean.class));
            fontAt = handle(lookup, method(type, "fontAt", 1))
                    .asType(MethodType.methodType(ResourceId.class, Object.class, int.class));
            slice = handle(lookup, method(type, "slice", 2))
                    .asType(MethodType.methodType(UiText.class, Object.class, int.class, int.class));
            equivalent = handle(lookup, method(type, "equivalentTo", 1))
                    .asType(MethodType.methodType(boolean.class, Object.class, Object.class));
            Class<?> profileType = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftProfileImplementation");
            Object profileOwner = instance(profileType);
            renderer = handle(lookup, method(profileType, "createTextRenderer", 2))
                    .bindTo(profileOwner)
                    .asType(MethodType.methodType(Object.class, MinecraftUiProfile.class, MinecraftFontBackendFactory.class));
            evaluator = handle(lookup, method(profileType, "createEvaluator", 4))
                    .bindTo(profileOwner)
                    .asType(MethodType.methodType(Function0.class, MinecraftUiProfile.class, Function1.class, Object.class, Object.class));
            Class<?> lineType = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextLineBreaker");
            layout = handle(lookup, method(lineType, "create", 8))
                    .bindTo(instance(lineType))
                    .asType(MethodType.methodType(Object.class, Object.class, Object.class, TextLayout.Multiline.class, int.class, TextStyle.class, boolean.class, boolean.class, int.class));
            Class<?> rendererType = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextRenderer");
            run = handle(lookup, method(rendererType, "create", 5))
                    .asType(MethodType.methodType(Object.class, Object.class, UiText.class, TextStyle.class, boolean.class, ResourceId.class, boolean.class));
            fonts = type.getDeclaredField("fonts");
            fonts.setAccessible(true);
            Field boundaryField;
            try {
                boundaryField = type.getDeclaredField("runStarts");
                boundaryField.setAccessible(true);
            } catch (NoSuchFieldException denseBaseline) {
                boundaryField = null;
            }
            starts = boundaryField;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("The selected runtime does not expose the frozen content contracts.", failure);
        }
    }

    /** Constructs and validates complete prepared text through its actual runtime factory. */
    public Object create(UiText text, ResourceId inherited, boolean multiline) {
        try {
            return (Object) create.invokeExact(text, inherited, multiline);
        } catch (Throwable failure) {
            throw propagate(failure);
        }
    }

    /** Queries one original UTF-16 scalar offset without shared mutable lookup state. */
    public ResourceId fontAt(Object content, int offset) {
        try {
            return (ResourceId) fontAt.invokeExact(content, offset);
        } catch (Throwable failure) {
            throw propagate(failure);
        }
    }

    /** Copies one ordered scalar-aligned range through the unchanged slice contract. */
    public UiText slice(Object content, int first, int last) {
        try {
            return (UiText) slice.invokeExact(content, first, last);
        } catch (Throwable failure) {
            throw propagate(failure);
        }
    }

    /** Compares complete structure and provenance through the selected implementation. */
    public boolean equivalent(Object first, Object second) {
        try {
            return (boolean) equivalent.invokeExact(first, second);
        } catch (Throwable failure) {
            throw propagate(failure);
        }
    }

    /** Counts actual retained font references without estimating VM reference widths or object headers. */
    public int retainedFontSlots(Object content) {
        try {
            return ((List<?>) fonts.get(content)).size();
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Counts all actual retained primitive boundary slots; the original dense representation has none. */
    public int retainedBoundarySlots(Object content) {
        try {
            return starts == null ? 0 : ((int[]) starts.get(content)).length;
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Copies retained starts only during independent acceptance; no mutable target array escapes. */
    public int[] retainedStarts(Object content) {
        try {
            return starts == null ? new int[0] : ((int[]) starts.get(content)).clone();
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Identifies the representation from its actual fields at the adapter boundary. */
    public boolean usesRunStarts() {
        return starts != null;
    }

    /** Opens an independently owned renderer from the frozen profile and CPU backend. */
    public Object renderer(MinecraftUiProfile profile, MinecraftFontBackendFactory backend) {
        try {
            return (Object) renderer.invokeExact(profile, backend);
        } catch (Throwable failure) {
            throw propagate(failure);
        }
    }

    /** Prepares the actual public-DSL evaluator without evaluating or retaining any runtime content yet. */
    @SuppressWarnings("unchecked")
    public Function0<Element> evaluator(
            MinecraftUiProfile profile, Function1<? super UiScope, Unit> content, Object owner) {
        try {
            return (Function0<Element>) evaluator.invokeExact(profile, (Function1<?, ?>) content, (Object) null, owner);
        } catch (Throwable failure) {
            throw propagate(failure);
        }
    }

    /** Lays out an already prepared immutable content value through the actual complete line engine. */
    public Object layout(Object content, Object owner, TextLayout.Multiline policy,
            int width, TextStyle style, boolean logical, int height) {
        try {
            return (Object) layout.invokeExact(content, owner, policy, width, style, true, logical, height);
        } catch (Throwable failure) {
            throw propagate(failure);
        }
    }

    /** Includes complete original content validation, logical width and display shaping for a single-line run. */
    public Object run(UiText text, Object owner, ResourceId font, boolean logical) {
        try {
            return (Object) run.invokeExact(owner, text, TextStyle.ContainerLabel, true, font, logical);
        } catch (Throwable failure) {
            throw propagate(failure);
        }
    }

    /** Releases one fixture-owned renderer while keeping previously returned content and glyphs detached. */
    public void closeRenderer(Object owner) {
        try {
            ((AutoCloseable) owner).close();
        } catch (Exception failure) {
            throw propagate(failure);
        }
    }

    /** Reads one actual private field only during independent acceptance and retention checks. */
    public static Object field(Object owner, String name) {
        try {
            Field selected = member(owner.getClass(), name);
            selected.setAccessible(true);
            return selected.get(owner);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("A frozen retained owner field is unavailable: " + name, failure);
        }
    }

    private static Field member(Class<?> type, String name) throws NoSuchFieldException {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException missing) {
            Class<?> parent = type.getSuperclass();
            if (parent == null) {
                throw missing;
            }
            return member(parent, name);
        }
    }

    private static Object instance(Class<?> type) throws ReflectiveOperationException {
        Field field = type.getDeclaredField("INSTANCE");
        field.setAccessible(true);
        return field.get(null);
    }

    private static Method method(Class<?> type, String prefix, int parameterCount) {
        List<Method> matches = Arrays.stream(type.getDeclaredMethods())
                .filter(member -> member.getName().startsWith(prefix) && member.getParameterCount() == parameterCount)
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("The frozen runtime member is missing or ambiguous: " + prefix);
        }
        return matches.get(0);
    }

    private static MethodHandle handle(MethodHandles.Lookup lookup, Method method)
            throws IllegalAccessException {
        method.setAccessible(true);
        return lookup.unreflect(method);
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("A frozen runtime content operation failed.", failure);
    }
}
