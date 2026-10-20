#!/usr/bin/env bash
set -euo pipefail
mkdir -p evidence
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
adb start-server
# The emulator launcher supplies its bundled library paths; raw qemu ldd is diagnostic only.
ldd "$ANDROID_HOME/emulator/qemu/linux-x86_64/qemu-system-x86_64" > evidence/emulator-runtime-libraries.txt || true
emulator -version > evidence/emulator-launcher-version.txt 2>&1
for name in A B; do
  echo no | avdmanager create avd -n "p14$name" -k 'system-images;android-35;google_apis;x86_64' --path "$ANDROID_AVD_HOME/p14$name.avd" --force
done
avdmanager list avd > evidence/avd-manager-list.txt
emulator -list-avds > evidence/registered-avds.txt
grep -qx p14A evidence/registered-avds.txt
grep -qx p14B evidence/registered-avds.txt
for item in A:5554 B:5556; do
  name="${item%:*}"; port="${item#*:}"
  emulator -avd "p14$name" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 1536 -cores 2 > "evidence/emulator-$name-boot.log" 2>&1 &
  process=$!
  sleep 1
  if ! kill -0 "$process" 2>/dev/null; then tail -80 "evidence/emulator-$name-boot.log"; exit 1; fi
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
  installed_path="$(adb -s "$serial" shell pm path me.rerere.rikkahub.debug | sed -n 's/^package://p' | tr -d '\r')"
  adb -s "$serial" pull "$installed_path" "$RUNNER_TEMP/$serial-installed.apk"
  cmp evidence/apks/app.apk "$RUNNER_TEMP/$serial-installed.apk"
  sha256sum "$RUNNER_TEMP/$serial-installed.apk" > "evidence/$serial-installed-apk-sha256.txt"
  rm -f "$RUNNER_TEMP/$serial-installed.apk"
  adb -s "$serial" shell pm grant me.rerere.rikkahub.debug android.permission.POST_NOTIFICATIONS
  adb -s "$serial" shell dumpsys package me.rerere.rikkahub.debug > "evidence/$serial-installed-package.txt"
done
for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8014/healthz > evidence/server-health.json; then break; fi
  sleep 2
done
curl -fsS http://127.0.0.1:8014/healthz
P14_TASK4_SYNTHETIC=1 python3 synthetic-server/server/acceptance/task4_fixture.py seed > evidence/fixture.json
P14_TASK4_SYNTHETIC=1 python3 synthetic-server/server/acceptance/task4_fixture.py scale --count 1200 > evidence/early-scale-seed.json
test_run=0
instrument() {
  local serial="$1" method="$2" klass="${3:-P14RecoveryInstrumentedTest}"
  test_run=$((test_run + 1))
  local folder=evidence
  if [ "${intentional_crash:-0}" = 1 ]; then folder=evidence/intentional-crashes; fi
  local report="$folder/$serial-$method-$test_run.txt"
  local setup=()
  if [ "$serial" = emulator-5554 ]; then setup=(-e p14SetupToken task4-setup-synthetic-only); fi
  timeout 240 adb -s "$serial" shell am instrument -w -r \
    -e class "me.rerere.rikkahub.data.remote.p14.$klass#$method" \
    -e p14BaseUrl http://10.0.2.2:8014/v1 -e p14OwnerToken task4-owner-synthetic-only \
    -e p14NotificationId "$(python3 -c 'import json; print(json.load(open("evidence/fixture.json"))["notification_id"])')" \
    -e p14MediaEntryId "$(python3 -c 'import json; print(json.load(open("evidence/fixture.json"))["entry_id"])')" \
    -e p14MediaConversationId "$(python3 -c 'import json; print(json.load(open("evidence/fixture.json"))["media_conversation_id"])')" \
    -e p14MediaMessageId "$(python3 -c 'import json; print(json.load(open("evidence/fixture.json"))["media_message_id"])')" \
    -e p14MediaAttachmentId "$(python3 -c 'import json; print(json.load(open("evidence/fixture.json"))["media_attachment_id"])')" \
    -e p14MediaSha256 "$(python3 -c 'import json; print(json.load(open("evidence/fixture.json"))["media_sha256"])')" \
    -e p14MediaByteSize "$(python3 -c 'import json; print(json.load(open("evidence/fixture.json"))["media_byte_size"])')" \
    -e p14ActionConfirm "$(python3 -c 'import json; print(json.load(open("evidence/fixture.json"))["actions"]["confirm"])')" \
    -e p14ActionSupplement "$(python3 -c 'import json; print(json.load(open("evidence/fixture.json"))["actions"]["supplement"])')" \
    -e p14ActionSkip "$(python3 -c 'import json; print(json.load(open("evidence/fixture.json"))["actions"]["skip"])')" \
    -e p14ActionCancel "$(python3 -c 'import json; print(json.load(open("evidence/fixture.json"))["actions"]["cancel"])')" \
    "${setup[@]}" \
    me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$report"
  grep -Eq 'OK \([1-9][0-9]* test' "$report"
  ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$report"
}
crash_window() {
  local serial="$1" method="$2" marker="$3" expected="$4"
  mkdir -p evidence/intentional-crashes
  adb -s "$serial" shell run-as me.rerere.rikkahub.debug rm -f "files/$marker"
  intentional_crash=1 instrument "$serial" "$method" > "evidence/intentional-crashes/$method-driver.txt" 2>&1 &
  local driver=$! observed="" pid_before=""
  for attempt in $(seq 1 180); do
    observed="$(adb -s "$serial" exec-out run-as me.rerere.rikkahub.debug cat "files/$marker" 2>/dev/null || true)"
    if [ "$observed" = "$expected" ]; then break; fi
    if ! kill -0 "$driver" 2>/dev/null; then cat "evidence/intentional-crashes/$method-driver.txt"; return 1; fi
    sleep 0.5
  done
  test "$observed" = "$expected"
  pid_before="$(adb -s "$serial" shell pidof me.rerere.rikkahub.debug | tr -d '\r')"
  test -n "$pid_before"
  adb -s "$serial" shell am force-stop me.rerere.rikkahub.debug
  wait "$driver" || true
  test -z "$(adb -s "$serial" shell pidof me.rerere.rikkahub.debug 2>/dev/null || true)"
  python3 - "$method" "$observed" "$pid_before" <<'PY'
import json,sys
from pathlib import Path
Path('evidence/intentional-crashes/'+sys.argv[1]+'.json').write_text(json.dumps({
 'method':sys.argv[1],'verified_window':sys.argv[2],'pid_before':sys.argv[3],
 'external_force_stop':True,'process_absent_after':True,
 'expected_instrumentation_interruption':True,'recovery_pass_requires_separate_test':True},indent=2)+'\n')
PY
}

