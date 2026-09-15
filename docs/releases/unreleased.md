# Unreleased changes

## Platform-native profile UUIDs

`ProfileUuid` names the platform's standard UUID type: `java.util.UUID` on JVM and `kotlin.uuid.Uuid` on JavaScript.
JavaScript parsing, formatting, equality, and hashing use the Kotlin standard library.

Shared code creates identifiers with `parseProfileUuid(text)`.
Canonical dashed hexadecimal UUID text is accepted on every target; additional accepted forms follow the platform's standard parser.
Platform-specific code can also use native UUID constructors or factories.

`PlayerSkinSource.Uuid` retains its Java UUID constructor, property getter, and generated data-class JVM descriptors.
Existing JVM consumers, including Java and Minecraft callers, require no UUID conversion or source migration.
