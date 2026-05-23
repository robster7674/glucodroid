#!/usr/bin/env bash
# hetzner-setup.sh — One-time provisioning of the base Waydroid snapshot.
#
# Creates a CAX31, installs Waydroid and the Android image, verifies ADB
# connectivity, snapshots the result, and deletes the server.
# Run this once before device-test-cycle.sh can be used.
#
# Usage:
#   ./scripts/hetzner-setup.sh
#
# Credentials: copy scripts/.env.hetzner.example → scripts/.env.hetzner

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/hetzner-common.sh"

SERVER_NAME="glucodroid-base-$(date +%Y%m%d-%H%M%S)"
SERVER_ID=""
SERVER_IP=""

on_error() {
    log "Setup failed at line $1."
    [[ -n "$SERVER_ID" ]] && log "Server '$SERVER_NAME' left running. Delete with: hcloud server delete $SERVER_NAME"
}
trap 'on_error $LINENO' ERR

require_tools hcloud jq ssh scp

# Warn if a snapshot already exists
EXISTING=$(latest_snapshot_id || true)
if [[ -n "$EXISTING" ]]; then
    log "WARNING: snapshot $EXISTING already exists for label '$SNAPSHOT_LABEL'."
    read -rp "Continue and create another? [y/N] " ans
    [[ "$ans" =~ ^[Yy]$ ]] || exit 0
fi

# ── 1. Create server ──────────────────────────────────────────────────────────
step "Creating base server ($SERVER_TYPE, $SERVER_LOCATION)"
SERVER_ID=$(hcloud server create \
    --name "$SERVER_NAME" \
    --type "$SERVER_TYPE" \
    --image ubuntu-24.04 \
    --ssh-key "$HCLOUD_SSH_KEY" \
    --location "$SERVER_LOCATION" \
    -o json | jq -r '.server.id')
SERVER_IP=$(server_ip "$SERVER_NAME")
log "Created $SERVER_NAME (id=$SERVER_ID, ip=$SERVER_IP)"

# ── 2. Wait for boot ──────────────────────────────────────────────────────────
step "Waiting for boot"
wait_for_ssh "$SERVER_IP"

# ── 3. Install Waydroid ───────────────────────────────────────────────────────
step "Installing Waydroid (this takes 5–10 min, Android image is ~800 MB)"
ssh_to "$SERVER_IP" bash << 'PROVISION'
set -euo pipefail
log() { printf '[%s remote] %s\n' "$(date +%H:%M:%S)" "$*"; }

export DEBIAN_FRONTEND=noninteractive

log "Updating packages …"
apt-get update -q
apt-get install -yq curl python3 adb lzip

log "Adding Waydroid repo …"
curl -fsSL https://repo.waydro.id | bash -s -- focal
apt-get install -yq waydroid

log "Loading binder kernel module …"
modprobe binder_linux devices="binder,hwbinder,vndbinder" 2>/dev/null || true
ls /dev/binder* || { echo "ERROR: binder device not found"; exit 1; }

log "Initialising Waydroid (downloading Android image) …"
waydroid init -s GAPPS -f

log "Starting Waydroid session for ADB verification …"
waydroid session start &
deadline=$(( $(date +%s) + 300 ))
until adb connect localhost:5555 2>&1 | grep -q "connected\|already"; do
    (( $(date +%s) < deadline )) || { echo "Waydroid did not start in 5 min"; exit 1; }
    sleep 5
done
sleep 15

log "ADB connected: $(adb -s localhost:5555 shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
log "Waydroid install verified."

# Stop the session cleanly before snapshotting
waydroid session stop 2>/dev/null || true
sleep 3
log "Session stopped — ready for snapshot."
PROVISION

# ── 4. Shutdown ───────────────────────────────────────────────────────────────
step "Shutting down"
hcloud server shutdown "$SERVER_NAME"
wait_for_state "$SERVER_NAME" off 90 || {
    log "Clean shutdown timed out — forcing off."
    hcloud server poweroff "$SERVER_NAME"
    wait_for_state "$SERVER_NAME" off 30 || die "Server did not power off"
}

# ── 5. Create snapshot ────────────────────────────────────────────────────────
step "Creating base snapshot"
SNAP_DESC="glucodroid-device-test-base-$(date +%Y%m%d)"
SNAP_ID=$(hcloud server create-image \
    --type snapshot \
    --description "$SNAP_DESC" \
    --label "${SNAPSHOT_LABEL%%=*}=${SNAPSHOT_LABEL##*=}" \
    "$SERVER_NAME" \
    -o json | jq -r '.image.id')
log "Snapshot created: $SNAP_ID ($SNAP_DESC)"

# ── 6. Delete server ──────────────────────────────────────────────────────────
step "Deleting base server"
hcloud server delete "$SERVER_NAME"
SERVER_ID=""

echo ""
log "Setup complete."
log "Snapshot $SNAP_ID is ready — device-test-cycle.sh can now run."
