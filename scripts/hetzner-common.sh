#!/usr/bin/env bash
# hetzner-common.sh — sourced by all Hetzner device-test scripts.
# Do not execute directly.

# Load credentials from scripts/.env.hetzner if it exists.
_COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_ENV_FILE="$_COMMON_DIR/.env.hetzner"
if [[ -f "$_ENV_FILE" ]]; then
    # shellcheck source=/dev/null
    source "$_ENV_FILE"
fi

# ── Required variables ────────────────────────────────────────────────────────
: "${HCLOUD_TOKEN:?HCLOUD_TOKEN not set. Copy scripts/.env.hetzner.example to scripts/.env.hetzner and fill it in.}"
: "${HCLOUD_SSH_KEY:?HCLOUD_SSH_KEY not set.}"
: "${TEST_SSH_KEY:?TEST_SSH_KEY not set.}"
export HCLOUD_TOKEN   # hcloud CLI reads this automatically

# ── Defaults ─────────────────────────────────────────────────────────────────
SERVER_TYPE="${SERVER_TYPE:-cax31}"
SERVER_LOCATION="${SERVER_LOCATION:-nbg1}"
SNAPSHOT_LABEL="${SNAPSHOT_LABEL:-role=glucodroid-device-test}"
RESULTS_BASE="${RESULTS_BASE:-./device-test-results}"

# ── Logging ───────────────────────────────────────────────────────────────────
log()  { printf '[%s] %s\n'        "$(date +%H:%M:%S)" "$*"; }
step() { printf '\n[%s] ── %s ──\n' "$(date +%H:%M:%S)" "$*"; }
die()  { printf '[%s] ERROR: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

# ── Server helpers ────────────────────────────────────────────────────────────

# server_ip NAME
server_ip() {
    hcloud server describe "$1" -o json 2>/dev/null \
        | jq -r '.public_net.ipv4.ip // empty'
}

# ssh_to IP [command...]
ssh_to() {
    local ip="$1"; shift
    ssh -o StrictHostKeyChecking=no \
        -o UserKnownHostsFile=/dev/null \
        -o BatchMode=yes \
        -o ConnectTimeout=15 \
        -o ServerAliveInterval=30 \
        -o ServerAliveCountMax=20 \
        -i "$TEST_SSH_KEY" \
        "root@$ip" "$@"
}

# scp_up IP local_path remote_path
scp_up() {
    scp -q -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i "$TEST_SSH_KEY" "$2" "root@$1:$3"
}

# scp_down IP remote_path local_path
scp_down() {
    scp -q -r -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i "$TEST_SSH_KEY" "root@$1:$2" "$3"
}

# wait_for_ssh IP
wait_for_ssh() {
    local ip="$1"
    log "Waiting for SSH on $ip …"
    local deadline=$(( $(date +%s) + 180 ))
    until ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
              -o BatchMode=yes -o ConnectTimeout=5 -i "$TEST_SSH_KEY" \
              "root@$ip" true 2>/dev/null; do
        (( $(date +%s) < deadline )) \
            || die "SSH on $ip not available after 3 minutes"
        sleep 5
    done
    log "SSH ready."
}

# wait_for_state NAME state timeout_seconds
wait_for_state() {
    local name="$1" want="$2" timeout="$3"
    local deadline=$(( $(date +%s) + timeout ))
    until hcloud server describe "$name" -o json 2>/dev/null \
          | jq -e --arg s "$want" '.status == $s' >/dev/null; do
        (( $(date +%s) < deadline )) || return 1
        sleep 5
    done
}

# latest_snapshot_id  →  prints snapshot ID for SNAPSHOT_LABEL
latest_snapshot_id() {
    hcloud image list --type snapshot --selector "$SNAPSHOT_LABEL" -o json \
        | jq -r 'sort_by(.created) | last | .id // empty'
}

# require_tools tool [tool ...]
require_tools() {
    for cmd in "$@"; do
        command -v "$cmd" >/dev/null || die "'$cmd' not found — install it first"
    done
}
