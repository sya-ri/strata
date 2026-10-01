"""Exercise publication selection and derived signatures with real Gradle APIs."""

import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
FIXTURE = r"""
apply plugin: 'java'
apply plugin: 'maven-publish'
apply plugin: 'signing'
group = 'fixture'
version = '1'
tasks.register('sourcesJar', Jar) {
    archiveClassifier = 'sources'
    destinationDirectory = layout.buildDirectory.dir('devlibs')
}
tasks.register('remapSourcesJar', Jar) {
    archiveClassifier = 'sources'
}
tasks.register('otherSourcesJar', Jar) {
    archiveClassifier = 'sources'
    destinationDirectory = layout.buildDirectory.dir('other')
}
java { withSourcesJar() }
configurations.sourcesElements.outgoing.artifacts.clear()
configurations.sourcesElements.outgoing.artifact(tasks.remapSourcesJar) { classifier = 'sources' }
publishing.publications {
    maven(MavenPublication) {
        from components.java
        if (findProperty('fixtureCase') in ['duplicate', 'unexpected']) artifact tasks.sourcesJar
        if (findProperty('fixtureCase') == 'unexpected') artifact tasks.otherSourcesJar
    }
}
if (findProperty('fixtureCase') == 'missing') {
    publishing.publications.maven.artifacts.removeAll { it.classifier == 'sources' }
}
signing { sign publishing.publications.maven }
tasks.register('verifySelection') {
    dependsOn tasks.jar, tasks.sourcesJar, tasks.remapSourcesJar, tasks.otherSourcesJar, tasks.generateMetadataFileForMavenPublication
    doLast {
        def publication = publishing.publications.maven
        def sources = publication.artifacts.findAll { it.classifier == 'sources' }
        assert sources.size() == 1
        assert sources[0].file == tasks.remapSourcesJar.archiveFile.get().asFile
        def signatures = tasks.signMavenPublication.signatures.findAll { it.toSign.name.endsWith('-sources.jar') }
        assert signatures.size() == 1
        assert signatures[0].toSign == sources[0].file
        def metadata = new groovy.json.JsonSlurper().parse(tasks.generateMetadataFileForMavenPublication.outputFile.get().asFile)
        def sourceVariants = metadata.variants.findAll { it.attributes['org.gradle.docstype'] == 'sources' }
        assert sourceVariants.size() == 1
        assert sourceVariants[0].files.size() == 1
        assert sourceVariants[0].files[0].name == sources[0].file.name
    }
}
"""


def main():
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    command = [str(wrapper)] if os.name == 'nt' else ['bash', str(wrapper)]
    with tempfile.TemporaryDirectory(prefix='strata-fabric-sources-') as directory:
        fixture = Path(directory)
        (fixture / 'runtime').mkdir()
        (fixture / 'settings.gradle').write_text("rootProject.name = 'fixture'\ninclude 'runtime'\n", encoding='utf-8')
        (fixture / 'build.gradle').write_text("project(':runtime') {\n" + FIXTURE + "\n}\n", encoding='utf-8')
        for case in ('unique', 'duplicate', 'unexpected', 'missing'):
            result = subprocess.run(
                command + ['--no-daemon', '--offline', '-p', str(fixture),
                           '--init-script', str(ROOT / 'release/prepare-fabric-sources.gradle'),
                           f'-PfixtureCase={case}', ':runtime:verifySelection'],
                capture_output=True, text=True, encoding='utf-8', errors='replace',
            )
            expected_success = case in ('unique', 'duplicate')
            if (result.returncode == 0) != expected_success:
                raise RuntimeError(f'{case}: unexpected Gradle result\n{result.stdout}\n{result.stderr}')
            if not expected_success and 'Unexpected Fabric sources inventory' not in result.stderr:
                raise RuntimeError(f'{case}: failed outside the inventory guard\n{result.stderr}')
            print(f'{case}: passed', flush=True)


if __name__ == '__main__':
    main()
