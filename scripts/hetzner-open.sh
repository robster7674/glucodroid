#!/usr/bin/env bash
# hetzner-open.sh — Create a CAX31 from the latest snapshot for manual investigation.
# The server is NOT automatically deleted — destroy it with hetzner-cleanup.sh
# or: hcloud server delete <name>
#
# Usage:
#   ./scripts/hetzner-open.sh [/path/to/glucodroid.apk]

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/hetzner-common.sh"

APK_PATH="${1:-$HOME/Downloads/glucodroid.apk}"
SERVER_NAME="glucodroid-open-$(date +%Y%m%d-%H%M%S)"

require_tools hcloud jq ssh scp

# ── Find snapshot ─────────────────────────────────────────────────────────────
step "Finding snapshot"
SNAPSHOT_ID=$(latest_snapshot_id)
[[ -n "$SNAPSHOT_ID" ]] || die "No snapshot found. Run hetzner-setup.sh first."
log "Using snapshot $SNAPSHOT_ID"

# ── Create server ─────────────────────────────────────────────────────────────
step "Creating server"
SERVER_ID=$(hcloud server create \
    --name "$SERVER_NAME" \
    --type "$SERVER_TYPE" \
    --image "$SNAPSHOT_ID" \
    --ssh-key "$HCLOUD_SSH_KEY" \
    --location "$SERVER_LOCATION" \
    -o json | jq -r '.server.id')
SERVER_IP=$(server_ip "$SERVER_NAME")
log "Created $SERVER_NAME (id=$SERVER_ID, ip=$SERVER_IP)"

# ── Wait for boot ─────────────────────────────────────────────────────────────
step "Waiting for boot"
wait_for_ssh "$SERVER_IP"

# ── Transfer APK if present ───────────────────────────────────────────────────
if [[ -f "$APK_PATH" ]]; then
    step "Uploading APK"
    scp_up "$SERVER_IP" "$APK_PATH" /tmp/glucodroid.apk
    log "APK at /tmp/glucodroid.apk on the server."
fi

# ── Start Waydroid ────────────────────────────────────────────────────────────
step "Starting Waydroid"
ssh_to "$SERVER_IP" bash << 'REMOTE'
modprobe binder_linux devices="binder,hwbinder,vndbinder" 2>/dev/null || true
waydroid session stop 2>/dev/null || true
sleep 2
waydroid session start &
deadline=$(( $(date +%s) + 180 ))
until adb connect localhost:5555 2>&1 | grep -q "connected\|already"; do
    (( $(date +%s) < deadline )) || { echo "Waydroid did not start"; exit 1; }
    sleep 5
done
sleep 10
echo "Waydroid ready: $(adb -s localhost:5555 shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
REMOTE

# ── Print access instructions ─────────────────────────────────────────────────
echo ""
log "Server is ready."
echo ""
echo "  SSH:     ssh -i \$TEST_SSH_KEY root@$SERVER_IP"
echo ""
echo "  Install: ssh root@$SERVER_IP 'adb -s localhost:5555 install -r /tmp/glucodroid.apk'"
echo "  Launch:  ssh root@$SERVER_IP 'adb -s localhost:5555 shell am start -n cloud.glucodroid/.MainActivity'"
echo "  Logcat:  ssh root@$SERVER_IP 'adb -s localhost:5555 logcat'"
echo ""
echo "  Delete when done:"
echo "    hcloud server delete $SERVER_NAME"
echo ""
log "This server is NOT deleted automatically. Cost: ~€$(echo "scale=4; 16.49/730" | bc)/hr while running."
