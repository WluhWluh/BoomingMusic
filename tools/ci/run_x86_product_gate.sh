#!/usr/bin/env bash
set -euo pipefail

mkdir -p build/ci/x86-product
adb wait-for-device
adb shell getprop > build/ci/x86-product/device-properties.txt
adb shell pm list packages > build/ci/x86-product/packages-before-test.txt
adb logcat -c || true
adb logcat -v threadtime > build/ci/x86-product/logcat.txt &
logcat_pid=$!

cleanup() {
    status=$?
    kill "$logcat_pid" 2>/dev/null || true
    adb shell ps -A > build/ci/x86-product/processes-after-test.txt 2>&1 || true
    adb shell dumpsys meminfo com.mardous.booming > build/ci/x86-product/meminfo-after-test.txt 2>&1 || true
    exit "$status"
}

trap cleanup EXIT

timeout --foreground --kill-after=30s 45m \
    ./gradlew connectedGithubDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=com.mardous.booming.separation.model.litert.MdxLiteRtX86GithubProductDeviceTest#downloadsAndExecutesStandardGithubProductPath" \
    -Pandroid.testInstrumentationRunnerArguments.runX86GithubProductGate=true \
    --stacktrace