A=emulator-5554
B=emulator-5556
instrument "$A" localContracts
adb -s "$A" shell svc wifi disable
adb -s "$A" shell svc data disable
adb -s "$A" shell dumpsys connectivity > evidence/offline-network-state.txt
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
crash_window "$B" bootstrapCommitWindow task4-bootstrap-window-ready FIRST_PAGE_PREFS_WITH_CURSOR_ZERO
instrument "$B" verifyBootstrapWindow
adb -s "$B" exec-out run-as me.rerere.rikkahub.debug cat files/task4-bootstrap-first-page-metrics.json > evidence/bootstrap-window-recovery.json
crash_window "$B" olderPageCommitWindow task4-older-page-window-ready OLDER_PAGE_FETCHED_WITHOUT_COMMIT
instrument "$B" verifyOlderPageWindow
instrument "$B" deviceBContinue
# Capture only a task-owned synthetic database after a real process stop; publish counts, not the DB.
adb -s "$B" shell am force-stop me.rerere.rikkahub.debug
snapshot="$RUNNER_TEMP/p14-bootstrap-snapshot.db"
adb -s "$B" exec-out run-as me.rerere.rikkahub.debug cat databases/rikkahub-p14-product.db > "$snapshot"
if adb -s "$B" shell run-as me.rerere.rikkahub.debug test -f databases/rikkahub-p14-product.db-wal; then
  adb -s "$B" exec-out run-as me.rerere.rikkahub.debug cat databases/rikkahub-p14-product.db-wal > "$snapshot-wal"
fi
python3 - "$snapshot" <<'PY'
import json,sqlite3,sys
from pathlib import Path
with sqlite3.connect(sys.argv[1]) as db:
    entries=db.execute('SELECT COUNT(*) FROM p14_entries').fetchone()[0]
    cursor=db.execute('SELECT MAX(last_sync_seq) FROM p14_product_sync_state').fetchone()[0]
assert 1 <= entries <= 150, f'Cold device eagerly restored {entries} entries from 1200+ history'
assert cursor > 0
Path('evidence/large-history-android-bootstrap.json').write_text(json.dumps({
    'server_synthetic_historical_entries':1200,'device_B_local_entries_after_cold_sync_and_one_older_page':entries,
    'device_B_durable_cursor':cursor,'bounded_bootstrap':True,
    'process_stopped_before_snapshot':True,'mid_transaction_kill_verified':True},indent=2)+'\n')
PY
rm -f "$snapshot" "$snapshot-wal" "$snapshot-shm"

instrument "$B" materializeRemoteMedia
adb -s "$B" shell am force-stop me.rerere.rikkahub.debug
instrument "$B" verifyRemoteMedia
instrument "$B" deviceBMutations
instrument "$B" actionDecisionsAndQuickAction
instrument "$A" competingDeviceActionConflict
instrument "$B" seedInteractionOffline
adb -s "$B" shell am force-stop me.rerere.rikkahub.debug
instrument "$B" verifyInteractionRecovery
adb -s "$A" shell am force-stop me.rerere.rikkahub.debug
instrument "$A" deviceAVerify
adb -s "$A" shell pm grant me.rerere.rikkahub.debug android.permission.ACCESS_COARSE_LOCATION
instrument "$A" observationPolicy
instrument "$B" scaleDirectoryPaging
adb -s "$B" exec-out run-as me.rerere.rikkahub.debug cat files/task4-directory-scale-metrics.json > evidence/directory-scale-metrics.json
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
 'repository':os.environ['GITHUB_REPOSITORY'],'android_sha':(p/'android-source-sha.txt').read_text().strip(),'workflow_sha':os.environ['GITHUB_SHA'],
 'run_id':os.environ['GITHUB_RUN_ID'],'run_attempt':os.environ['GITHUB_RUN_ATTEMPT'],
 'server_sha':(p/'server-source-sha.txt').read_text().strip(),
 'emulator_api':35,'abi':'x86_64','devices':['emulator-5554','emulator-5556'],
 'synthetic_data_only':True,'physical_device_acceptance':False,
 'hermes_used':False,'host':platform.platform()},indent=2)+'\n')
PY

