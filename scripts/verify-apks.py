#!/usr/bin/env python3
"""Inspect the actual release APKs, including Standard's merged manifest and DEX payload."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

root = Path(__file__).resolve().parent.parent
os.chdir(root)
tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
results = {}
for variant, package_name in (("standard", "dev.lichboard"), ("assist", "dev.lichboard.assist")):
    apk = root / f"app/build/outputs/apk/{variant}/release/app-{variant}-release.apk"
    output = root / "verification/apks" / variant
    output.mkdir(parents=True, exist_ok=True)
    signature = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
    manifest = subprocess.check_output([str(tools / "aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml"], text=True)
    (output / "signature.txt").write_text(signature)
    (output / "badging.txt").write_text(badging)
    (output / "manifest.txt").write_text(manifest)
    assert f"name='{package_name}'" in badging.splitlines()[0], badging.splitlines()[0]
    assert "sdkVersion:'30'" in badging and "targetSdkVersion:'35'" in badging
    assert "application-debuggable" not in badging
    assert "android.permission.INTERNET" not in manifest
    for component in ("LichboardApplication", "MainActivity", "ime.LichboardImeService", "overlay.FloatingService", "overlay.BootReceiver"):
        assert f"dev.lichboard.{component}" in manifest, (variant, "incorrect component namespace", component)
    assert "android:foregroundServiceType" in manifest
    markers = ("android.permission.BIND_ACCESSIBILITY_SERVICE", "android.accessibilityservice.AccessibilityService", "dev.lichboard.accessibility.LichboardAccessibilityService")
    expected_assist = variant == "assist"
    for marker in markers:
        assert (marker in manifest) == expected_assist, (variant, marker)
    with zipfile.ZipFile(apk) as bundle:
        assert bundle.testzip() is None
        dex = b"".join(bundle.read(name) for name in bundle.namelist() if name.endswith(".dex"))
        implementation = b"Ldev/lichboard/accessibility/LichboardAccessibilityService;" in dex
        # AAPT shortens release resource file paths (for example res/4z.xml).
        # Inspect the resource-table key, not the original source filename.
        resources = subprocess.check_output([str(tools / "aapt"), "dump", "resources", str(apk)], text=True)
        metadata = bool(re.search(r":xml/accessibility_service\b", resources))
        resource_lines = [line for line in resources.splitlines() if ":xml/accessibility_service" in line]
        (output / "accessibility-resources.txt").write_text("\n".join(resource_lines) + "\n" if resource_lines else "No accessibility-service XML resource in the APK resource table.\n")
        assert implementation == expected_assist, (variant, "unexpected accessibility implementation")
        assert metadata == expected_assist, (variant, "unexpected accessibility metadata")
        assert b"Ldev/lichboard/ProbeActivity;" not in dex, "Debug-only probe leaked into release"
    version = re.search(r"versionName='([^']+)'", badging).group(1)
    result = {
        "applicationId": package_name,
        "versionName": version,
        "accessibilityServiceDeclared": expected_assist,
        "accessibilityImplementationIncluded": implementation,
        "accessibilityMetadataIncluded": metadata,
        "debuggable": False,
        "internetPermission": False,
        "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "apkBytes": apk.stat().st_size,
    }
    (output / "capabilities.json").write_text(json.dumps(result, indent=2) + "\n")
    results[variant] = result
    print(variant, json.dumps(result))
(root / "verification/apk-capabilities.json").write_text(json.dumps(results, indent=2) + "\n")
print("Verified: Standard has no accessibility service declaration, implementation, or service metadata; Assist is explicitly separate.")
