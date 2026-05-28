#!/usr/bin/env bash
set -euo pipefail

API_LEVEL="${1:-}"
TEST_LEVEL="${2:-smoke}"
EVENT_NAME="${3:-push}"

echo "Running instrumented UX tests on API ${API_LEVEL}"

if [ "${TEST_LEVEL}" = "smoke" ] || [ "${EVENT_NAME}" != "workflow_dispatch" ]; then
    ./gradlew connectedMobileLibre3SiDexGoogleDebugAndroidTest \
        --no-daemon \
        --warning-mode=all \
        -Pandroid.testInstrumentationRunnerArguments.size=small
    echo "Smoke tests completed"
fi

if [ "${TEST_LEVEL}" = "full" ] || [ "${TEST_LEVEL}" = "extended" ]; then
    ./gradlew connectedMobileLibre3SiDexGoogleDebugAndroidTest \
        --no-daemon \
        --warning-mode=all
    echo "Full instrumented tests completed"
fi

if [ "${TEST_LEVEL}" = "extended" ]; then
    echo "Running extended soak tests..."
    ./gradlew connectedMobileLibre3SiDexGoogleDebugAndroidTest \
        --no-daemon \
        --warning-mode=all \
        -Pandroid.testInstrumentationRunnerArguments.duration=3600
    echo "Extended tests completed"
fi
