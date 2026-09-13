import java.security.MessageDigest

val componentDirectory = layout.projectDirectory.dir("src/main/kotlin/dev/s7a/strata/component")
val componentInputs = listOf("ProfileComponents.kt", "TextInputComponents.kt", "CanvasComponents.kt", "TiledImageComponents.kt", "VirtualListComponents.kt", "SelectionListComponents.kt").map { componentDirectory.file(it).asFile }
val componentOutput = componentDirectory.file("ObservedComponents.kt").asFile
val componentCompileOutput = layout.projectDirectory.file("src/test/kotlin/dev/s7a/strata/component/ObservedComponentCompileCases.kt").asFile
val reactiveParameters = mapOf(
    "ProgressBar" to setOf("progress"),
    "Image" to setOf("source"),
    "PlayerHead" to setOf("source"),
    "Canvas" to setOf("source"),
    "TiledImage" to setOf("source"),
    "Slot" to setOf("bind", "highlightable"),
    "Button" to setOf("label", "enabled"),
    "Tab" to setOf("label", "selected", "enabled"),
    "Checkbox" to setOf("label", "enabled"),
    "Slider" to setOf("label", "enabled"),
    "CycleButton" to setOf("label", "enabled"),
    "TextField" to setOf("enabled"),
    "TextArea" to setOf("enabled"),
    "VirtualList" to setOf("items", "canLoadLeading", "canLoadTrailing"),
    "SelectionList" to setOf("items", "canLoadLeading", "canLoadTrailing"),
)

/** Generates every nonempty reactive-argument combination from the maintained literal declarations. */
fun stateComponentOverloads(compileCases: Boolean = false): String {
    val inputs = componentInputs.map { it.readText().replace("\r\n", "\n") }
    val declarations = Regex("(?m)^public fun (?:(<[^\\n]+>) )?UiScope\\.(\\w+)\\(\\n([\\s\\S]*?)^\\) \\{")
    val functions = buildString {
        inputs.forEach { source ->
            declarations.findAll(source).forEach declaration@{ match ->
                val generics = match.groupValues[1]
                val name = match.groupValues[2]
                val selected = reactiveParameters[name] ?: return@declaration
                val parameters = match.groupValues[3].trimEnd().lines().map { line ->
                    check(line.startsWith("    ") && line.trim().endsWith(",")) { "Unsupported component parameter layout: $name: $line" }
                    line.trim().removeSuffix(",")
                }
                val names = parameters.map { it.substringBefore(":") }
                if (name == "PlayerHead" && "scale" !in names) return@declaration
                val reactive = names.filter { it in selected }
                check(reactive.isNotEmpty()) { "Reactive component $name has no selected parameters." }
                val identity = "$name:$generics:${parameters.joinToString { it.substringAfter(": ").substringBefore(" = ") }}"
                val signature = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
                for (mask in 1 until (1 shl reactive.size)) {
                    val sources = reactive.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                    appendLine()
                    if (compileCases.not()) {
                        appendLine("/**")
                        appendLine(" * Observes ${sources.joinToString()} for [$name] at the next owner-thread frame cutoff.")
                        appendLine(" *")
                        appendLine(" * Retain caller-owned sources and editing state outside reevaluation.")
                        appendLine(" * See [Observe] for shared snapshots, equal-value suppression, and cleanup; literal arguments follow [$name].")
                        appendLine(" * Modifiers and parent data apply to the actual component, while the binding owns its sibling key.")
                        appendLine(" */")
                    }
                    if (compileCases.not()) appendLine("@JvmName(\"${name}State${signature}_$mask\")")
                    val functionName = if (compileCases) "compile${name}${signature}_$mask" else name
                    appendLine("${if (compileCases) "private" else "public"} fun ${if (generics.isEmpty()) "" else "$generics "}UiScope.$functionName(")
                    parameters.forEachIndexed { index, parameter ->
                        val parameterName = names[index]
                        if (parameterName in sources) {
                            val type = parameter.substringAfter(": ").substringBefore(" = ")
                            appendLine("    $parameterName: StateSource<$type>,")
                        } else {
                            appendLine("    $parameter,")
                        }
                    }
                    appendLine(") {")
                    if (compileCases.not()) appendLine("    emitObservedComponent(listOf(${sources.joinToString()}), key) { values ->")
                    val callIndent = if (compileCases) "    " else "        "
                    appendLine("$callIndent$name(")
                    parameters.forEachIndexed { index, parameter ->
                        val parameterName = names[index]
                        val sourceIndex = sources.indexOf(parameterName)
                        val expression = when {
                            compileCases -> parameterName
                            parameterName == "key" -> "null"
                            sourceIndex < 0 -> parameterName
                            else -> "values[$sourceIndex] as ${parameter.substringAfter(": ").substringBefore(" = ")}"
                        }
                        appendLine("$callIndent    $parameterName = $expression,")
                    }
                    appendLine("$callIndent)")
                    if (compileCases.not()) appendLine("    }")
                    appendLine("}")
                }
            }
        }
    }
    if (compileCases.not()) reactiveParameters.keys.forEach { name -> check("UiScope.$name(" in functions) { "Missing generated component: $name" } }
    val imports = inputs.flatMap { source -> source.lineSequence().filter { it.startsWith("import ") }.toList() }
        .filter { line -> Regex("\\b${Regex.escape(line.substringAfterLast('.'))}\\b").containsMatchIn(functions) }
        .plus("import dev.s7a.strata.state.StateSource")
        .plus(if (compileCases) emptyList() else listOf("import kotlin.jvm.JvmName")).distinct().sorted()
    return buildString {
        appendLine("// Generated by :api:generateStateComponentOverloads. Do not edit by hand.")
        appendLine("@file:Suppress(\"FunctionNaming\", \"ktlint:standard:function-naming\", \"LongParameterList\", \"TooManyFunctions\", \"UNCHECKED_CAST\", \"unused\")")
        appendLine()
        appendLine("package dev.s7a.strata.component")
        appendLine()
        imports.forEach(::appendLine)
        append(functions)
    }
}

tasks.register("generateStateComponentOverloads") {
    group = "generation"
    description = "Generates typed literal/source combinations for reactive component inputs."
    inputs.files(componentInputs)
    inputs.file(layout.projectDirectory.file("state-components.gradle.kts"))
    outputs.file(componentOutput)
    outputs.file(componentCompileOutput)
    doLast {
        componentOutput.writeText(stateComponentOverloads(), Charsets.UTF_8)
        componentCompileOutput.parentFile.mkdirs()
        componentCompileOutput.writeText(stateComponentOverloads(compileCases = true), Charsets.UTF_8)
    }
}
val checkStateComponentOverloads = tasks.register("checkStateComponentOverloads") {
    group = "verification"
    description = "Checks reactive component generation without changing tracked sources."
    inputs.files(componentInputs)
    inputs.file(layout.projectDirectory.file("state-components.gradle.kts"))
    inputs.file(componentOutput)
    inputs.file(componentCompileOutput)
    doLast {
        check(componentOutput.readText().replace("\r\n", "\n") == stateComponentOverloads()) {
            "Reactive component overloads are stale. Run :api:generateStateComponentOverloads."
        }
        check(componentCompileOutput.readText().replace("\r\n", "\n") == stateComponentOverloads(compileCases = true)) {
            "Reactive component compile cases are stale. Run :api:generateStateComponentOverloads."
        }
    }
}
tasks.named("check") { dependsOn(checkStateComponentOverloads) }
tasks.matching { it.name != "generateStateComponentOverloads" }.configureEach {
    mustRunAfter("generateStateComponentOverloads")
}
