#!/usr/bin/env python3
"""Package the complete Android Studio project only after verified CI evidence exists."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)
report = json.loads((ROOT / "verification/latest.json").read_text())
if not report.get("verifiedReleasePublished"):
    raise SystemExit("Refusing to package: the latest release did not pass all verification stages.")

apk = ROOT / "releases/Lichboard-1.0.0.apk"
if hashlib.sha256(apk.read_bytes()).hexdigest() != report["apkSha256"]:
    raise SystemExit("Refusing to package: APK checksum differs from the verified CI result.")

# Evidence is committed after the tested source. Documentation may also advance, but
# the app, resources, tests, build configuration, and Gradle wrapper must match exactly.
source_paths = ["app", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                "gradle", "gradlew", "gradlew.bat"]
if subprocess.run(["git", "diff", "--quiet", report["sourceCommit"], "--", *source_paths]).returncode:
    raise SystemExit("Refusing to package: application sources differ from the tested commit.")
if subprocess.run(["git", "diff", "--quiet", "HEAD"]).returncode:
    raise SystemExit("Commit tracked-file changes before packaging so the archive has clear provenance.")

files = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
output = ROOT / "deliverables"
output.mkdir(exist_ok=True)
archive = output / "Lichboard-1.0.0-AndroidStudio.zip"
provenance = {
    "packagedCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
    "testedSourceCommit": report["sourceCommit"],
    "verificationRun": report["runUrl"],
    "apkSha256": report["apkSha256"],
    "signing": report["signing"],
    "openInAndroidStudio": "Open the Lichboard folder containing settings.gradle.kts; use JDK 17 and SDK 35.",
}
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
    for name in sorted(filter(None, files)):
        path = Path(name)
        if path.parts[0] in (".git", "deliverables") or not path.is_file():
            raise SystemExit(f"Unexpected tracked path: {name}")
        bundle.write(path, "Lichboard/" + path.as_posix())
    bundle.writestr("Lichboard/DELIVERY.json", json.dumps(provenance, indent=2) + "\n")
with zipfile.ZipFile(archive) as bundle:
    if bundle.testzip() is not None:
        raise SystemExit("Archive integrity check failed.")
    assert bundle.read("Lichboard/releases/Lichboard-1.0.0.apk") == apk.read_bytes()

checksums = output / "SHA256SUMS"
checksums.write_text("".join(
    f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(ROOT).as_posix()}\n"
    for path in (apk, archive)
))
print(f"Created {archive.relative_to(ROOT)} ({archive.stat().st_size:,} bytes)")
print(checksums.read_text(), end="")
