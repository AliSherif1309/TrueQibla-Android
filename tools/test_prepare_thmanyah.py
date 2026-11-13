import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('prepare', Path(__file__).with_name('prepare_thmanyah.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class PackagedFontTests(unittest.TestCase):
    def test_checked_source_and_payloads_stage_without_changing_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = root / 'source.apk'
            data = {'r': b'obfuscated-regular', 'm': b'obfuscated-medium', 'b': b'obfuscated-bold'}
            with zipfile.ZipFile(apk, 'w') as archive:
                for name, payload in data.items():
                    archive.writestr('assets/f/' + name + '.bin', payload)
            expected = {n: hashlib.sha256(v).hexdigest() for n, v in data.items()}
            prepare.stage(apk, root / 'out', prepare.digest(apk.read_bytes()), expected)
            for name, payload in data.items():
                self.assertEqual(payload, (root / 'out/thmanyah' / (name + '.bin')).read_bytes())
            with self.assertRaises(ValueError):
                prepare.stage(apk, root / 'bad', '0' * 64, expected)
            self.assertFalse((root / 'bad').exists())
            expected['b'] = '0' * 64
            with self.assertRaises(ValueError):
                prepare.stage(apk, root / 'partial', prepare.digest(apk.read_bytes()), expected)
            self.assertFalse((root / 'partial').exists())

    def test_final_apk_rejects_missing_and_corrupt_fonts(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'bad.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/thmanyah/r.bin', b'wrong')
            with self.assertRaises(ValueError):
                prepare.check_apk(apk)


if __name__ == '__main__':
    unittest.main()
