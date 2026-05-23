#!/usr/bin/env bash
# device-test-cycle.sh — Phoenix VM cycle for GlucoDroid device tests.
#
# Recreates a Hetzner CAX31 from the latest snapshot, installs the APK,
# runs remote-test-suite.sh via Waydroid/ADB, collects results, snapshots
# updated state, and destroys the server.  Total wall time: ~20 min.
#
# Usage:
#   ./scripts/device-test-cycle.sh [/path/to/glucodroid.apk]
#
# Credentials: copy scripts/.env.hetzner.example → scripts/.env.hetzner
# and fill in HCLOUD_TOKEN, HCLOUD_SSH_KEY, TEST_SSH_KEY.
#
# On failure: the server is left running for investigation.
# The script prints the SSH command and the hcloud delete command.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/hetzner-common.sh"

APK_PATH="${1:-$HOME/Downloads/glucodroid.apk}"
RESULTS_DIR="$RESULTS_BASE/$(date +%Y%m%d-%H%M%S)"
SERVER_NAME="glucodroid-test-$(date +%Y%m%d-%H%M%S)"
SERVER_ID=""
SERVER_IP=""

# ── Failure handler ───────────────────────────────────────────────────────────
on_error() {
    log "Cycle failed at line $1."
    if [[ -n "$SERVER_ID" ]]; then
        log "Server '$SERVER_NAME' left running for investigation."
        log "  SSH:    ssh -i \$TEST_SSH_KEY root@${SERVER_IP:-<check hcloud>}"
        log "  Delete: hcloud server delete $SERVER_NAME"
    fi
}
trap 'on_error $LINENO' ERR

# ── Pre-flight ────────────────────────────────────────────────────────────────
require_tools hcloud jq ssh scp
[[ -f "$APK_PATH" ]] \
    || die "APK not found: $APK_PATH — build it first:\n  ./gradlew assembleMobileLibre3SiDexGoogleReleaser -Pno_x86 -Pno_x86_64"
[[ -f "$TEST_SSH_KEY" ]] || die "SSH key not found: $TEST_SSH_KEY"

mkdir -p "$RESULTS_DIR"
log "Results will be saved to: $RESULTS_DIR"

# ── 1. Find snapshot ──────────────────────────────────────────────────────────
step "Finding snapshot"
SNAPSHOT_ID=$(latest_snapshot_id)
[[ -n "$SNAPSHOT_ID" ]] \
    || die "No snapshot with label '$SNAPSHOT_LABEL'. Run hetzner-setup.sh first."
SNAPSHOT_DESC=$(hcloud image describe "$SNAPSHOT_ID" -o json | jq -r '.description')
log "Using snapshot $SNAPSHOT_ID ($SNAPSHOT_DESC)"

# ── 2. Create server ──────────────────────────────────────────────────────────
step "Creating server"
SERVER_ID=$(hcloud server create \
    --name "$SERVER_NAME" \
    --type "$SERVER_TYPE" \
    --image "$SNAPSHOT_ID" \
    --ssh-key "$HCLOUD_SSH_KEY" \
    --location "$SERVER_LOCATION" \
    -o json | jq -r '.server.id')
SERVER_IP=$(server_ip "$SERVER_NAME")
log "Created $SERVER_NAME (id=$SERVER_ID, ip=$SERVER_IP, type=$SERVER_TYPE)"

# ── 3. Wait for boot ──────────────────────────────────────────────────────────
step "Waiting for boot"
wait_for_ssh "$SERVER_IP"

# ── 4. Transfer files ─────────────────────────────────────────────────────────
step "Transferring APK and test suite"
APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
log "APK: $APK_PATH ($APK_SIZE)"
scp_up "$SERVER_IP" "$APK_PATH"                   /tmp/glucodroid.apk
scp_up "$SERVER_IP" "$SCRIPT_DIR/remote-test-suite.sh" /tmp/remote-test-suite.sh
ssh_to "$SERVER_IP" chmod +x /tmp/remote-test-suite.sh

