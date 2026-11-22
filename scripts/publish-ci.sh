#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification releases
python3 - <<'PY'
import os, pathlib, json, datetime, shutil, hashlib
root=pathlib.Path('.')
for p in root.glob('ci-*.log'):
    # Keep diagnostics small; full reports are available in the Actions artifact.
    text=p.read_text(errors='replace')
    (root/'verification'/p.name).write_text(text[-200_000:])
for kind in ('test-results/testDebugUnitTest', 'reports/lint-results-debug.txt'):
    p=root/'app/build'/kind
    if p.is_dir():
        dst=root/'verification/unit-tests'; dst.mkdir(exist_ok=True)
        for xml in p.glob('*.xml'): shutil.copy(xml, dst/xml.name)
    elif p.exists(): shutil.copy(p, root/'verification'/p.name)
report={
 'sourceCommit':os.environ['GITHUB_SHA'],
 'runUrl':f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
 'timestampUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
 'buildUnitTestsLint':os.environ.get('BUILD_OUTCOME'),
 'android11Api30':os.environ.get('API30_OUTCOME'),
 'android15Api35':os.environ.get('API35_OUTCOME'),
 'signing':'Evaluation release; Android development certificate. Use your own keystore for production.',
}
verified=all(report[k]=='success' for k in ['buildUnitTestsLint','android11Api30','android15Api35'])
apk=root/'app/build/outputs/apk/release/app-release.apk'
if verified and apk.exists():
    dest=root/'releases/Lichboard-1.0.0.apk'; shutil.copy(apk,dest)
    report['apkSha256']=hashlib.sha256(dest.read_bytes()).hexdigest()
    report['apkBytes']=dest.stat().st_size
report['verifiedReleasePublished']=verified and apk.exists()
(root/'verification/latest.json').write_text(json.dumps(report,indent=2)+'\n')
PY
git config user.name "Lichboard verification"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add verification releases
if ! git diff --cached --quiet; then
  git commit -m "Save Android build evidence and verified APK [skip ci]"
  git pull --rebase origin "$GITHUB_REF_NAME"
  git push origin "HEAD:$GITHUB_REF_NAME"
fi
