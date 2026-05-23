#!/usr/bin/env bash
# device-test-cycle.sh
#
# Recreate a Hetzner CAX21 from the latest test snapshot, install the APK,
# run the Waydroid device test suite, collect results, snapshot the new state,
# and destroy the server.  Total wall time: ~15–20 minutes.  Server only exists
# while tests are running, keeping costs to a few cents per run.
#
# Usage:
#   ./scripts/device-test-cycle.sh [/path/to/glucodroid.apk]
#
# Required environment:
#   HCLOUD_TOKEN      Hetzner Cloud API token
#   HCLOUD_SSH_KEY    Name of the SSH public key registered in Hetzner Cloud
#   TEST_SSH_KEY      Path to the matching private key file on this machine
#
# Optional environment (defaults shown):
#   SERVER_TYPE       cax21
#   SERVER_LOCATION   nbg1
#   SNAPSHOT_LABEL    role=glucodroid-device-test
#   RESULTS_BASE      ./device-test-results
#
# First-time setup:
#   See TESTING.md — you need to create the initial snapshot manually before
#   this script can run.

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────

SNAPSHOT_LABEL="${SNAPSHOT_LABEL:-role=glucodroid-device-test}"
SERVER_TYPE="${SERVER_TYPE:-cax21}"
SERVER_LOCATION="${SERVER_LOCATION:-nbg1}"
RESULTS_BASE="${RESULTS_BASE:-./device-test-results}"
APK_PATH="${1:-$HOME/Downloads/glucodroid.apk}"

: "${HCLOUD_TOKEN:?HCLOUD_TOKEN is required}"
: "${HCLOUD_SSH_KEY:?HCLOUD_SSH_KEY (Hetzner key name) is required}"
: "${TEST_SSH_KEY:?TEST_SSH_KEY (local private key path) is required}"

SERVER_NAME="glucodroid-test-$(date +%Y%m%d-%H%M%S)"
RESULTS_DIR="$RESULTS_BASE/$(date +%Y%m%d-%H%M%S)"
SERVER_ID=""

# ── Helpers ───────────────────────────────────────────────────────────────────

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die()  { log "ERROR: $*" >&2; exit 1; }
step() { printf '\n[%s] ── %s ──\n' "$(date +%H:%M:%S)" "$*"; }

server_ip() {
    hcloud server describe "$SERVER_NAME" -o json | jq -r '.public_net.ipv4.ip'
}

ssh_run() {
    ssh -o StrictHostKeyChecking=no \
        -o BatchMode=yes \
        -o ConnectTimeout=15 \
        -i "$TEST_SSH_KEY" \
        "root@$(server_ip)" "$@"
}

scp_get() {          # scp_get remote_path local_path
    scp -q \
        -o StrictHostKeyChecking=no \
        -i "$TEST_SSH_KEY" \
        "root@$(server_ip):$1" "$2"
}

wait_for_ssh() {
    local ip; ip=$(server_ip)
    log "Waiting for SSH on $ip …"
    local deadline=$(( $(date +%s) + 180 ))
    until ssh -o StrictHostKeyChecking=no -o BatchMode=yes -o ConnectTimeout=5 \
              -i "$TEST_SSH_KEY" "root@$ip" true 2>/dev/null; do
        (( $(date +%s) < deadline )) || die "SSH did not become available within 3 minutes"
        sleep 5
    done
    log "SSH ready."
}

wait_for_server_state() {   # wait_for_server_state <state> <timeout_s>
    local want="$1" timeout="$2"
    local deadline=$(( $(date +%s) + timeout ))
    until hcloud server describe "$SERVER_NAME" -o json \
          | jq -e --arg s "$want" '.status == $s' >/dev/null 2>&1; do
        (( $(date +%s) < deadline )) || return 1
        sleep 5
    done
}

# ── Failure handler ───────────────────────────────────────────────────────────
# On error: leave the server running so you can SSH in to investigate.
# Run 'hcloud server delete <SERVER_NAME>' manually when done.

on_error() {
    log "Test cycle failed at line $1."
    if [[ -n "$SERVER_ID" ]]; then
        log "Server '$SERVER_NAME' (id=$SERVER_ID) is still running."
        log "SSH:    ssh -i \$TEST_SSH_KEY root@$(server_ip 2>/dev/null || echo '<unknown>')"
        log "Delete: hcloud server delete $SERVER_NAME"
    fi
}
trap 'on_error $LINENO' ERR

# ── Validate tools ────────────────────────────────────────────────────────────

for cmd in hcloud jq ssh scp; do
    command -v "$cmd" >/dev/null || die "'$cmd' not found — install it first"
