"""Stage the user's existing SahlTV packaged fonts; never download fonts at runtime.

No raw font files or secret keys are committed. The source APK and every selected
obfuscated payload are pinned by SHA-256. Updating them requires explicit review.
"""
import argparse
import hashlib
from pathlib import Path
import urllib.request
import zipfile

URL = 'https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/build-48/SahlTV.apk'
APK_SHA = '853a33199ae87518e2e5582782f3464bccdc6692895e8ed6c65f24ddfb4a6658'
FONT_HASHES = {
    'r': '847b8cac2920985336bdca05d9158f388fff83b2b21e32a50ff9251d0a7f2595',
    'm': '2a0e04262d2c7c1989c67400a06d29beb3d488efc8d14a5a9b51bfdde372ac3a',
    'b': '09c9bd3b3eb545690ab966111322380ec5b8bd7dcd504cf32bfeeae61699947c',
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def stage(apk, output, apk_sha=APK_SHA, font_hashes=None):
    expected = FONT_HASHES if font_hashes is None else font_hashes
    if digest(Path(apk).read_bytes()) != apk_sha:
        raise ValueError('Source SahlTV APK checksum mismatch')
    payloads = {}
    with zipfile.ZipFile(apk) as archive:
        for name, checksum in expected.items():
            data = archive.read('assets/f/' + name + '.bin')
            if digest(data) != checksum:
                raise ValueError('Packaged font checksum mismatch: ' + name)
            payloads[name] = data
    # Validate every input before publishing any generated assets.
    folder = Path(output) / 'thmanyah'
    folder.mkdir(parents=True, exist_ok=True)
    for name, data in payloads.items():
        temp = folder / (name + '.tmp')
        temp.write_bytes(data)
        temp.replace(folder / (name + '.bin'))


def check_apk(apk):
    with zipfile.ZipFile(apk) as archive:
        for name, checksum in FONT_HASHES.items():
            if digest(archive.read('assets/thmanyah/' + name + '.bin')) != checksum:
                raise ValueError('Final APK font checksum mismatch: ' + name)
        for name in archive.namelist():
            if 'thmanyah' in name.lower() and name.lower().endswith(('.ttf', '.otf', '.woff', '.woff2')):
                raise ValueError('Raw Thmanyah font in APK: ' + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-apk', type=Path)
    parser.add_argument('--check-apk', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.check_apk:
        check_apk(args.check_apk)
        print('Verified offline Thmanyah assets in', args.check_apk)
        return
    root = Path(__file__).resolve().parents[1]
    apk = args.source_apk or root / '.gradle/thmanyah/SahlTV-build-48.apk'
    if not apk.exists():
        if args.source_apk:
            raise FileNotFoundError(apk)
        apk.parent.mkdir(parents=True, exist_ok=True)
        temp = apk.with_suffix('.download')
        try:
            with urllib.request.urlopen(URL, timeout=90) as response:
                data = response.read(8 * 1024 * 1024)
            if digest(data) != APK_SHA:
                raise ValueError('Downloaded SahlTV APK checksum mismatch')
            temp.write_bytes(data)
            temp.replace(apk)
        finally:
            temp.unlink(missing_ok=True)
    stage(apk, args.output or root / 'app/build/generated/thmanyah/assets')
    print('Verified and staged Thmanyah Sans Regular/Medium/Bold from SahlTV build-48')


if __name__ == '__main__':
    main()
