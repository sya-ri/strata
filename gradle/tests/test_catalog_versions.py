"""Exercise the controller-owned catalog CLI against a separate product checkout."""

import pathlib
import subprocess
import sys
import tempfile
import unittest


READER = pathlib.Path(__file__).parents[1] / "catalog-versions.py"
CATALOG = '''[versions]
java-current = "25"
java-baseline = "17"
java-duplicate = "17"
performance-toolchain = "17.0.8"
performance-launcher = "25.0.0"
actionlint = "1.2.3"
shellcheck = "4.5.6"
pyflakes = "7.8.9"
'''


class CatalogVersionsTest(unittest.TestCase):
    """Retain named version ordering, Java profiles and controller/product separation."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.product = pathlib.Path(temporary.name)
        (self.product / "gradle").mkdir()
        self.catalog = self.product / "gradle/libs.versions.toml"
        self.catalog.write_text(CATALOG, encoding="utf-8")

    def run_reader(self, *arguments):
        """Run the actual controller helper from a product with no helper of its own."""
        return subprocess.run([sys.executable, "-B", str(READER), *arguments], cwd=self.product, capture_output=True, check=False)

    def test_reads_the_product_catalog_in_each_supported_output_format(self):
        for arguments, expected in (
            ((), "17\n25\n"),
            (("--paired",), "toolchain=17.0.8\nlauncher=25.0.0\n"),
            (("--keys", "pyflakes", "actionlint", "shellcheck"), "7.8.9 1.2.3 4.5.6\n"),
        ):
            with self.subTest(arguments=arguments):
                result = self.run_reader(*arguments)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(expected.encode("utf-8"), result.stdout)

    def test_rejects_missing_or_malformed_versions_and_conflicting_selection(self):
        for content, arguments in (
            (CATALOG, ("--keys", "missing")),
            (CATALOG.replace('actionlint = "1.2.3"', 'actionlint = ""'), ("--keys", "actionlint")),
            (CATALOG.replace('actionlint = "1.2.3"', 'actionlint = "bad value"'), ("--keys", "actionlint")),
            (CATALOG.replace('actionlint = "1.2.3"', "actionlint = 123"), ("--keys", "actionlint")),
            (CATALOG.replace('java-baseline = "17"', 'java-baseline = "bad"'), ()),
            (CATALOG, ("--paired", "--keys", "actionlint")),
        ):
            with self.subTest(content=content, arguments=arguments):
                self.catalog.write_text(content, encoding="utf-8")
                result = self.run_reader(*arguments)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(b"", result.stdout)


if __name__ == "__main__":
    unittest.main()
