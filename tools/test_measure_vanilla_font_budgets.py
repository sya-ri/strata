"""Exercise receipt provenance checks using synthetic metadata without game files or downloads."""

import importlib.util
import io
import json
import pathlib
import tempfile
import unittest
from unittest.mock import Mock, patch


class VanillaFontBudgetMeasurementTest(unittest.TestCase):
    """Check metadata identity, bounded reads, and portable deterministic receipt projection."""

    def setUp(self):
        """Load an isolated measurement module and own a temporary input root for one test."""
        path = pathlib.Path(__file__).with_name("measure-vanilla-font-budgets.py")
        spec = importlib.util.spec_from_file_location("vanilla_font_budgets", path)
        self.measurement = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.measurement)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.measurement.ROOT = pathlib.Path(self.directory.name)

    def metadata(self, version, identity):
        """Write minimal synthetic metadata without creating any referenced game asset."""
        folder = self.measurement.ROOT / version
        folder.mkdir(exist_ok=True)
        content = {"assetIndex": {"id": "fixture"}}
        if identity is not None:
            content["id"] = identity
        (folder / "mojang_minecraft_info.json").write_text(json.dumps(content), encoding="utf-8")

    def test_swapped_metadata_ids_fail_before_reading_index_or_client(self):
        for version, identity in (("1.20.5", "26.2"), ("26.2", "1.20.5")):
            with self.subTest(version=version):
                self.metadata(version, identity)
                with patch.object(self.measurement, "file_hashes") as read_artifact:
                    with self.assertRaisesRegex(ValueError, "version metadata identity mismatch"):
                        self.measurement.measure(version)
                    read_artifact.assert_not_called()

    def test_absent_metadata_id_cannot_adopt_the_directory_label(self):
        self.metadata("1.20.5", None)
        with patch.object(self.measurement, "file_hashes") as read_artifact:
            with self.assertRaisesRegex(ValueError, "version metadata identity mismatch"):
                self.measurement.measure("1.20.5")
            read_artifact.assert_not_called()

    def test_metadata_identity_is_not_coerced_or_whitespace_normalized(self):
        for identity in (1205, " 1.20.5", "1.20.5\n"):
            with self.subTest(identity=identity):
                self.metadata("1.20.5", identity)
                with patch.object(self.measurement, "file_hashes") as read_artifact:
                    with self.assertRaisesRegex(ValueError, "version metadata identity mismatch"):
                        self.measurement.measure("1.20.5")
                    read_artifact.assert_not_called()

    def test_matching_metadata_id_proceeds_to_the_named_index(self):
        self.metadata("1.20.5", "1.20.5")
        with patch.object(self.measurement, "file_hashes", side_effect=OSError("fixture asset boundary")) as read_artifact:
            with self.assertRaisesRegex(OSError, "fixture asset boundary"):
                self.measurement.measure("1.20.5")
            read_artifact.assert_called_once_with(self.measurement.ROOT / "assets/indexes/1.20.5-fixture.json")

    def test_bounded_reads_handle_short_chunks_and_stop_after_one_detection_byte(self):
        cases = ((b"", 0), (b"a", 0), (b"abcd", 4), (b"abcde", 4), (b"abcd", 5))
        for data, maximum in cases:
            with self.subTest(data=data, maximum=maximum), io.BytesIO(data) as raw:
                stream = Mock(wraps=raw)
                stream.read.side_effect = lambda requested: raw.read(min(requested, 2))
                if len(data) <= maximum:
                    self.assertEqual(data, self.measurement.bounded_read(stream, maximum))
                else:
                    with self.assertRaisesRegex(ValueError, "input exceeds safe bound"):
                        self.measurement.bounded_read(stream, maximum)
                self.assertEqual(min(len(data), maximum + 1), raw.tell())
                self.assertFalse(raw.closed)
                stream.close.assert_not_called()
        stream = Mock()
        with self.assertRaisesRegex(ValueError, "bound must be non-negative"):
            self.measurement.bounded_read(stream, -1)
        stream.read.assert_not_called()

    def test_json_input_is_bounded_and_closed_even_when_rejected(self):
        for maximum in (0, 1, 2, 3):
            with self.subTest(maximum=maximum), patch.dict(self.measurement.LIMITS, json_bytes=maximum):
                stream = io.BytesIO(b"{}")
                path = Mock(spec=pathlib.Path)
                path.open.return_value = stream
                if 2 <= maximum:
                    self.assertEqual({}, self.measurement.read_json(path))
                else:
                    with self.assertRaisesRegex(ValueError, "input exceeds safe bound"):
                        self.measurement.read_json(path)
                self.assertTrue(stream.closed)
                path.open.assert_called_once_with("rb")

    def test_trailing_comma_normalization_preserves_quoted_commas_and_escapes(self):
        literal = '日,] and "quoted",} and \\ slash'
        text = '{"text":' + json.dumps(literal) + ',"values":[1,],}'
        document, removed = self.measurement.font_document(text.encode("utf-8"))
        self.assertEqual({"text": literal, "values": [1]}, document)
        self.assertEqual([text.rfind(",]"), text.rfind(",}")], removed)

    def test_receipt_projection_is_identical_across_roots_and_omits_run_details(self):
        parent = self.measurement.ROOT
        encoded = []
        for name in ("first-machine", "second-machine"):
            self.measurement.ROOT = parent / name
            path = self.measurement.ROOT / "1.20" / "fixture.bin"
            path.parent.mkdir(parents=True)
            path.write_bytes(b"same measured bytes")
            evidence = self.measurement.file_hashes(path)
            result = dict(version="1.20", sources={"entries": 1}, summary={"providers": 1})
            for section in ("metadata", "client", "assetIndex"):
                result[section] = dict(evidence, absolutePath=str(path), mtimeNs=path.stat().st_mtime_ns)
            result["documents"] = [{"sourceLocator": {"path": str(path)}}]
            result["resources"] = [{"source": {"path": str(path)}}]
            result["timestamp"] = path.stat().st_mtime_ns
            projected = self.measurement.portable_result(result)
            self.assertEqual(["version", "metadata", "client", "assetIndex", "sources", "summary"], list(projected))
            self.assertEqual("1.20/fixture.bin", projected["metadata"]["locator"])
            serialized = json.dumps(projected)
            for excluded in (str(path), "absolutePath", "mtimeNs", "timestamp", "sourceLocator"):
                self.assertNotIn(excluded, serialized)
            encoded.append(serialized)
        self.assertEqual(encoded[0], encoded[1])

    def test_receipt_rejects_nonportable_or_traversing_locators(self):
        for locator in ("", "/tmp/input", "C:/Users/name/input", "C:\\Users\\name\\input", "../input", "a/../input", "a//input", "./input"):
            with self.subTest(locator=locator):
                evidence = dict(locator=locator, bytes=0, sha1="a" * 40, sha256="b" * 64)
                result = dict(version="1.20", metadata=evidence, client=evidence, assetIndex=evidence, sources={}, summary={})
                with self.assertRaisesRegex(ValueError, "receipt input locators"):
                    self.measurement.portable_result(result)


if __name__ == "__main__":
    unittest.main()