done
[[ -f "$APK_PATH" ]] || die "APK not found: $APK_PATH  (build it first with: ./gradlew assembleMobileLibre3SiDexGoogleReleaser -Pno_x86 -Pno_x86_64)"
[[ -f "$TEST_SSH_KEY" ]] || die "SSH key not found: $TEST_SSH_KEY"

mkdir -p "$RESULTS_DIR"

# ── 1. Find latest snapshot ───────────────────────────────────────────────────

step "Finding snapshot"
SNAPSHOT_JSON=$(hcloud image list --type snapshot --selector "$SNAPSHOT_LABEL" -o json)
SNAPSHOT_ID=$(echo "$SNAPSHOT_JSON" | jq -r 'sort_by(.created) | last | .id // empty')
SNAPSHOT_DESC=$(echo "$SNAPSHOT_JSON" | jq -r 'sort_by(.created) | last | .description // "unknown"')
[[ -n "$SNAPSHOT_ID" ]] || die "No snapshot with label '$SNAPSHOT_LABEL'. See TESTING.md for first-time setup."
log "Snapshot: $SNAPSHOT_ID ($SNAPSHOT_DESC)"

# ── 2. Create server ──────────────────────────────────────────────────────────

step "Creating server"
SERVER_ID=$(hcloud server create \
    --name "$SERVER_NAME" \
    --type "$SERVER_TYPE" \
    --image "$SNAPSHOT_ID" \
    --ssh-key "$HCLOUD_SSH_KEY" \
    --location "$SERVER_LOCATION" \
    -o json | jq -r '.server.id')
log "Created server '$SERVER_NAME' (id=$SERVER_ID, type=$SERVER_TYPE, location=$SERVER_LOCATION)"

# ── 3. Wait for boot ──────────────────────────────────────────────────────────

step "Waiting for boot"
wait_for_ssh

# ── 4. Transfer APK ───────────────────────────────────────────────────────────

step "Transferring APK"
log "APK: $APK_PATH ($(du -h "$APK_PATH" | cut -f1))"
scp -q -o StrictHostKeyChecking=no -i "$TEST_SSH_KEY" \
    "$APK_PATH" "root@$(server_ip):/tmp/glucodroid.apk"
log "Transfer complete."

# ── 5. Start Waydroid ─────────────────────────────────────────────────────────

step "Starting Waydroid"
ssh_run bash <<'REMOTE'
set -euo pipefail
log() { printf '[%s remote] %s\n' "$(date +%H:%M:%S)" "$*"; }

# Ensure binder is loaded
if ! ls /dev/binder* >/dev/null 2>&1; then
    modprobe binder_linux devices="binder,hwbinder,vndbinder" || true
fi

# Stop any stale session from a previous cycle
waydroid session stop 2>/dev/null || true
sleep 2

# Start fresh session in background
log "Starting Waydroid session …"
waydroid session start &

# Wait up to 2 minutes for Android to fully boot
deadline=$(( $(date +%s) + 120 ))
until adb connect localhost:5555 2>&1 | grep -q "connected"; do
    (( $(date +%s) < deadline )) || { echo "Waydroid/ADB did not become ready in time"; exit 1; }
    sleep 5
done

# Give Android system services a moment to settle
sleep 10
log "Waydroid + ADB ready."
REMOTE

# ── 6. Install APK ────────────────────────────────────────────────────────────

step "Installing APK"
ssh_run bash <<'REMOTE'
set -euo pipefail
log() { printf '[%s remote] %s\n' "$(date +%H:%M:%S)" "$*"; }
log "Installing cloud.glucodroid …"
adb -s localhost:5555 install -r /tmp/glucodroid.apk
log "Installed."
adb -s localhost:5555 shell dumpsys package cloud.glucodroid \
    | grep -E "versionName|versionCode" | head -4
REMOTE

# ── 7. Run tests ──────────────────────────────────────────────────────────────

step "Running device tests"
ssh_run bash <<'REMOTE'
set -euo pipefail
log() { printf '[%s remote] %s\n' "$(date +%H:%M:%S)" "$*"; }
ADB="adb -s localhost:5555"
mkdir -p /tmp/test-results

# Clear logcat buffer
$ADB logcat -c

# Launch the app
log "Launching cloud.glucodroid …"
$ADB shell am start -n cloud.glucodroid/.MainActivity
sleep 10

# Check for immediate crash
if $ADB shell pidof cloud.glucodroid >/dev/null 2>&1; then
    log "App is running (PID $($ADB shell pidof cloud.glucodroid))."
