#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/p14-evidence
adb pull /sdcard/Android/data/me.rerere.rikkahub.debug/files/p14-inbox-evidence app/build/p14-evidence/ || true
run_test() {
  adb shell am instrument -w -r -e class "me.rerere.rikkahub.data.remote.p14.P14RecoveryInstrumentedTest#$1" me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "app/build/p14-evidence/$1.txt"
  grep -q 'OK (' "app/build/p14-evidence/$1.txt"
  ! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED' "app/build/p14-evidence/$1.txt"
}
run_test localContracts
run_test seedOffline
adb shell am force-stop me.rerere.rikkahub.debug
run_test verifyOffline
adb logcat -d -s P14_TASK4:I > app/build/p14-evidence/durability-logcat.txt
