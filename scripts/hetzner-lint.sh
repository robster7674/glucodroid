#!/usr/bin/env bash
# hetzner-lint.sh — Spin up a CX11, run Android lint + static analysis,
# download XML reports, file GitHub issues, tear down the server.
#
# Usage:
#   ./scripts/hetzner-lint.sh
#
# Credentials: scripts/.env.hetzner must have HCLOUD_TOKEN, HCLOUD_SSH_KEY,
# TEST_SSH_KEY set.  GitHub: gh CLI must be authenticated.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/hetzner-common.sh"

GITHUB_REPO="robster7674/glucodroid"
SERVER_NAME="glucodroid-lint-$(date +%Y%m%d-%H%M%S)"
SERVER_ID=""
SERVER_IP=""
RESULTS_DIR="$RESULTS_BASE/lint-$(date +%Y%m%d-%H%M%S)"
SRC_TARBALL="/tmp/glucodroid-lint-src.tar.gz"

on_error() {
    log "hetzner-lint.sh failed at line $1."
    if [[ -n "$SERVER_ID" ]]; then
        log "Server '$SERVER_NAME' left running for investigation."
        log "  SSH:    ssh -i $TEST_SSH_KEY root@${SERVER_IP:-<check hcloud>}"
        log "  Delete: hcloud server delete $SERVER_NAME"
    fi
}
trap 'on_error $LINENO' ERR

require_tools hcloud jq gh ssh scp tar
[[ -f "$TEST_SSH_KEY" ]] || die "SSH key not found: $TEST_SSH_KEY"
gh auth status >/dev/null 2>&1 || die "gh CLI not authenticated — run: gh auth login"

mkdir -p "$RESULTS_DIR"
log "Results directory: $RESULTS_DIR"

# ── 1. Bundle source ──────────────────────────────────────────────────────────
step "Creating source tarball"
cd "$REPO_ROOT"
git archive HEAD | gzip > "$SRC_TARBALL"
log "Source tarball: $SRC_TARBALL ($(du -sh "$SRC_TARBALL" | cut -f1))"

# ── 2. Create server ─────────────────────────────────────────────────────────
LINT_SERVER_TYPE="${LINT_SERVER_TYPE:-cpx32}"
step "Creating $LINT_SERVER_TYPE server (x86, Ubuntu 24.04, nbg1)"
SERVER_ID=$(hcloud server create \
    --name "$SERVER_NAME" \
    --type "$LINT_SERVER_TYPE" \
    --image ubuntu-24.04 \
    --ssh-key "$HCLOUD_SSH_KEY" \
    --location nbg1 \
    -o json | jq -r '.server.id')
SERVER_IP=$(server_ip "$SERVER_NAME")
log "Created $SERVER_NAME (id=$SERVER_ID, ip=$SERVER_IP)"

# ── 3. Wait for SSH ───────────────────────────────────────────────────────────
step "Waiting for SSH"
wait_for_ssh "$SERVER_IP"
ssh-keygen -f "$HOME/.ssh/known_hosts" -R "$SERVER_IP" 2>/dev/null || true

# ── 4. Upload files ───────────────────────────────────────────────────────────
step "Uploading source and remote script"
scp_up "$SERVER_IP" "$SRC_TARBALL"                               /tmp/glucodroid-src.tar.gz
scp_up "$SERVER_IP" "$SCRIPT_DIR/remote-lint-suite.sh"          /tmp/remote-lint-suite.sh
ssh_to "$SERVER_IP" chmod +x /tmp/remote-lint-suite.sh
log "Files uploaded."

# ── 5. Run remote lint suite ──────────────────────────────────────────────────
step "Running remote lint suite (takes 15–25 min on CX11)"
SUITE_EXIT=0
ssh_to "$SERVER_IP" /tmp/remote-lint-suite.sh 2>&1 | tee "$RESULTS_DIR/remote.log" \
    || SUITE_EXIT=$?
log "Remote script exit: $SUITE_EXIT"

# ── 6. Collect results ────────────────────────────────────────────────────────
step "Downloading results"
scp_down "$SERVER_IP" /tmp/lint-results/. "$RESULTS_DIR/" || true
log "Results saved to $RESULTS_DIR"

# ── 7. Delete server ──────────────────────────────────────────────────────────
step "Deleting server"
hcloud server delete "$SERVER_ID" 2>/dev/null \
    || hcloud server delete "$SERVER_NAME" 2>/dev/null \
    || log "WARNING: server may already be gone (OOM crash). Check: hcloud server list"
SERVER_ID=""
log "Server deleted."

# ── 8. Print summary ──────────────────────────────────────────────────────────
echo ""
if [[ -f "$RESULTS_DIR/summary.txt" ]]; then
    cat "$RESULTS_DIR/summary.txt"
fi
echo ""
ls -lh "$RESULTS_DIR/"

