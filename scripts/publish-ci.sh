#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification releases
python3 - <<'PY'
import datetime, hashlib, json, os, pathlib, shutil
import xml.etree.ElementTree as ET
root = pathlib.Path('.')
for p in root.glob('ci-*.log'):
    (root / 'verification' / p.name).write_text(p.read_text(errors='replace')[-200_000:])
for variant in ('standard', 'assist'):
    task_variant = variant.capitalize()
    tests = root / f'app/build/test-results/test{task_variant}DebugUnitTest'
    if tests.is_dir():
        dst = root / 'verification/unit-tests' / variant
        dst.mkdir(parents=True, exist_ok=True)
        for xml in tests.glob('*.xml'): shutil.copy(xml, dst / xml.name)
    lint = root / f'app/build/reports/lint-results-{variant}Debug.txt'
    if lint.exists(): shutil.copy(lint, root / 'verification' / lint.name)

report = {
    'sourceCommit': os.environ['GITHUB_SHA'],
    'runUrl': f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
    'timestampUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'versionName': '1.2.0',
    'buildUnitTestsLint': os.environ.get('BUILD_OUTCOME'),
    'android11Api30': os.environ.get('API30_OUTCOME'),
    'android15Api35': os.environ.get('API35_OUTCOME'),
    'signing': 'Non-debuggable evaluation releases; Android development certificate. Use your own private keystore for production and stable updates.',
    'playProtectAcceptance': 'Not verified. Stock Android emulator tests do not include Google Play Protect or OEM installation policy. Do not disable device protections.',
    'variants': {},
}
capability_path = root / 'verification/apk-capabilities.json'
capabilities = json.loads(capability_path.read_text()) if capability_path.exists() else {}
verified = all(report[k] == 'success' for k in ('buildUnitTestsLint', 'android11Api30', 'android15Api35'))
for variant in ('standard', 'assist'):
    info = dict(capabilities.get(variant, {}))
    info['deviceTests'] = {}
    info['inputPerformance'] = {}
    for api in (30, 35):
        directory = root / f'verification/api-{api}' / variant
        suites = ['keyboard-and-windows'] + (['accessibility'] if variant == 'assist' else [])
        counts = dict(tests=0, failures=0, errors=0, skipped=0)
        complete = True
        for suite in suites:
            path = directory / f'{suite}.xml'
            if not path.exists(): complete = False; continue
            suite_root = ET.parse(path).getroot()
            for field in counts: counts[field] += int(suite_root.get(field, 0))
        launch = directory / 'release-launch.txt'
        pid = directory / 'release-pid.txt'
        smoke = launch.exists() and 'Status: ok' in launch.read_text() and pid.exists() and pid.read_text().strip().isdigit()
        required_count = 22 if variant == 'standard' else 21
        passed = complete and counts['tests'] >= required_count and all(counts[k] == 0 for k in ('failures', 'errors', 'skipped')) and smoke
        performance = {}
        for name in ('input-burst', 'input-latency'):
            path = directory / 'screenshots' / f'{name}.json'
            if path.exists(): performance[name] = json.loads(path.read_text())
        burst = performance.get('input-burst', {})
        latency = performance.get('input-latency', {})
        performance_passed = (
            burst.get('characters') == 520 and burst.get('backspaces') == 128 and
            burst.get('loss') == 0 and burst.get('orderMatches') is True and burst.get('selectedTextQueries') == 0 and
            latency.get('samples', 0) >= 100 and latency.get('loss') == 0 and latency.get('orderMatches') is True and
            0 <= latency.get('activationToCommitMs', {}).get('p95', 9999) < 100 and
            all(p.get('apiLevel') == api and p.get('edition') == variant for p in performance.values())
        )
        passed = passed and performance_passed
        info['inputPerformance'][f'api{api}'] = performance
        info['deviceTests'][f'api{api}'] = {**counts, 'releaseSmokePassed': smoke, 'inputPerformancePassed': performance_passed, 'passed': passed}
        verified = verified and passed
    apk = root / f'app/build/outputs/apk/{variant}/release/app-{variant}-release.apk'
    valid_apk = apk.exists() and bool(info) and hashlib.sha256(apk.read_bytes()).hexdigest() == info.get('apkSha256')
    verified = verified and valid_apk
    info['published'] = False
    info['apkPath'] = None
    report['variants'][variant] = info

if verified:
    for variant, info in report['variants'].items():
        destination = root / f'releases/Lichboard-1.2.0-{variant}.apk'
        shutil.copy(root / f'app/build/outputs/apk/{variant}/release/app-{variant}-release.apk', destination)
        info['apkPath'] = destination.as_posix()
        info['published'] = True
    # Keep historical builds in Git history, not alongside the current paired downloads.
    for old in (root / 'releases').glob('Lichboard-*.apk'):
        if old.name not in {pathlib.Path(info['apkPath']).name for info in report['variants'].values()}:
            old.unlink()
    (root / 'releases/SHA256SUMS').write_text(''.join(
        f"{info['apkSha256']}  {pathlib.Path(info['apkPath']).name}\n" for info in report['variants'].values()
    ))
report['verifiedReleasePublished'] = bool(verified)
(root / 'verification/latest.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
PY
git config user.name "Lichboard verification"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add verification releases
if ! git diff --cached --quiet; then
  git commit -m "Save Standard and Assist APK verification evidence [skip ci]"
  git pull --rebase origin "$GITHUB_REF_NAME"
  git push origin "HEAD:$GITHUB_REF_NAME"
fi

python3 - <<'PY_CHECK'
import json
from pathlib import Path
report = json.loads(Path('verification/latest.json').read_text())
stages = ('buildUnitTestsLint', 'android11Api30', 'android15Api35')
if all(report.get(stage) == 'success' for stage in stages) and not report['verifiedReleasePublished']:
    raise SystemExit('Verification/publication integrity checks failed; see verification/latest.json.')
PY_CHECK
