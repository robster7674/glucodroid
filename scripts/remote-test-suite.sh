#!/usr/bin/env bash
# remote-test-suite.sh
# Runs INSIDE the CAX31 server. Uploaded and executed by device-test-cycle.sh.
# Requires Waydroid running and ADB connected on localhost:5555.

set -euo pipefail

ADB="adb -s localhost:5555"
RESULTS="/tmp/test-results"
PASS=0
FAIL=0

mkdir -p "$RESULTS"

log()  { printf '[%s remote] %s\n' "$(date +%H:%M:%S)" "$*"; }
pass() { log "PASS: $*"; echo "PASS: $*" >> "$RESULTS/summary.txt"; (( PASS++ )) || true; }
fail() { log "FAIL: $*"; echo "FAIL: $*" >> "$RESULTS/summary.txt"; (( FAIL++ )) || true; }

# ── Verify ADB connection ────────────────────────────────────────────────────
if ! $ADB get-state 2>/dev/null | grep -q device; then
    log "ADB not connected — trying to reconnect …"
    adb connect localhost:5555
    sleep 3
    $ADB get-state | grep -q device || { echo "FAIL: ADB not available" >> "$RESULTS/summary.txt"; exit 1; }
fi
log "ADB connected: $($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')"

# ── Clear state ──────────────────────────────────────────────────────────────
$ADB logcat -c
$ADB shell rm -f /data/local/tmp/trace.pb /data/local/tmp/perfetto.cfg 2>/dev/null || true

# ── Test 1: App launch ───────────────────────────────────────────────────────
log "Launching cloud.glucodroid …"
$ADB shell am start -n cloud.glucodroid/.MainActivity
sleep 10

if $ADB shell pidof cloud.glucodroid 2>/dev/null | grep -q '[0-9]'; then
    pass "app launches without crash"
else
    fail "app process not found after launch"
fi

# ── Perfetto trace (20 s, covering launch + idle) ────────────────────────────
log "Writing Perfetto config …"
cat > /tmp/perfetto.cfg << 'CFG'
buffers: { size_kb: 65536 }
data_sources: { config {
  name: "linux.ftrace"
  ftrace_config {
    ftrace_events: "sched/sched_switch"
    ftrace_events: "sched/sched_wakeup"
    ftrace_events: "sched/sched_waking"
    atrace_categories: "am"
    atrace_categories: "view"
    atrace_categories: "wm"
    atrace_categories: "db"
    atrace_apps: "cloud.glucodroid"
  }
} }
data_sources: { config { name: "android.surfaceflinger.frametimeline" } }
data_sources: { config { name: "android.heapprofd"
  heapprofd_config { sampling_interval_bytes: 4096
                     process_cmdline: "cloud.glucodroid" } } }
duration_ms: 20000
CFG

$ADB push /tmp/perfetto.cfg /data/local/tmp/perfetto.cfg
log "Starting Perfetto trace (20 s) …"
$ADB shell perfetto \
    --txt -c /data/local/tmp/perfetto.cfg \
    -o /data/local/tmp/trace.pb &
PERFETTO_PID=$!

# ── Navigate while tracing ───────────────────────────────────────────────────
# Get screen dimensions for relative taps
SCREEN_SIZE=$($ADB shell wm size 2>/dev/null | grep -o '[0-9]*x[0-9]*' | head -1)
W=$(echo "$SCREEN_SIZE" | cut -dx -f1)
H=$(echo "$SCREEN_SIZE" | cut -dx -f2)
W=${W:-1080}; H=${H:-1920}
CX=$(( W / 2 )); CY=$(( H / 2 ))
BOTTOM=$(( H * 9 / 10 ))

log "Screen: ${W}x${H}. Navigating …"

sleep 4
# Swipe up to simulate scroll / trigger chart refresh
$ADB shell input swipe "$CX" "$BOTTOM" "$CX" "$(( H / 3 ))" 300
sleep 3
# Press back and relaunch to simulate app resume
$ADB shell input keyevent KEYCODE_BACK
sleep 2
$ADB shell am start -n cloud.glucodroid/.MainActivity
sleep 4

# Wait for Perfetto to finish
wait "$PERFETTO_PID" 2>/dev/null || true

# Pull trace
if $ADB pull /data/local/tmp/trace.pb "$RESULTS/trace.pb" 2>/dev/null; then
    TRACE_KB=$(( $(stat -c%s "$RESULTS/trace.pb" 2>/dev/null || echo 0) / 1024 ))
    pass "Perfetto trace captured (${TRACE_KB} KB)"
else
    fail "Perfetto trace pull failed"
fi

# ── Logcat + crash detection ─────────────────────────────────────────────────
log "Collecting logcat …"
$ADB logcat -d -v threadtime > "$RESULTS/logcat.txt"

CRASH_COUNT=$(grep -cE "FATAL EXCEPTION|Process .* has died|BEGIN LINKER STATISTICS" \
    "$RESULTS/logcat.txt" 2>/dev/null || true)
ANR_COUNT=$(grep -c "ANR in" "$RESULTS/logcat.txt" 2>/dev/null || true)

if (( CRASH_COUNT == 0 )); then
    pass "no crashes in logcat"
else
    fail "$CRASH_COUNT crash signal(s) in logcat"
    grep -B2 -A30 "FATAL EXCEPTION\|Process .* has died" \
        "$RESULTS/logcat.txt" > "$RESULTS/crashes.txt" 2>/dev/null || true
fi

if (( ANR_COUNT == 0 )); then
    pass "no ANRs in logcat"
else
    fail "$ANR_COUNT ANR(s) in logcat"
    grep -B2 -A10 "ANR in" "$RESULTS/logcat.txt" > "$RESULTS/anrs.txt" 2>/dev/null || true
fi

# ── BatteryTrace emission counts (our own instrumentation) ───────────────────
log "Extracting BatteryTrace events …"
grep -E "BatteryTrace|dashboard\.history|glucose\.native\.one_shot|ui_recovery|history\.recovery" \
    "$RESULTS/logcat.txt" > "$RESULTS/battery-trace.txt" 2>/dev/null || true
BT_LINES=$(wc -l < "$RESULTS/battery-trace.txt")
log "BatteryTrace events: $BT_LINES"

# ── Memory footprint ─────────────────────────────────────────────────────────
$ADB shell dumpsys meminfo cloud.glucodroid 2>/dev/null \
    | head -40 > "$RESULTS/meminfo.txt" || true
PSS=$(grep "TOTAL PSS" "$RESULTS/meminfo.txt" | grep -o '[0-9]*' | head -1 || echo "unknown")
log "Total PSS: ${PSS} KB"

# ── Package info ─────────────────────────────────────────────────────────────
$ADB shell dumpsys package cloud.glucodroid 2>/dev/null \
    | grep -E "versionName|versionCode|lastUpdateTime|firstInstallTime" \
    > "$RESULTS/package-info.txt" || true

# ── Summary ──────────────────────────────────────────────────────────────────
{
    echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "pass=$PASS"
    echo "fail=$FAIL"
    echo "crash_count=$CRASH_COUNT"
    echo "anr_count=$ANR_COUNT"
    echo "battery_trace_lines=$BT_LINES"
    echo "total_pss_kb=$PSS"
} >> "$RESULTS/summary.txt"

log "────────────────────────────────"
log "Results: PASS=$PASS  FAIL=$FAIL"
log "Results directory: $RESULTS"
(( FAIL == 0 ))   # exit 0 if all passed, 1 otherwise