# ── 9. Parse lint XML and file GitHub issues ──────────────────────────────────
step "Filing GitHub issues from lint results"
gh label create lint --repo "$GITHUB_REPO" --color "#e4e669" --description "Android lint finding" 2>/dev/null || true
XML_FILE=$(ls "$RESULTS_DIR"/lint-results*.xml 2>/dev/null | head -1 || true)

if [[ -z "$XML_FILE" ]]; then
    log "No lint XML found in $RESULTS_DIR — skipping issue creation."
    log "Check $RESULTS_DIR/lint-stdout.txt for what went wrong."
    exit "$SUITE_EXIT"
fi

log "Parsing $XML_FILE"
REPORT_DATE="$(date -u +%Y-%m-%d)"

# Write Python to a temp file (single-quoted heredoc = no bash expansion inside).
# All bash values are passed via environment variables.
cat > /tmp/file-lint-issues.py << 'PYEOF'
import xml.etree.ElementTree as ET
import subprocess, sys, textwrap, os
from collections import defaultdict

xml_path    = os.environ["LINT_XML"]
repo        = os.environ["GITHUB_REPO"]
report_date = os.environ["REPORT_DATE"]
gradle_cmd  = "./gradlew :Common:lintMobileLibre3SiDexGoogleReleaser"

try:
    tree = ET.parse(xml_path)
except ET.ParseError as e:
    print(f"[parse error] {e}")
    sys.exit(0)

root   = tree.getroot()
issues = root.findall("issue")

by_cat = defaultdict(list)
for iss in issues:
    sev = iss.get("severity", "?")
    cat = iss.get("category",  "?")
    by_cat[(sev, cat)].append(iss)

errors   = [(k, v) for k, v in by_cat.items() if k[0] == "Error"]
warnings = [(k, v) for k, v in by_cat.items() if k[0] == "Warning"]

total_e = sum(len(v) for _, v in errors)
total_w = sum(len(v) for _, v in warnings)
print(f"Lint results: {total_e} error(s), {total_w} warning(s) across {len(by_cat)} categories")

created = []

def gh_issue(title, body):
    res = subprocess.run(
        ["gh", "issue", "create",
         "--repo", repo, "--title", title,
         "--label", "lint", "--body", body],
        capture_output=True, text=True
    )
    if res.returncode == 0:
        url = res.stdout.strip()
        print(f"  created: {url}")
        created.append(url)
    else:
        print(f"  gh issue create failed: {res.stderr.strip()}")

def fmt_issues(iss_list, max_items=20):
    lines = []
    for iss in iss_list[:max_items]:
        msg   = iss.get("message", "")[:200]
        loc   = iss.find("location")
        fname = os.path.basename(loc.get("file", "?")) if loc is not None else "?"
        lnum  = loc.get("line", "?") if loc is not None else "?"
        lines.append(f"- **{fname}:{lnum}** — {msg}")
    if len(iss_list) > max_items:
        lines.append(f"- … and {len(iss_list) - max_items} more (see full XML report)")
    return "\n".join(lines)

# One issue per error category
for (sev, cat), iss_list in sorted(errors, key=lambda x: -len(x[1])):
    summary = iss_list[0].get("summary", cat)
    title = f"[Lint Error] {cat}: {summary} ({len(iss_list)} instance(s))"
    body = (
        f"## Android Lint — {sev}: {cat}\n\n"
        f"**Summary:** {summary}\n"
        f"**Count:** {len(iss_list)}\n"
        f"**Reported by:** `{gradle_cmd}`\n"
        f"**Report date:** {report_date}\n\n"
        f"### Locations\n\n"
        f"{fmt_issues(iss_list)}\n\n"
        f"---\nFull report: `{os.path.basename(xml_path)}`"
    )
    gh_issue(title, body)

# One omnibus issue for warnings (top 10 categories)
if warnings:
    parts = [
        "## Android Lint — Warnings Summary",
        "",
        f"**Total warnings:** {total_w} across {len(warnings)} category(ies)",
        f"**Reported by:** `{gradle_cmd}`",
        f"**Report date:** {report_date}",
        "",
    ]
    for (sev, cat), iss_list in sorted(warnings, key=lambda x: -len(x[1]))[:10]:
        summary = iss_list[0].get("summary", cat)
        parts += [f"### {cat} — {len(iss_list)} instance(s)", f"*{summary}*",
                  fmt_issues(iss_list, max_items=5), ""]
    gh_issue(f"[Lint] {total_w} warning(s) across {len(warnings)} category(ies)",
             "\n".join(parts))

if not by_cat:
    gh_issue("[Lint] Clean — no issues found",
             f"Android lint reported zero issues on `{gradle_cmd}`.\n\nReport date: {report_date}")

print(f"\nFiled {len(created)} GitHub issue(s) on {repo}")
PYEOF

LINT_XML="$XML_FILE" GITHUB_REPO="$GITHUB_REPO" REPORT_DATE="$REPORT_DATE" python3 /tmp/file-lint-issues.py

log "Done. Results: $RESULTS_DIR"
