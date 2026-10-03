package dev.s7a.strata.gradle.fabric

import net.fabricmc.loom.task.prod.ClientProductionRunTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath

/**
 * Runs Loom's production client with ordinary verification libraries on its Java classpath.
 * Libraries remain separate JARs and do not become Fabric Mods or part of a published runtime.
 */
public abstract class LibraryClientProductionRunTask : ClientProductionRunTask() {
    /**
     * Test-only library artifacts whose actual bytes are available to the production host loader.
     */
    @get:Classpath
    public abstract val verificationLibraries: ConfigurableFileCollection

    init {
        classpath.from(verificationLibraries)
    }
}
