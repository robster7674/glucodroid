#!/usr/bin/env bash
# firebase-test.sh — Build debug + test APKs, run on Firebase Test Lab,
# download results, and process Battery Historian / wakelock data.
#
# Usage:
#   ./scripts/firebase-test.sh [smoke|full]
#
# Credentials: scripts/.env.firebase must exist and be filled in.
# Auth: GOOGLE_APPLICATION_CREDENTIALS must point to a valid service account key.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/firebase-common.sh"

TEST_LEVEL="${1:-smoke}"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
RESULTS_GCS_DIR="glucodroid-${TEST_LEVEL}-${RUN_ID}"
RESULTS_LOCAL="${FIREBASE_RESULTS_BASE}/${RUN_ID}"
FLAVOR="mobileLibre3SiDexNogoogle"
BUILD_TYPE="debug"

require_tools gcloud gsutil gradle gh python3

# ── 1. Authenticate ───────────────────────────────────────────────────────────
step "Authenticating with Firebase"
gcloud_auth

# ── 2. Build APKs ─────────────────────────────────────────────────────────────
step "Building debug + test APKs (flavor: $FLAVOR)"
cd "$REPO_ROOT"
./gradlew \
    ":Common:assembleMobileLibre3SiDexNogoogleDebug" \
    ":Common:assembleMobileLibre3SiDexNogoogleDebugAndroidTest" \
    --no-daemon \
    --warning-mode=all \
    -Pno_x86 -Pno_x86_64

APP_APK="$(find_apk "$FLAVOR" "$BUILD_TYPE")"
TEST_APK="$(find_test_apk "$FLAVOR" "$BUILD_TYPE")"
log "App APK:  $APP_APK ($(du -sh "$APP_APK" | cut -f1))"
log "Test APK: $TEST_APK ($(du -sh "$TEST_APK" | cut -f1))"

# ── 3. Run on Firebase Test Lab ───────────────────────────────────────────────
step "Submitting to Firebase Test Lab (device: ${FIREBASE_DEVICE_MODEL} API${FIREBASE_DEVICE_VERSION})"
log "GCS results dir: gs://${FIREBASE_RESULTS_BUCKET}/${RESULTS_GCS_DIR}"
mkdir -p "$RESULTS_LOCAL"

RUN_JSON="$RESULTS_LOCAL/firebase-run.json"
TEST_EXIT=0
firebase_run "$APP_APK" "$TEST_APK" "$RESULTS_GCS_DIR" \
    | tee "$RUN_JSON" "$RESULTS_LOCAL/firebase-run.log" \
    || TEST_EXIT=$?

log "Firebase run exit code: $TEST_EXIT"

# ── 4. Download artifacts ─────────────────────────────────────────────────────
step "Downloading results from GCS"
download_results "$RESULTS_GCS_DIR" "$RESULTS_LOCAL/artifacts"
log "Artifacts saved to: $RESULTS_LOCAL/artifacts"

# ── 5. Extract batterystats ───────────────────────────────────────────────────
step "Processing battery / wakelock data"
BATT_FILE=$(find "$RESULTS_LOCAL/artifacts" -name "batterystats*" 2>/dev/null | head -1 || true)
LOGCAT_FILE=$(find "$RESULTS_LOCAL/artifacts" -name "logcat*" 2>/dev/null | head -1 || true)

if [[ -n "$BATT_FILE" ]]; then
    log "Found batterystats: $BATT_FILE"
    # Extract wakelock summary using Python
    python3 << PYEOF
import re, sys

path = "$BATT_FILE"
try:
    data = open(path).read()
except Exception as e:
    print(f"Could not read batterystats: {e}")
    sys.exit(0)

wakelocks = re.findall(r'Wake lock\s+([\w.:]+)\s+(.+)', data)
alarms    = re.findall(r'Alarm\s+([\w.:]+)\s+(.+)', data)

print(f"\n=== Wakelock Summary ({len(wakelocks)} entries) ===")
for name, detail in wakelocks[:20]:
    print(f"  {name}: {detail}")
if len(wakelocks) > 20:
    print(f"  ... and {len(wakelocks)-20} more")

print(f"\n=== Alarm Summary ({len(alarms)} entries) ===")
for name, detail in alarms[:20]:
    print(f"  {name}: {detail}")
if len(alarms) > 20:
    print(f"  ... and {len(alarms)-20} more")
PYEOF
else
    log "No batterystats file found in artifacts (device may not have emitted it)."
    log "Check logcat for wakelock-related output instead."
    if [[ -n "$LOGCAT_FILE" ]]; then
        log "Scanning logcat for power-related tags..."
        grep -iE "(wakelock|WakeLock|PowerManager|JobScheduler|AlarmManager|BLE|BleScan|Doze)" \
            "$LOGCAT_FILE" 2>/dev/null \
            | tail -50 > "$RESULTS_LOCAL/power-logcat.txt" || true
        log "Power-related logcat lines → $RESULTS_LOCAL/power-logcat.txt"
    fi
fi

# ── 6. Print summary ──────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════"
echo "  Firebase Test Lab Run: $RUN_ID"
echo "  Level:    $TEST_LEVEL"
echo "  Device:   ${FIREBASE_DEVICE_MODEL} (API ${FIREBASE_DEVICE_VERSION})"
echo "  GCS:      gs://${FIREBASE_RESULTS_BUCKET}/${RESULTS_GCS_DIR}"
echo "  Local:    $RESULTS_LOCAL"
echo "  Outcome:  $([ $TEST_EXIT -eq 0 ] && echo PASSED || echo FAILED)"
echo "════════════════════════════════════════"
echo ""
ls -lh "$RESULTS_LOCAL/artifacts/" 2>/dev/null || true

exit "$TEST_EXIT"
