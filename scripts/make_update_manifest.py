"""Generate update metadata from the APK and Android's actual release build output."""
import hashlib
import json
import sys
from pathlib import Path


def make_manifest(apk: Path, metadata: Path) -> dict:
    elements = json.loads(metadata.read_text(encoding="utf-8"))["elements"]
    if len(elements) != 1 or elements[0]["outputFile"] != "app-release.apk":
        raise ValueError("Expected one universal release APK")
    item = elements[0]
    code = item["versionCode"]
    if type(code) is not int or not 0 < code <= 2_147_483_647:
        raise ValueError("Invalid versionCode")
    data = apk.read_bytes()
    if not 0 < len(data) <= 50 * 1024 * 1024:
        raise ValueError("Invalid APK size")
    version = item["versionName"]
    if not isinstance(version, str) or not version.strip():
        raise ValueError("Missing versionName")
    return dict(versionCode=code, versionName=version, sha256=hashlib.sha256(data).hexdigest(), size=len(data))


if __name__ == "__main__":
    result = make_manifest(Path(sys.argv[1]), Path(sys.argv[2]))
    Path(sys.argv[3]).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
