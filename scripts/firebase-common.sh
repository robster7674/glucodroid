#!/usr/bin/env bash
# firebase-common.sh — sourced by all Firebase Test Lab scripts.
# Do not execute directly.

_COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_ENV_FILE="$_COMMON_DIR/.env.firebase"
if [[ -f "$_ENV_FILE" ]]; then
    # shellcheck source=/dev/null
    source "$_ENV_FILE"
fi

# ── Required variables ────────────────────────────────────────────────────────
: "${FIREBASE_PROJECT_ID:?FIREBASE_PROJECT_ID not set. Copy scripts/.env.firebase.example to scripts/.env.firebase and fill it in.}"
: "${GOOGLE_APPLICATION_CREDENTIALS:?GOOGLE_APPLICATION_CREDENTIALS not set.}"
: "${FIREBASE_RESULTS_BUCKET:?FIREBASE_RESULTS_BUCKET not set.}"
export GOOGLE_APPLICATION_CREDENTIALS

# ── Defaults ─────────────────────────────────────────────────────────────────
FIREBASE_DEVICE_MODEL="${FIREBASE_DEVICE_MODEL:-oriole}"
FIREBASE_DEVICE_VERSION="${FIREBASE_DEVICE_VERSION:-33}"
FIREBASE_DEVICE_LOCALE="${FIREBASE_DEVICE_LOCALE:-en}"
FIREBASE_DEVICE_ORIENTATION="${FIREBASE_DEVICE_ORIENTATION:-portrait}"
FIREBASE_TEST_TIMEOUT="${FIREBASE_TEST_TIMEOUT:-10m}"
FIREBASE_RESULTS_BASE="${FIREBASE_RESULTS_BASE:-./firebase-test-results}"

# ── Logging ───────────────────────────────────────────────────────────────────
log()  { printf '[%s] %s\n'        "$(date +%H:%M:%S)" "$*"; }
step() { printf '\n[%s] ── %s ──\n' "$(date +%H:%M:%S)" "$*"; }
die()  { printf '[%s] ERROR: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

# ── Helpers ───────────────────────────────────────────────────────────────────

# require_tools tool [tool ...]
require_tools() {
    for cmd in "$@"; do
        command -v "$cmd" >/dev/null || die "'$cmd' not found — install it first"
    done
}

# gcloud_auth — activate the service account
gcloud_auth() {
    local sa_key="${GOOGLE_APPLICATION_CREDENTIALS/#\~/$HOME}"
    [[ -f "$sa_key" ]] || die "Service account key not found: $sa_key"
    gcloud auth activate-service-account --key-file="$sa_key" --quiet
    gcloud config set project "$FIREBASE_PROJECT_ID" --quiet
    log "Authenticated as $(gcloud config get-value account 2>/dev/null)"
}

# firebase_run APP_APK TEST_APK RESULTS_GCS_DIR
# Runs an instrumented test on Firebase Test Lab; streams output.
# Returns the gcloud exit code.
firebase_run() {
    local app_apk="$1" test_apk="$2" results_gcs_dir="$3"
    gcloud firebase test android run \
        --type instrumentation \
        --app "$app_apk" \
        --test "$test_apk" \
        --device "model=${FIREBASE_DEVICE_MODEL},version=${FIREBASE_DEVICE_VERSION},locale=${FIREBASE_DEVICE_LOCALE},orientation=${FIREBASE_DEVICE_ORIENTATION}" \
        --project "$FIREBASE_PROJECT_ID" \
        --results-bucket "$FIREBASE_RESULTS_BUCKET" \
        --results-dir "$results_gcs_dir" \
        --environment-variables clearPackageData=true \
        --timeout "$FIREBASE_TEST_TIMEOUT" \
        --format=json 2>&1
}

# download_results GCS_DIR LOCAL_DIR
# Pulls all test artifacts from GCS to a local directory.
download_results() {
    local gcs_dir="$1" local_dir="$2"
    mkdir -p "$local_dir"
    gsutil -m cp -r "gs://${FIREBASE_RESULTS_BUCKET}/${gcs_dir}/**" "$local_dir/" 2>/dev/null || true
}

# find_apk FLAVOR BUILD_TYPE
# Prints the path to the built APK; dies if not found.
find_apk() {
    local flavor="$1" build_type="$2"
    local path
    path=$(find "Common/build/outputs/apk/${flavor}/${build_type}" \
                -name "*.apk" ! -name "*-unsigned*" 2>/dev/null | head -1)
    [[ -n "$path" ]] || die "APK not found for flavor=$flavor build_type=$build_type"
    echo "$path"
}

# find_test_apk FLAVOR BUILD_TYPE
find_test_apk() {
    local flavor="$1" build_type="$2"
    local path
    path=$(find "Common/build/outputs/apk/androidTest/${flavor}/${build_type}" \
                -name "*.apk" 2>/dev/null | head -1)
    [[ -n "$path" ]] || die "Test APK not found for flavor=$flavor build_type=$build_type"
    echo "$path"
}
