#!/usr/bin/env bash
# hetzner-setup.sh — One-time provisioning of the base Redroid snapshot.
#
# Creates a CAX31, installs Docker and the Redroid Android 12 image, verifies
# ADB connectivity, snapshots the result, and deletes the server.
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
    --image ubuntu-22.04 \
    --ssh-key "$HCLOUD_SSH_KEY" \
    --location "$SERVER_LOCATION" \
    -o json | jq -r '.server.id')
SERVER_IP=$(server_ip "$SERVER_NAME")
log "Created $SERVER_NAME (id=$SERVER_ID, ip=$SERVER_IP)"

# ── 2. Wait for boot ──────────────────────────────────────────────────────────
step "Waiting for boot"
wait_for_ssh "$SERVER_IP"

# ── 3. Install Docker + Redroid ───────────────────────────────────────────────
step "Installing Docker and pulling Redroid image (~1 GB, takes 5-10 min)"
ssh_to "$SERVER_IP" bash << 'PROVISION'
set -euo pipefail
log() { printf '[%s remote] %s\n' "$(date +%H:%M:%S)" "$*"; }

export DEBIAN_FRONTEND=noninteractive

log "Updating packages …"
apt-get update -q
apt-get install -yq curl python3 adb jq

log "Installing Docker …"
apt-get install -yq docker.io
systemctl enable --now docker

log "Installing binder kernel module support …"
# Hetzner CAX kernel ships with CONFIG_ANDROID_BINDER_DEVICES="" — binderfs is
# required; binder devices are not auto-created on modprobe.
apt-get install -yq "linux-modules-extra-$(uname -r)" 2>/dev/null \
    || log "WARNING: linux-modules-extra not found for $(uname -r) — binder_linux may already be built-in"

log "Verifying binder_linux module loads …"
modprobe binder_linux 2>/dev/null || true
mkdir -p /dev/binderfs
mount -t binder binder /dev/binderfs
python3 -c "
import fcntl, struct, os
BINDER_CTL_ADD = 0xC1086201
fd = os.open('/dev/binderfs/binder-control', os.O_RDONLY)
for name in ['binder', 'hwbinder', 'vndbinder']:
    buf = struct.pack('256sII', name.encode(), 0, 0)
    fcntl.ioctl(fd, BINDER_CTL_ADD, buf)
    print('Created /dev/binderfs/' + name)
os.close(fd)
"
ls -la /dev/binderfs/binder /dev/binderfs/hwbinder /dev/binderfs/vndbinder

log "Pulling Redroid Android 12 image …"
docker pull redroid/redroid:12.0.0_64only-latest

log "Running Redroid smoke test …"
docker run -d \
    --name redroid-verify \
    --privileged \
    -v /dev/binderfs:/dev/binderfs \
    -p 5555:5555 \
    redroid/redroid:12.0.0_64only-latest \
    androidboot.redroid_gpu_mode=guest

log "Waiting for Android boot (up to 5 min) …"
deadline=$(( $(date +%s) + 300 ))
while true; do
    (( $(date +%s) < deadline )) || { log "ERROR: Redroid did not boot in 5 min"; exit 1; }
    adb connect localhost:5555 >/dev/null 2>&1 || true
    if adb -s localhost:5555 shell getprop sys.boot_completed 2>/dev/null \
            | tr -d '\r\n' | grep -q '^1$'; then
        break
    fi
    printf '  %s still waiting…\n' "$(date +%H:%M:%S)"
    sleep 5
done

log "ADB verified: $(adb -s localhost:5555 shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
adb disconnect localhost:5555 2>/dev/null || true
docker stop redroid-verify
docker rm redroid-verify
log "Redroid install verified — ready for snapshot."
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
    "$SERVER_NAME" 2>&1 | grep -oE 'Image [0-9]+' | grep -oE '[0-9]+')
log "Snapshot created: $SNAP_ID ($SNAP_DESC)"

# ── 6. Delete server ──────────────────────────────────────────────────────────
step "Deleting base server"
hcloud server delete "$SERVER_NAME"
SERVER_ID=""

echo ""
log "Setup complete."
log "Snapshot $SNAP_ID is ready — device-test-cycle.sh can now run."
