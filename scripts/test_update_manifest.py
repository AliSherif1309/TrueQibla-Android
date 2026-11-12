import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from make_update_manifest import make_manifest


class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.apk = self.root / "TrueQibla.apk"
        self.apk.write_bytes(b"fixture-apk-bytes")
        self.metadata = self.root / "output-metadata.json"
        self.item = dict(outputFile="app-release.apk", versionCode=117, versionName="1.6")

    def write(self, elements):
        self.metadata.write_text(json.dumps(dict(elements=elements)), encoding="utf-8")

    def test_matches_exact_apk_and_build_version(self):
        self.write([self.item])
        self.assertEqual(make_manifest(self.apk, self.metadata), dict(versionCode=117, versionName="1.6",
            size=len(self.apk.read_bytes()), sha256=hashlib.sha256(self.apk.read_bytes()).hexdigest()))

    def test_rejects_invalid_codes(self):
        for code in (0, -1, True, "117", 2_147_483_648):
            with self.subTest(code=code):
                self.write([dict(self.item, versionCode=code)])
                with self.assertRaises(ValueError):
                    make_manifest(self.apk, self.metadata)

    def test_rejects_missing_or_split_apks(self):
        for elements in ([], [self.item, self.item], [dict(self.item, outputFile="split.apk")]):
            self.write(elements)
            with self.assertRaises(ValueError):
                make_manifest(self.apk, self.metadata)

    def test_rejects_empty_apk(self):
        self.write([self.item])
        self.apk.write_bytes(b"")
        with self.assertRaises(ValueError):
            make_manifest(self.apk, self.metadata)
