"""Verify real Gradle inventory, nested Maven isolation, disposable signing and archive rejection."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "release"))
import preparation
import verification


FIXTURE = r"""
plugins {
    id 'java-library'
    id 'maven-publish'
    id 'signing'
}
group = 'org.fixture'
version = '7.8.9'
abstract class FixtureRelease {
    abstract RegularFileProperty getReleaseNotesFile()
}
@CacheableTask
abstract class FreshVerification extends DefaultTask implements VerificationTask {
    boolean ignoreFailures
    @OutputFile
    abstract RegularFileProperty getReceipt()
    @TaskAction
    void verify() { receipt.get().asFile.text = UUID.randomUUID().toString() }
}
tasks.register('verifyFreshness', FreshVerification) {
    receipt = layout.buildDirectory.file('freshness.txt')
}
def release = extensions.create('strataRelease', FixtureRelease)
publishing.publications { maven(MavenPublication) { from components.java } }
signing {
    useInMemoryPgpKeys(System.getenv('FIXTURE_SIGNING_KEY'), '')
    sign publishing.publications.maven
}
tasks.register('verifyConsumer', GradleBuild) {
    dependsOn 'publishToMavenLocal'
    dir = file('consumer')
    tasks = ['check']
}
tasks.register('prepareFixture') {
    dependsOn tasks.verifyConsumer, tasks.verifyFreshness
    doLast {
        assert release.releaseNotesFile.get().asFile.text.contains('Non-publishing')
        assert file(System.getProperty('maven.repo.local')) == file(System.getenv('FIXTURE_MAVEN'))
    }
}
"""
CONSUMER = r"""
repositories { mavenLocal() }
configurations { fixture }
dependencies { fixture 'org.fixture:fixture:7.8.9' }
tasks.register('check') {
    doLast {
        assert file(System.getProperty('maven.repo.local')) == file(System.getenv('FIXTURE_MAVEN'))
        assert configurations.fixture.singleFile.isFile()
        assert configurations.fixture.singleFile.toPath().startsWith(file(System.getenv('FIXTURE_MAVEN')).toPath())
    }
}
"""


def run(command, **kwargs):
    """Retain a complete cause chain on failure without printing signing environment values."""
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", **kwargs)
    if result.returncode != 0:
        raise RuntimeError(f"Fixture command failed: {command[0]}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def main():
    """Exercise actual packaging locally; this fixture cannot contact publication services."""
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    with tempfile.TemporaryDirectory(prefix="strata-release-fixture-") as directory:
        root = Path(directory)
        product, repository, keyring = root / "product", root / "maven", root / "keyring"
        product.mkdir()
        repository.mkdir()
        keyring.mkdir(mode=0o700)
        gpg = ["gpg", "--homedir", preparation.prepared.gpg_home_path(keyring), "--batch", "--pinentry-mode", "loopback", "--passphrase", ""]
        run(gpg + ["--quick-generate-key", "Strata rehearsal fixture", "rsa2048", "sign", "0"])
        key = run(gpg + ["--armor", "--export-secret-keys"])
        environment = dict(os.environ, FIXTURE_SIGNING_KEY=key, FIXTURE_MAVEN=str(repository),
                           JAVA_TOOL_OPTIONS=f"-Dmaven.repo.local={repository.as_posix()}")
        (product / "settings.gradle").write_text("rootProject.name = 'fixture'\n", encoding="utf-8")
        (product / "build.gradle").write_text(FIXTURE, encoding="utf-8")
        java_source = product / "src/main/java/Fixture.java"
        java_source.parent.mkdir(parents=True)
        java_source.write_text("public class Fixture {}\n", encoding="utf-8")
        consumer = product / "consumer"
        consumer.mkdir()
        (consumer / "settings.gradle").write_text("rootProject.name = 'consumer'\n", encoding="utf-8")
        (consumer / "build.gradle").write_text(CONSUMER, encoding="utf-8")
        command = ([str(wrapper)] if os.name == "nt" else ["bash", str(wrapper)]) + ["--offline", "--no-daemon", "--no-configuration-cache", "--build-cache"]
        missing = subprocess.run(command + ["-p", str(consumer), "check"], env=environment, capture_output=True)
        assert missing.returncode != 0, "An empty isolated Maven repository must not resolve the fixture."
        notes = root / "notes.md"
        notes.write_text("# Non-publishing rehearsal fixture\n", encoding="utf-8")
        environment["STRATA_REHEARSAL_NOTES"] = str(notes)
        run(command + ["-p", str(product), "--init-script", str(ROOT / "release/verification-tasks.gradle"),
                       "--init-script", str(ROOT / "release/rehearsal-metadata.gradle"), "prepareFixture"], env=environment)
        tasks = json.loads((product / "build/release-verification/tasks.json").read_text())
        assert {":prepareFixture", ":verifyConsumer", ":publishToMavenLocal"} <= {task["path"] for task in tasks}
        freshness = product / "build/freshness.txt"
        first_receipt = freshness.read_text()
        freshness.unlink()
        compiled = product / "build/classes/java/main/Fixture.class"
        original_class = compiled.read_bytes()
        compiled.unlink()
        warm = run(command + ["-p", str(product), "--init-script", str(ROOT / "release/verification-tasks.gradle"),
                              "--init-script", str(ROOT / "release/rehearsal-metadata.gradle"),
                              "verifyFreshness", "compileJava"], env=environment)
        assert first_receipt != freshness.read_text(), "Warm build inputs must not restore prior verification evidence."
        assert ":compileJava FROM-CACHE" in warm and original_class == compiled.read_bytes(), "Compilation inputs must remain reusable."
        publication = repository / "org/fixture/fixture/7.8.9"
        for suffix in (".jar", ".pom", ".module"):
            assert (publication / f"fixture-7.8.9{suffix}.asc").is_file(), "Real Gradle signatures must be installed."
        release = product / "build/release"
        release.mkdir(parents=True)
        (release / "maven-coordinates.txt").write_text("org.fixture:fixture:7.8.9\n", encoding="utf-8")
        (release / "maven-files.txt").write_text("\n".join(f"org.fixture:fixture:{s}" for s in (".jar", ".pom", ".module")) + "\n", encoding="utf-8")
        distribution = release / "modrinth/artifacts/fixture-7.8.9.jar"
        distribution.parent.mkdir(parents=True)
        shutil.copyfile(publication / distribution.name, distribution)
        verification.write_json(release / "modrinth/manifest.json", dict(artifacts=[dict(
            mavenCoordinate="org.fixture:fixture:7.8.9", fileName=distribution.name,
            relativePath="artifacts/" + distribution.name, sha256=verification.digest(distribution), size=distribution.stat().st_size)]))
        previous = {name: os.environ.get(name) for name in ("ORG_GRADLE_PROJECT_signingInMemoryKey", "GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")}
        try:
            os.environ.update(ORG_GRADLE_PROJECT_signingInMemoryKey=key, GITHUB_SHA="b" * 40, GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1")
            output = root / "rehearsal"
            preparation.prepared.prepare(product, repository, output, "v7.8.9", "a" * 40, "", scope="rehearsal", notes=notes)
            preparation.prepared.validate(output, "v7.8.9", "a" * 40, scope="rehearsal")
            try:
                preparation.prepared.validate(output, "v7.8.9", "a" * 40)
            except ValueError:
                pass
            else:
                raise AssertionError("Production accepted a disposable-key rehearsal.")
        finally:
            for name, value in previous.items():
                if value is None:
                    os.environ.pop(name, None)
                else:
                    os.environ[name] = value
        print("Real task inventory, nested isolated consumer, disposable signatures and production rejection: passed", flush=True)


if __name__ == "__main__":
    main()
