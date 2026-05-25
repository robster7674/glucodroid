#!/usr/bin/env bash
# device-test-cycle.sh — Phoenix VM cycle for GlucoDroid device tests.
#
# Recreates a Hetzner CAX31 from the latest snapshot, installs the APK,
# runs remote-test-suite.sh via Redroid/ADB, collects results, snapshots
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

# ── 5. Start Redroid + ADB ────────────────────────────────────────────────────
step "Starting Redroid"
ssh_to "$SERVER_IP" bash << 'REDROID'
set -euo pipefail
log() { printf '[%s remote] %s\n' "$(date +%H:%M:%S)" "$*"; }

log "Setting up binderfs …"
modprobe binder_linux 2>/dev/null || true
mkdir -p /dev/binderfs
mount -t binder binder /dev/binderfs 2>/dev/null || true

# Create binder devices if they don't exist yet (idempotent)
python3 -c "
import fcntl, struct, os
BINDER_CTL_ADD = 0xC1086201
fd = os.open('/dev/binderfs/binder-control', os.O_RDONLY)
for name in ['binder', 'hwbinder', 'vndbinder']:
    if not os.path.exists('/dev/binderfs/' + name):
        buf = struct.pack('256sII', name.encode(), 0, 0)
        fcntl.ioctl(fd, BINDER_CTL_ADD, buf)
        print('Created /dev/binderfs/' + name)
os.close(fd)
"

log "Starting Redroid container …"
docker rm -f redroid 2>/dev/null || true
docker run -d \
    --name redroid \
    --privileged \
    -v /dev/binderfs:/dev/binderfs \
    -p 5555:5555 \
    redroid/redroid:12.0.0_64only-latest \
    androidboot.redroid_gpu_mode=guest

log "Waiting for ADB and Android boot_completed (up to 5 min) …"
deadline=$(( $(date +%s) + 300 ))
while true; do
    (( $(date +%s) < deadline )) || { log "Timeout: Redroid did not boot"; exit 1; }
    adb connect localhost:5555 >/dev/null 2>&1 || true
    if adb -s localhost:5555 shell getprop sys.boot_completed 2>/dev/null \
            | tr -d '\r\n' | grep -q '^1$'; then
        break
    fi
    printf '  %s still waiting…\n' "$(date +%H:%M:%S)"
    sleep 5
done

log "Redroid running: $(adb -s localhost:5555 shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
REDROID

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
    "$SERVER_NAME" 2>&1 | grep -oE 'Image [0-9]+' | grep -oE '[0-9]+')
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
