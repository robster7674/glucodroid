#!/usr/bin/env bash
# hetzner-cleanup.sh — List and optionally delete orphaned glucodroid test servers.
# Safe to run at any time. Shows what exists before asking to delete.
#
# Usage:
#   ./scripts/hetzner-cleanup.sh [--force]   # --force skips confirmation

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/hetzner-common.sh"

FORCE="${1:-}"
require_tools hcloud jq

echo ""
echo "═══ Glucodroid test servers ═══════════════════════════════"
SERVERS=$(hcloud server list -o json \
    | jq -r '.[] | select(.name | startswith("glucodroid-")) | [.id, .name, .status, .public_net.ipv4.ip] | @tsv')

if [[ -z "$SERVERS" ]]; then
    echo "  (none)"
else
    echo "$SERVERS" | column -t -N "ID,NAME,STATUS,IP"
fi

echo ""
echo "═══ Glucodroid snapshots ══════════════════════════════════"
SNAPS=$(hcloud image list --type snapshot --selector "$SNAPSHOT_LABEL" -o json \
    | jq -r '.[] | [.id, .description, (.disk_size | tostring) + " GB", .created] | @tsv')

if [[ -z "$SNAPS" ]]; then
    echo "  (none)"
else
    echo "$SNAPS" | column -t -N "ID,DESCRIPTION,SIZE,CREATED"
fi
echo ""

# Count items
SERVER_COUNT=$(echo "$SERVERS" | grep -c . 2>/dev/null || true)
SNAP_COUNT=$(echo "$SNAPS" | grep -c . 2>/dev/null || true)

if (( SERVER_COUNT == 0 && SNAP_COUNT == 0 )); then
    log "Nothing to clean up."
    exit 0
fi

if [[ "$FORCE" != "--force" ]]; then
    read -rp "Delete all of the above? [y/N] " ans
    [[ "$ans" =~ ^[Yy]$ ]] || { log "Aborted."; exit 0; }
fi

# Delete servers
if [[ -n "$SERVERS" ]]; then
    echo "$SERVERS" | awk '{print $2}' | while read -r name; do
        log "Deleting server: $name"
        hcloud server delete "$name"
    done
fi

# Delete snapshots — keep the latest one unless --force
if [[ -n "$SNAPS" ]]; then
    if [[ "$FORCE" == "--force" ]]; then
        echo "$SNAPS" | awk '{print $1}' | while read -r id; do
            log "Deleting snapshot: $id"
            hcloud image delete "$id"
        done
    else
        LATEST=$(latest_snapshot_id)
        echo "$SNAPS" | awk '{print $1}' | while read -r id; do
            if [[ "$id" == "$LATEST" ]]; then
                log "Keeping latest snapshot: $id"
            else
                log "Deleting old snapshot: $id"
                hcloud image delete "$id"
            fi
        done
    fi
fi

echo ""
log "Cleanup complete."