# ── 5. Start Waydroid + ADB ───────────────────────────────────────────────────
step "Starting Waydroid"
ssh_to "$SERVER_IP" bash << 'WAYDROID'
set -euo pipefail
log() { printf '[%s remote] %s\n' "$(date +%H:%M:%S)" "$*"; }

# Load binder if not present
ls /dev/binder* >/dev/null 2>&1 \
    || modprobe binder_linux devices="binder,hwbinder,vndbinder" 2>/dev/null \
    || true

# Stop any stale session from a previous cycle
waydroid session stop 2>/dev/null || true
sleep 2

log "Starting Waydroid session …"
waydroid session start &

# Wait for ADB to become available (up to 3 min)
deadline=$(( $(date +%s) + 180 ))
until adb connect localhost:5555 2>&1 | grep -q "connected\|already"; do
    (( $(date +%s) < deadline )) || { echo "Waydroid/ADB did not become ready"; exit 1; }
    sleep 5
done

# Give Android system services time to settle
sleep 15
log "Waydroid running: $(adb -s localhost:5555 shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
WAYDROID

# ── 6. Install APK ────────────────────────────────────────────────────────────
step "Installing APK"
ssh_to "$SERVER_IP" bash << 'INSTALL'
adb -s localhost:5555 install -r /tmp/glucodroid.apk
adb -s localhost:5555 shell dumpsys package cloud.glucodroid \
    | grep -E "versionName|versionCode" | head -2
INSTALL

# ── 7. Run test suite ─────────────────────────────────────────────────────────
step "Running device test suite"
SUITE_EXIT=0
ssh_to "$SERVER_IP" /tmp/remote-test-suite.sh || SUITE_EXIT=$?

# ── 8. Collect results ────────────────────────────────────────────────────────
step "Collecting results"
scp_down "$SERVER_IP" /tmp/test-results/. "$RESULTS_DIR/"
log "Results saved to $RESULTS_DIR"

# Print summary
if [[ -f "$RESULTS_DIR/summary.txt" ]]; then
    echo ""
    cat "$RESULTS_DIR/summary.txt"
    echo ""
fi

# ── 9. Shutdown ───────────────────────────────────────────────────────────────
step "Shutting down"
hcloud server shutdown "$SERVER_NAME"
if ! wait_for_state "$SERVER_NAME" off 90; then
    log "Clean shutdown timed out — forcing off."
    hcloud server poweroff "$SERVER_NAME"
    wait_for_state "$SERVER_NAME" off 30 || die "Server did not power off"
fi
log "Server off."

# ── 10. Rotate snapshot ───────────────────────────────────────────────────────
step "Rotating snapshot"
NEW_DESC="glucodroid-device-test-$(date +%Y%m%d)"
NEW_ID=$(hcloud server create-image \
    --type snapshot \
    --description "$NEW_DESC" \
    --label "${SNAPSHOT_LABEL%%=*}=${SNAPSHOT_LABEL##*=}" \
    "$SERVER_NAME" \
    -o json | jq -r '.image.id')
log "New snapshot: $NEW_ID ($NEW_DESC)"
log "Deleting old snapshot: $SNAPSHOT_ID"
hcloud image delete "$SNAPSHOT_ID"

# ── 11. Delete server ─────────────────────────────────────────────────────────
step "Deleting server"
hcloud server delete "$SERVER_NAME"
SERVER_ID=""     # disarm failure trap

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
log "Cycle complete."
log "Results:  $RESULTS_DIR/"
log "Trace:    open $RESULTS_DIR/trace.pb at https://ui.perfetto.dev"
[[ -s "$RESULTS_DIR/crashes.txt" ]] && log "⚠  Crashes: $RESULTS_DIR/crashes.txt"
exit "$SUITE_EXIT"
