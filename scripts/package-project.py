#!/usr/bin/env python3
"""Package both verified editions and the complete Android Studio project."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)
report = json.loads((ROOT / "verification/latest.json").read_text())
variants = report.get("variants", {})
if not report.get("verifiedReleasePublished") or set(variants) != {"standard", "assist"}:
    raise SystemExit("Refusing to package: both editions must pass all verification stages first.")
apks = []
for variant, info in variants.items():
    if not info.get("published") or not info.get("apkPath"):
        raise SystemExit(f"Refusing to package: {variant} was not published by verified CI.")
    apk = ROOT / info["apkPath"]
    if hashlib.sha256(apk.read_bytes()).hexdigest() != info["apkSha256"]:
        raise SystemExit(f"Refusing to package: {variant} APK checksum differs from CI.")
    apks.append(apk)
if variants["standard"]["accessibilityServiceDeclared"] or variants["standard"]["accessibilityImplementationIncluded"]:
    raise SystemExit("Refusing to package: Standard unexpectedly includes accessibility input assist.")

source_paths = ["app", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                "gradle", "gradlew", "gradlew.bat", "scripts", ".github/workflows/android.yml"]
if subprocess.run(["git", "diff", "--quiet", report["sourceCommit"], "--", *source_paths]).returncode:
    raise SystemExit("Refusing to package: application/build sources differ from the tested commit.")
if subprocess.run(["git", "diff", "--quiet", "HEAD"]).returncode:
    raise SystemExit("Commit tracked changes before packaging so the archive has clear provenance.")

files = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
output = ROOT / "deliverables"
output.mkdir(exist_ok=True)
archive = output / f"Lichboard-{report['versionName']}-AndroidStudio.zip"
provenance = {
    "packagedCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
    "testedSourceCommit": report["sourceCommit"],
    "verificationRun": report["runUrl"],
    "recommendedEdition": "standard",
    "variants": variants,
    "signing": report["signing"],
    "playProtectAcceptance": report["playProtectAcceptance"],
    "openInAndroidStudio": "Open the Lichboard folder with JDK 17 and SDK 35. Choose standardDebug by default; assistDebug explicitly adds accessibility input assist.",
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
    for apk in apks:
        assert bundle.read("Lichboard/" + apk.relative_to(ROOT).as_posix()) == apk.read_bytes()

checksums = output / "SHA256SUMS"
checksums.write_text("".join(
    f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(ROOT).as_posix()}\n"
    for path in (*apks, archive)
))
print(f"Created {archive.relative_to(ROOT)} ({archive.stat().st_size:,} bytes)")
print(checksums.read_text(), end="")