else
    log "WARNING: app process not found after launch — possible crash."
fi

# Capture a Perfetto trace while the app is live
log "Capturing 15-second Perfetto trace …"
$ADB shell perfetto \
    --txt -c - \
    -o /data/local/tmp/trace.pb <<'PERFETTO_CFG'
buffers: { size_kb: 32768 }
data_sources: { config { name: "linux.ftrace"
  ftrace_config {
    ftrace_events: "sched/sched_switch"
    ftrace_events: "sched/sched_wakeup"
    atrace_categories: "am"
    atrace_categories: "view"
    atrace_categories: "wm"
    atrace_apps: "cloud.glucodroid"
  } } }
data_sources: { config { name: "android.surfaceflinger.frametimeline" } }
duration_ms: 15000
PERFETTO_CFG

$ADB pull /data/local/tmp/trace.pb /tmp/test-results/trace.pb

# Dump logcat (all tags, full history since clear)
log "Collecting logcat …"
$ADB logcat -d -v threadtime > /tmp/test-results/logcat.txt

# Check for crash signals in logcat
CRASHES=$(grep -c "FATAL EXCEPTION\|AndroidRuntime" /tmp/test-results/logcat.txt || true)
if (( CRASHES > 0 )); then
    log "WARNING: $CRASHES crash signal(s) detected in logcat."
    grep -A 20 "FATAL EXCEPTION\|AndroidRuntime" /tmp/test-results/logcat.txt \
        > /tmp/test-results/crashes.txt
else
    log "No crash signals detected."
fi

# Package info
$ADB shell dumpsys package cloud.glucodroid \
    | grep -E "versionName|versionCode|lastUpdateTime|firstInstallTime" \
    > /tmp/test-results/package-info.txt

# BatteryTrace emission counts (look for our custom trace keys in logcat)
grep "BatteryTrace\|dashboard.history\|glucose.native" \
    /tmp/test-results/logcat.txt > /tmp/test-results/battery-trace.txt || true

log "Device test phase complete."
REMOTE

# ── 8. Fetch results ──────────────────────────────────────────────────────────

step "Fetching results"
scp -q -o StrictHostKeyChecking=no -i "$TEST_SSH_KEY" -r \
    "root@$(server_ip):/tmp/test-results/." \
    "$RESULTS_DIR/"

CRASH_FILE="$RESULTS_DIR/crashes.txt"
if [[ -s "$CRASH_FILE" ]]; then
    log "⚠  Crashes found — see $CRASH_FILE"
else
    log "No crashes."
fi
log "Trace: open $RESULTS_DIR/trace.pb at https://ui.perfetto.dev"

# ── 9. Shut down server ───────────────────────────────────────────────────────

step "Shutting down server"
hcloud server shutdown "$SERVER_NAME"
wait_for_server_state "off" 90 || {
    log "Clean shutdown timed out — forcing off."
    hcloud server poweroff "$SERVER_NAME"
    wait_for_server_state "off" 30 || die "Server did not power off"
}
log "Server off."

# ── 10. Create new snapshot ───────────────────────────────────────────────────

step "Creating snapshot"
NEW_SNAP_DESC="glucodroid-device-test-$(date +%Y%m%d)"
NEW_SNAPSHOT_ID=$(hcloud server create-image \
    --type snapshot \
    --description "$NEW_SNAP_DESC" \
    --label "${SNAPSHOT_LABEL%%=*}=${SNAPSHOT_LABEL##*=}" \
    "$SERVER_NAME" \
    -o json | jq -r '.image.id')
log "New snapshot: $NEW_SNAPSHOT_ID ($NEW_SNAP_DESC)"

# ── 11. Delete old snapshot ───────────────────────────────────────────────────

step "Rotating snapshot"
log "Deleting old snapshot $SNAPSHOT_ID …"
hcloud image delete "$SNAPSHOT_ID"

# ── 12. Delete server ─────────────────────────────────────────────────────────

step "Deleting server"
hcloud server delete "$SERVER_NAME"
SERVER_ID=""      # disarm the failure trap
log "Server deleted."

# ── Done ──────────────────────────────────────────────────────────────────────

printf '\n[%s] ── Cycle complete ──\n' "$(date +%H:%M:%S)"
log "Results:       $RESULTS_DIR/"
log "Perfetto:      open $RESULTS_DIR/trace.pb at https://ui.perfetto.dev"
log "Logcat:        $RESULTS_DIR/logcat.txt"
[[ -s "$RESULTS_DIR/crashes.txt" ]] && log "⚠  Crashes:     $RESULTS_DIR/crashes.txt"
