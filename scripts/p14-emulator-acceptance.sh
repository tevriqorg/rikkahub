#!/usr/bin/env bash
set -euo pipefail
mkdir -p evidence
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
for item in A:5554 B:5556; do
  name="${item%:*}"; port="${item#*:}"
  echo no | avdmanager create avd -n "p14$name" -k 'system-images;android-35;google_apis;x86_64' --force
  emulator -avd "p14$name" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 1536 -cores 2 > "evidence/emulator-$name-boot.log" 2>&1 &
done
for serial in emulator-5554 emulator-5556; do
  timeout 300 adb -s "$serial" wait-for-device
  timeout 300 bash -c 'until [ "$(adb -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" = 1 ]; do sleep 3; done' _ "$serial"
  adb -s "$serial" shell input keyevent 82
  adb -s "$serial" shell settings put global window_animation_scale 0
  adb -s "$serial" shell settings put global transition_animation_scale 0
  adb -s "$serial" shell settings put global animator_duration_scale 0
  adb -s "$serial" install -r evidence/apks/app.apk
  adb -s "$serial" install -r evidence/apks/test.apk
  adb -s "$serial" shell pm grant me.rerere.rikkahub.debug android.permission.POST_NOTIFICATIONS
  adb -s "$serial" shell dumpsys package me.rerere.rikkahub.debug > "evidence/$serial-installed-package.txt"
done
for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8014/healthz > evidence/server-health.json; then break; fi
  sleep 2
done
curl -fsS http://127.0.0.1:8014/healthz
P14_TASK4_SYNTHETIC=1 python3 synthetic-server/server/acceptance/task4_fixture.py seed > evidence/fixture.json
instrument() {
  local serial="$1" method="$2" klass="${3:-P14RecoveryInstrumentedTest}"
  local report="evidence/$serial-$method.txt"
  timeout 240 adb -s "$serial" shell am instrument -w -r \
    -e class "me.rerere.rikkahub.data.remote.p14.$klass#$method" \
    -e p14BaseUrl http://10.0.2.2:8014/v1 -e p14OwnerToken task4-owner-synthetic-only \
    -e p14NotificationId "$(python3 -c 'import json; print(json.load(open("evidence/fixture.json"))["notification_id"])')" \
    me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$report"
  grep -Eq 'OK \([1-9][0-9]* test' "$report"
  ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$report"
}
A=emulator-5554
B=emulator-5556
instrument "$A" localContracts
adb -s "$A" shell svc wifi disable
adb -s "$A" shell svc data disable
instrument "$A" seedOffline
adb -s "$A" shell am force-stop me.rerere.rikkahub.debug
adb -s "$A" shell monkey -p me.rerere.rikkahub.debug -c android.intent.category.LAUNCHER 1
instrument "$A" verifyOffline
# Android must reject a different certificate without deleting the existing synthetic data.
if adb -s "$A" install -r evidence/apks/incompatible-ci.apk > evidence/incompatible-signature-install.txt 2>&1; then
  echo "Unexpected incompatible signature install success"; exit 1
fi
grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE evidence/incompatible-signature-install.txt
instrument "$A" verifyOffline
# Reinstallation with the same certificate must preserve the synthetic Room records.
adb -s "$A" install -r evidence/apks/app.apk
instrument "$A" verifyOffline
instrument "$A" captureAndProfileUi P14RecoveryUiInstrumentedTest
instrument "$A" sharedTextUsesDurableComposer P14RecoveryUiInstrumentedTest
adb -s "$A" shell svc wifi enable
adb -s "$A" shell svc data enable
instrument "$A" deviceASeed
instrument "$A" uploaderMediaContract
adb -s "$A" exec-out run-as me.rerere.rikkahub.debug cat files/task4-remote-contracts.json > evidence/task4-remote-contracts.json
P14_TASK4_SYNTHETIC=1 python3 synthetic-server/server/acceptance/task4_media_contract.py --input evidence/task4-remote-contracts.json > evidence/media-contract-validation.json
instrument "$B" deviceBContinue
instrument "$B" deviceBMutations
instrument "$B" seedInteractionOffline
adb -s "$B" shell am force-stop me.rerere.rikkahub.debug
instrument "$B" verifyInteractionRecovery
adb -s "$A" shell am force-stop me.rerere.rikkahub.debug
instrument "$A" deviceAVerify
adb -s "$A" shell pm grant me.rerere.rikkahub.debug android.permission.ACCESS_COARSE_LOCATION
instrument "$A" observationPolicy
for serial in "$A" "$B"; do
  adb -s "$serial" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p me.rerere.rikkahub.debug
  adb -s "$serial" shell uiautomator dump /sdcard/task4-ui.xml
  adb -s "$serial" pull /sdcard/task4-ui.xml "evidence/$serial-ui.xml"
done
python3 - <<'PY'
import json,os,platform
from pathlib import Path
p=Path('evidence')
(p/'run-provenance.json').write_text(json.dumps({
 'repository':os.environ['GITHUB_REPOSITORY'],'android_sha':os.environ['GITHUB_SHA'],
 'run_id':os.environ['GITHUB_RUN_ID'],'run_attempt':os.environ['GITHUB_RUN_ATTEMPT'],
 'server_sha':(p/'server-source-sha.txt').read_text().strip(),
 'emulator_api':35,'abi':'x86_64','devices':['emulator-5554','emulator-5556'],
 'synthetic_data_only':True,'physical_device_acceptance':False,
 'hermes_used':False,'host':platform.platform()},indent=2)+'\n')
PY

