"""Exercise input refusal without starting Java; real Gradle selection has separate functional tests."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("controlled_toolchains", Path(__file__).parents[1] / "controlled_toolchains.py")
TOOLS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOLS)


class ControlledToolchainsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "gradle").mkdir()
        (self.root / "gradle/libs.versions.toml").write_text('[versions]\njava-baseline="17"\njava-minecraft="25"\njava-minecraft121="21"\n')
        (self.root / "gradle.properties").write_text("org.gradle.java.installations.auto-download=false\n")
        self.user = self.root / "gradle-user"
        self.user.mkdir()
        self.distribution = self.root / "gradle-installation"
        self.distribution.mkdir()
        jdks = []
        for major in (17, 21, 25):
            home = self.root / f"jdk {major}"
            (home / "bin").mkdir(parents=True)
            (home / "lib").mkdir()
            (home / "release").write_text(f'JAVA_VERSION="{major}.0.1"\nIMPLEMENTOR="Fixture Vendor"\n')
            for name in ("bin/java", "bin/javac", "bin/javadoc", "lib/modules"):
                (home / name).write_bytes(b"synthetic-source-control")
            jdks.append(dict(TOOLS.installation(home), vendor="Fixture Vendor", runtime_version=f"{major}.0.1+4", jvm_version=f"{major}.0.1+4"))
        self.profile = {"schema": 1, "model": "complete", "daemon_major": 25, "gradle_version": "fixture",
                        "jdks": jdks, "gradle_user_home": self.user.as_posix(), "daemon_criteria": None,
                        "gradle_installation_home": self.distribution.as_posix(), "inherited_environment": {},
                        "inherited_properties": {"user": {}, "project": TOOLS.properties(self.root / "gradle.properties"), "installation": {}}}
        self.environment = {"JAVA_HOME": jdks[-1]["home"], "GRADLE_USER_HOME": self.user.as_posix()}
        self.profile_path = self.root / "profile.json"
        self.arguments = [":consumer:test", ":fixtures:compileJava", "--no-daemon", "--no-parallel", "--max-workers=1"]

    def bind(self, *, bounded=True):
        self.profile_path.write_text(json.dumps(self.profile))
        return TOOLS.bind(self.profile_path, self.root, self.environment, self.arguments, self.root / "invocation", bounded=bounded)

    def receipt(self, manifest):
        binding = json.loads(manifest.read_text())
        Path(binding["receipts"]).mkdir()
        binding["receipt"] = str(Path(binding["receipts"]) / "root.json")
        jdk = self.profile["jdks"][0]
        daemon = self.profile["jdks"][-1]
        receipt = {"status": "passed", "profile_sha256": binding["profile_sha256"], "gradle_version": "fixture",
                   "build_path": ":", "project_dir": str(self.root), "parent_build_path": None, "included_builds": [],
                   "daemon": dict(daemon, vendor=daemon["implementor"]),
                   "tools": [dict(jdk, task=":consumer:test", role="launcher", executable=jdk["java"])],
                   "tasks": [{"path": ":consumer:test", "did_work": True, "kotlin_major": None}]}
        Path(binding["receipt"]).write_text(json.dumps(receipt))
        log = self.root / "gradle.log"
        log.write_text("BUILD SUCCESSFUL\n")
        return binding, receipt, log

    def test_complete_model_preserves_requested_work_and_bounds_all_catalog_jdks(self):
        arguments, environment, manifest = self.bind()
        self.assertEqual(self.arguments, arguments[:len(self.arguments)])
        self.assertIn("-Dorg.gradle.java.installations.auto-detect=false", arguments)
        self.assertIn("-Dorg.gradle.java.installations.auto-download=false", arguments)
        self.assertIn("-Dorg.gradle.java.installations.fromEnv=", arguments)
        self.assertEqual(str(manifest), environment["STRATA_TOOLCHAIN_BINDING"])
        self.assertEqual(3, len(json.loads(manifest.read_text())["profile"]["jdks"]))

    def test_jvm_model_uses_existing_catalog_closure(self):
        self.profile["model"] = "jvmOnly"
        self.profile["jdks"].pop(1)
        self.arguments.insert(0, "-Pstrata.jvmOnly=true")
        self.bind()

    def test_unbounded_control_keeps_existing_discovery_and_identical_work(self):
        arguments, _, manifest = self.bind(bounded=False)
        self.assertEqual(self.arguments, arguments[:len(self.arguments)])
        self.assertEqual(["org.gradle.java.home"], list(json.loads(manifest.read_text())["properties"]))
        self.assertFalse(any(TOOLS.INSTALLATIONS in argument for argument in arguments))

    def test_full_model_refuses_missing_java21(self):
        self.profile["jdks"].pop(1)
        with self.assertRaisesRegex(ValueError, "Missing"):
            self.bind()

    def test_duplicate_version_and_model_mismatch_are_refused(self):
        self.profile["jdks"].append(copy.deepcopy(self.profile["jdks"][0]))
        with self.assertRaises(ValueError):
            self.bind()
        self.profile["jdks"].pop()
        self.arguments.append("-Pstrata.jvmOnly=true")
        with self.assertRaisesRegex(ValueError, "model differs"):
            self.bind()

    def test_missing_non_jdk_malformed_and_relative_homes_are_refused(self):
        for value in ("relative", str(self.root / "missing"), self.root.as_posix(), "bad,home", "bad\nhome", None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                TOOLS.absolute_home(value) if value in ("relative", "bad,home", "bad\nhome", None) else TOOLS.installation(value)

    def test_changed_library_bytes_and_missing_compiler_are_refused(self):
        modules = Path(self.profile["jdks"][0]["home"]) / "lib/modules"
        modules.write_bytes(b"different-library")
        with self.assertRaisesRegex(ValueError, "identity changed"):
            self.bind()
        modules.write_bytes(b"synthetic-source-control")
        Path(self.profile["jdks"][0]["javac"]).unlink()
        with self.assertRaisesRegex(ValueError, "missing"):
            self.bind()

    def test_malformed_release_and_declared_identity_are_refused(self):
        release = Path(self.profile["jdks"][0]["home"]) / "release"
        release.write_text('JAVA_VERSION="17"\nJAVA_VERSION="21"\n')
        with self.assertRaisesRegex(ValueError, "Malformed"):
            self.bind()

    def test_java_home_and_user_home_must_match(self):
        self.environment["JAVA_HOME"] = self.profile["jdks"][0]["home"]
        with self.assertRaisesRegex(ValueError, "JAVA_HOME"):
            self.bind()
        self.environment["JAVA_HOME"] = self.profile["jdks"][-1]["home"]
        self.environment["GRADLE_USER_HOME"] = str(self.root)
        with self.assertRaisesRegex(ValueError, "user home"):
            self.bind()

    def test_inherited_path_expansion_is_refused_even_when_overridden(self):
        (self.user / "gradle.properties").write_text(f"org.gradle.java.installations.paths={self.root.as_posix()}\n")
        with self.assertRaisesRegex(ValueError, "JDK set"):
            self.bind()

    def test_inherited_from_env_expansion_missing_and_bound_values(self):
        file = self.user / "gradle.properties"
        file.write_text("org.gradle.java.installations.fromEnv=EXTRA_JDK\n")
        for value in (None, self.root.as_posix()):
            if value is not None:
                self.environment["EXTRA_JDK"] = value
            with self.assertRaises(ValueError):
                self.bind()
        self.environment["EXTRA_JDK"] = self.profile["jdks"][0]["home"]
        self.profile["inherited_properties"]["user"] = TOOLS.properties(file)
        self.profile["inherited_environment"] = {"EXTRA_JDK": self.environment["EXTRA_JDK"]}
        self.bind()

    def test_inherited_daemon_override_and_property_drift_are_refused(self):
        file = self.user / "gradle.properties"
        file.write_text(f"org.gradle.java.home={self.profile['jdks'][0]['home']}\n")
        with self.assertRaisesRegex(ValueError, "daemon home"):
            self.bind()
        file.write_text("org.gradle.java.installations.auto-detect=false\n")
        with self.assertRaisesRegex(ValueError, "properties changed"):
            self.bind()

    def test_criteria_require_explicit_identity_and_matching_version(self):
        criteria = self.root / "gradle/gradle-daemon-jvm.properties"
        criteria.write_text("toolchainVersion=17\ntoolchainVendor=ADOPTIUM\n")
        with self.assertRaisesRegex(ValueError, "criteria changed"):
            self.bind()
        self.profile["daemon_criteria"] = {"sha256": TOOLS.digest(criteria), "properties": TOOLS.properties(criteria)}
        with self.assertRaisesRegex(ValueError, "criteria version"):
            self.bind()
        criteria.write_text("toolchainVersion=25\ntoolchainVendor=ADOPTIUM\n")
        self.profile["daemon_criteria"] = {"sha256": TOOLS.digest(criteria), "properties": TOOLS.properties(criteria)}
        self.bind()

    def test_jvm_options_and_command_line_overrides_are_refused(self):
        self.environment["JAVA_TOOL_OPTIONS"] = "-Dorg.gradle.java.installations.paths=/extra"
        with self.assertRaisesRegex(ValueError, "Inherited JVM"):
            self.bind()
        self.environment.pop("JAVA_TOOL_OPTIONS")
        for value in ("-Dorg.gradle.java.home=/extra", "--gradle-user-home=/extra", "-g"):
            with self.subTest(value=value):
                self.arguments.append(value)
                with self.assertRaisesRegex(ValueError, "supplied through"):
                    self.bind()
                self.arguments.pop()

    def test_properties_decode_escaped_keys_continuations_and_windows_paths(self):
        path = self.root / "properties"
        path.write_text('signing.secret=not-returned\norg.gradle.java.installations.\\u0070aths=C\\:\\\\JDK\\\n  \\\\bin\n')
        self.assertEqual({"org.gradle.java.installations.paths": "C:\\JDK\\bin"}, TOOLS.properties(path))

    def test_receipt_zero_exit_without_selected_metadata_is_refused(self):
        _, environment, manifest = self.bind()
        log = self.root / "gradle.log"
        log.write_text("Invalid Java installation: warning\nBUILD SUCCESSFUL\n")
        with self.assertRaisesRegex(ValueError, "receipts"):
            TOOLS.verify(manifest, self.root, environment, log)

    def test_matching_actual_metadata_then_vendor_version_executable_fallback_refusal(self):
        _, environment, manifest = self.bind()
        binding, receipt, log = self.receipt(manifest)
        self.assertEqual("passed", TOOLS.verify(manifest, self.root, environment, log)["status"])
        for key, value in (("vendor", "Another Vendor"), ("runtime_version", "17.0.2+4"), ("major", 8), ("executable", self.profile["jdks"][-1]["java"])):
            changed = copy.deepcopy(receipt)
            changed["tools"][0][key] = value
            Path(binding["receipt"]).write_text(json.dumps(changed))
            with self.subTest(key=key), self.assertRaises(ValueError):
                TOOLS.verify(manifest, self.root, environment, log)

    def test_post_launch_jdk_drift_and_unbound_kotlin_home_are_refused(self):
        _, environment, manifest = self.bind()
        _, _, log = self.receipt(manifest)
        log.write_text(f"[KOTLIN] Kotlin compilation 'jdkHome' argument: {self.root.as_posix()}\n")
        with self.assertRaisesRegex(ValueError, "Kotlin compiler"):
            TOOLS.verify(manifest, self.root, environment, log)
        Path(self.profile["jdks"][0]["home"]).joinpath("lib/modules").write_bytes(b"drift")
        with self.assertRaisesRegex(ValueError, "identity changed"):
            TOOLS.verify(manifest, self.root, environment, log)

    def test_existing_evidence_is_never_replaced(self):
        self.bind()
        with self.assertRaisesRegex(ValueError, "fresh"):
            self.bind()

    def test_distribution_properties_expansion_and_from_env_drift_are_refused(self):
        file = self.distribution / "gradle.properties"
        file.write_text(f"org.gradle.java.installations.paths={self.root.as_posix()}\n")
        with self.assertRaisesRegex(ValueError, "JDK set"):
            self.bind()
        file.write_text("org.gradle.java.installations.fromEnv=EXTRA_JDK\n")
        self.environment["EXTRA_JDK"] = self.profile["jdks"][0]["home"]
        self.profile["inherited_properties"]["installation"] = TOOLS.properties(file)
        self.profile["inherited_environment"] = {"EXTRA_JDK": self.profile["jdks"][-1]["home"]}
        with self.assertRaisesRegex(ValueError, "values changed"):
            self.bind()

    def test_executed_kotlin_requires_actual_home_for_its_own_version(self):
        _, environment, manifest = self.bind()
        binding, receipt, log = self.receipt(manifest)
        receipt["tasks"].append({"path": ":fixtures:compileKotlin", "did_work": True, "kotlin_major": 17})
        Path(binding["receipt"]).write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "unavailable"):
            TOOLS.verify(manifest, self.root, environment, log)
        log.write_text(f"> Task :fixtures:compileKotlin\n[KOTLIN] Kotlin compilation 'jdkHome' argument: {self.profile['jdks'][-1]['home']}\n")
        with self.assertRaisesRegex(ValueError, "version"):
            TOOLS.verify(manifest, self.root, environment, log)
        log.write_text(f"> Task :fixtures:compileKotlin\n[KOTLIN] Kotlin compilation 'jdkHome' argument: {self.profile['jdks'][0]['home']}\n")
        self.assertEqual("passed", TOOLS.verify(manifest, self.root, environment, log)["status"])

    def test_native_project_property_forms_keep_jvm_model_selection(self):
        for arguments in (("-Pstrata.jvmOnly=true",), ("-P", "strata.jvmOnly=true"), ("--project-prop", "strata.jvmOnly=true"), ("--project-prop=strata.jvmOnly=true",)):
            with self.subTest(arguments=arguments):
                self.assertEqual({"strata.jvmOnly": "true"}, TOOLS.project_properties(arguments))

    def test_inherited_model_cannot_narrow_a_complete_binding(self):
        file = self.user / "gradle.properties"
        file.write_text("strata.jvmOnly=true\n")
        self.profile["inherited_properties"]["user"] = TOOLS.properties(file)
        with self.assertRaisesRegex(ValueError, "effective Gradle model"):
            self.bind()

    def test_empty_java_executable_is_not_a_jdk_binding(self):
        Path(self.profile["jdks"][0]["java"]).write_bytes(b"")
        with self.assertRaisesRegex(ValueError, "missing"):
            self.bind()

    def included_receipts(self, manifest):
        """Build two independent captured graphs with the same local Kotlin task path."""
        binding, receipt, log = self.receipt(manifest)
        receipt["tasks"].append({"path": ":compileKotlin", "did_work": True, "kotlin_major": 17})
        project = self.root / "additional-compiler"
        project.mkdir()
        receipt["included_builds"].append({"project_dir": str(project)})
        included = copy.deepcopy(receipt)
        included.update(build_path=":additional-compiler", project_dir=str(project), parent_build_path=":", included_builds=[])
        included["tools"] = [dict(self.profile["jdks"][0], task=":compileKotlin", role="launcher", executable=self.profile["jdks"][0]["java"])]
        included["tasks"] = [{"path": ":compileKotlin", "did_work": True, "kotlin_major": 17}]
        Path(binding["receipt"]).write_text(json.dumps(receipt))
        included_file = Path(binding["receipts"]) / "included.json"
        included_file.write_text(json.dumps(included))
        home = self.profile["jdks"][0]["home"]
        log.write_text(f"> Task :compileKotlin\n[KOTLIN] Kotlin compilation 'jdkHome' argument: {home}\n> Task :additional-compiler:compileKotlin\n[KOTLIN] Kotlin compilation 'jdkHome' argument: {home}\n")
        return binding, included, included_file, log

    def test_build_qualified_receipts_keep_complete_included_kotlin_work(self):
        _, environment, manifest = self.bind()
        _, _, _, log = self.included_receipts(manifest)
        result = TOOLS.verify(manifest, self.root, environment, log)
        self.assertEqual(2, len(result["builds"]))

    def test_included_wrong_home_missing_receipt_and_unknown_task_are_refused(self):
        _, environment, manifest = self.bind()
        binding, included, included_file, log = self.included_receipts(manifest)
        home = self.profile["jdks"][-1]["home"]
        log.write_text(f"> Task :additional-compiler:compileKotlin\n[KOTLIN] Kotlin compilation 'jdkHome' argument: {home}\n")
        with self.assertRaisesRegex(ValueError, "version"):
            TOOLS.verify(manifest, self.root, environment, log)
        home = self.profile["jdks"][0]["home"]
        log.write_text(f"> Task :unknown-build:compileKotlin\n[KOTLIN] Kotlin compilation 'jdkHome' argument: {home}\n")
        with self.assertRaisesRegex(ValueError, "unbound JDK or task"):
            TOOLS.verify(manifest, self.root, environment, log)
        included_file.unlink()
        log.write_text(f"> Task :additional-compiler:compileKotlin\n[KOTLIN] Kotlin compilation 'jdkHome' argument: {home}\n")
        with self.assertRaisesRegex(ValueError, "Missing declared"):
            TOOLS.verify(manifest, self.root, environment, log)
        log.write_text("BUILD SUCCESSFUL\n")
        with self.assertRaisesRegex(ValueError, "Missing declared"):
            TOOLS.verify(manifest, self.root, environment, log)

    def test_included_vendor_fallback_and_unrelated_build_are_refused(self):
        _, environment, manifest = self.bind()
        _, included, file, log = self.included_receipts(manifest)
        included["tools"][0]["vendor"] = "Unbound vendor"
        file.write_text(json.dumps(included))
        with self.assertRaisesRegex(ValueError, "identity"):
            TOOLS.verify(manifest, self.root, environment, log)
        included["tools"][0]["vendor"] = self.profile["jdks"][0]["vendor"]
        file.write_text(json.dumps(included))
        foreign = copy.deepcopy(included)
        foreign.update(build_path=":foreign", parent_build_path=None, project_dir=str(self.root / "foreign"))
        (file.parent / "foreign.json").write_text(json.dumps(foreign))
        with self.assertRaisesRegex(ValueError, "Unrelated"):
            TOOLS.verify(manifest, self.root, environment, log)

    def test_ambiguous_build_qualified_task_display_is_refused(self):
        _, environment, manifest = self.bind()
        binding, _, _, log = self.included_receipts(manifest)
        root = json.loads(Path(binding["receipt"]).read_text())
        root["tasks"].append({"path": ":additional-compiler:compileKotlin", "did_work": False, "kotlin_major": 17})
        Path(binding["receipt"]).write_text(json.dumps(root))
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            TOOLS.verify(manifest, self.root, environment, log)


if __name__ == "__main__":
    unittest.main()
