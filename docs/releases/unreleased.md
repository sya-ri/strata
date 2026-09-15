# Unreleased changes

## Standard profile UUIDs

`PlayerSkinSource.Uuid.value` now uses `kotlin.uuid.Uuid` on every platform.
Use `Uuid.parse(text)` or `Uuid.fromLongs(mostSignificantBits, leastSignificantBits)` to create profile identifiers in shared code.
The standard parser accepts canonical dashed UUIDs and compact hexadecimal UUIDs; parsing follows the Kotlin standard-library contract on every target.
These APIs are stable with Kotlin 2.4 or newer; consumers still compiling with Kotlin 2.3 must opt in to `ExperimentalUuidApi` at UUID use sites.

On JVM, convert values from Java or Minecraft APIs at the boundary:

```kotlin
import dev.s7a.strata.component.PlayerSkinSource
import kotlin.uuid.toKotlinUuid

val source = PlayerSkinSource.Uuid(javaUuid.toKotlinUuid())
```

Use `source.value.toJavaUuid()` when an external Java API needs the identifier back.
Java source can use `UuidKt.toKotlinUuid(javaUuid)` and `UuidKt.toJavaUuid(source.getValue())` from `kotlin.uuid.UuidKt`.
Strata's native adapters perform the outbound conversion internally.

This changes the JVM constructor, property getter, and generated data-class method descriptors of `PlayerSkinSource.Uuid`; recompile existing consumers and update constructor references to conversion lambdas.
The earlier Java UUID descriptors cannot be retained by a type alias or a deprecated overload alone because the property and generated methods also return the old type.
Other player-skin source variants are unchanged.
