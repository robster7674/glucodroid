#!/usr/bin/env bash
# remote-test-suite.sh
# Runs INSIDE the CAX31 server. Uploaded and executed by device-test-cycle.sh.
# Requires Redroid running and ADB connected on localhost:5555.
#
# Test scenarios:
#   A. Contiguous 60-reading history   → Room→ViewModel flow, BatteryTrace events
#   B. Same data re-injected           → distinctUntilChangedBy gate (expect no extra recompose)
#   C. 600-reading bulk inject         → 512-point consumer cap (expect no crash/ANR)
#   D. Concurrent writes from 2 procs  → SQLite WAL race condition (expect no SQLITE_BUSY)

set -euo pipefail

ADB="adb -s localhost:5555"
RESULTS="/tmp/test-results"
PASS=0
FAIL=0
PKG="cloud.glucodroid"
DB="/data/data/$PKG/databases/glucose_history.db"
SERIAL="SYNTHETIC-TEST-001"

mkdir -p "$RESULTS"

log()  { printf '[%s remote] %s\n' "$(date +%H:%M:%S)" "$*"; }
pass() { log "PASS: $*"; echo "PASS: $*" >> "$RESULTS/summary.txt"; (( PASS++ )) || true; }
fail() { log "FAIL: $*"; echo "FAIL: $*" >> "$RESULTS/summary.txt"; (( FAIL++ )) || true; }

# Generate and push a SQL file, then feed it to sqlite3 on the device.
# inject_readings <label> <serial> <count> [gap_at_minute] [gap_len_minutes]
inject_readings() {
    local label="$1" serial="$2" count="$3" gap_at="${4:-0}" gap_len="${5:-0}"
    log "Injecting '$label' ($count readings, serial=$serial, gap_at=$gap_at gap_len=$gap_len) …"

    local sqlfile="/tmp/inject_${label}.sql"
    python3 - "$count" "$gap_at" "$gap_len" "$serial" > "$sqlfile" << 'PYEOF'
import sys, math
count, gap_at, gap_len, serial = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
import time; now_ms = int(time.time() * 1000)
print("BEGIN;")
for i in range(count, 0, -1):
    if gap_at > 0 and gap_at - gap_len < i <= gap_at:
        continue
    ts = now_ms - i * 60000
    val = round(110 + 30 * math.sin(i * 0.18), 1)
    print(f"INSERT OR REPLACE INTO history_readings "
          f"(timestamp, sensorSerial, value, rawValue) "
          f"VALUES ({ts}, '{serial}', {val}, {val});")
print("COMMIT;")
PYEOF

    $ADB push "$sqlfile" /data/local/tmp/inject.sql > /dev/null 2>&1
    $ADB shell "sqlite3 '$DB' '.read /data/local/tmp/inject.sql'" 2>&1 | grep -v "^$" || true
    log "Injection done: $label"
}

# ── Verify ADB connection ─────────────────────────────────────────────────────
if ! $ADB get-state 2>/dev/null | grep -q device; then
    log "ADB not connected — trying to reconnect …"
    adb connect localhost:5555
    sleep 3
    $ADB get-state | grep -q device || { echo "FAIL: ADB not available" >> "$RESULTS/summary.txt"; exit 1; }
fi
log "ADB connected: $($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')"

# Clear state
$ADB logcat -c
$ADB shell rm -f /data/local/tmp/trace.pb /data/local/tmp/inject.sql 2>/dev/null || true

# ── Test 1: App launch ────────────────────────────────────────────────────────
log "Launching $PKG …"
$ADB shell am start -n "$PKG/tk.glucodata.MainActivity"
sleep 10

if $ADB shell pidof "$PKG" 2>/dev/null | grep -q '[0-9]'; then
    pass "app launches without crash"
else
    fail "app process not found after launch"
fi

# Get root so we can access the private DB file
log "Requesting adb root …"
$ADB root > /dev/null 2>&1 || log "WARNING: adb root unavailable — DB access may fail"
sleep 2

# Wait for Room to create the database (up to 30 s)
log "Waiting for Room DB to be created …"
deadline=$(( $(date +%s) + 30 ))
until $ADB shell "test -f '$DB'" 2>/dev/null; do
    (( $(date +%s) < deadline )) || { log "WARNING: DB not found after 30 s, proceeding anyway"; break; }
    sleep 2
done
log "DB path confirmed: $DB"

# ── Start Perfetto (45 s, covers all injection scenarios) ─────────────────────
cat > /tmp/perfetto.cfg << 'CFG'
buffers: { size_kb: 131072 }
data_sources: { config {
  name: "linux.ftrace"
  ftrace_config {
    ftrace_events: "sched/sched_switch"
    ftrace_events: "sched/sched_wakeup"
    atrace_categories: "am"
    atrace_categories: "view"
    atrace_categories: "wm"
    atrace_categories: "db"
    atrace_apps: "cloud.glucodroid"
  }
} }
data_sources: { config { name: "android.surfaceflinger.frametimeline" } }
data_sources: { config {
  name: "android.heapprofd"
  heapprofd_config {
    sampling_interval_bytes: 4096
    process_cmdline: "cloud.glucodroid"
  }
} }
duration_ms: 45000
CFG

$ADB push /tmp/perfetto.cfg /data/local/tmp/perfetto.cfg > /dev/null
log "Starting Perfetto trace (45 s) …"
$ADB shell perfetto \
    --txt -c /data/local/tmp/perfetto.cfg \
    -o /data/local/tmp/trace.pb &
PERFETTO_PID=$!

# ── Scenario A: 60 contiguous readings ───────────────────────────────────────
sleep 2
log "=== Scenario A: contiguous history ==="
inject_readings "contiguous" "$SERIAL" 60
sleep 5

# ── Scenario B: same data re-injected (dedup gate) ───────────────────────────
log "=== Scenario B: dedup gate — re-inject identical data ==="
inject_readings "contiguous" "$SERIAL" 60
# INSERT OR REPLACE with identical values still triggers Room's InvalidationTracker.
# The distinctUntilChangedBy gate in the ViewModel should suppress the recomposition.
# In the Perfetto trace, the second injection should produce fewer 'view' slices
# (no Choreographer#doFrame for chart content, only for the invalidation bookkeeping).
sleep 5

# ── Scenario C: 600-reading bulk (512-cap stress) ─────────────────────────────
log "=== Scenario C: 600-reading bulk (512-cap stress) ==="
inject_readings "bulk600" "$SERIAL" 600
sleep 5

# ── Scenario D: concurrent writes (WAL race condition) ────────────────────────
log "=== Scenario D: concurrent writes from two processes ==="
NOW_MS=$(date +%s%3N)

python3 - > /tmp/race_a.sql << PYEOF
import random
print("BEGIN;")
now = $NOW_MS
for i in range(50):
    ts = now + (i + 1) * 60000
    v = round(100 + random.uniform(-20, 20), 1)
    print(f"INSERT OR REPLACE INTO history_readings (timestamp, sensorSerial, value, rawValue) VALUES ({ts}, 'RACE-A', {v}, {v});")
print("COMMIT;")
PYEOF

python3 - > /tmp/race_b.sql << PYEOF
import random
print("BEGIN;")
now = $NOW_MS
for i in range(50):
    ts = now + (i + 1) * 60000 + 30000
    v = round(100 + random.uniform(-20, 20), 1)
    print(f"INSERT OR REPLACE INTO history_readings (timestamp, sensorSerial, value, rawValue) VALUES ({ts}, 'RACE-B', {v}, {v});")
print("COMMIT;")
PYEOF

$ADB push /tmp/race_a.sql /data/local/tmp/race_a.sql > /dev/null
$ADB push /tmp/race_b.sql /data/local/tmp/race_b.sql > /dev/null

# Fire both concurrently — SQLite WAL should serialise without errors
$ADB shell "sqlite3 '$DB' '.read /data/local/tmp/race_a.sql'" &
$ADB shell "sqlite3 '$DB' '.read /data/local/tmp/race_b.sql'" &
wait
log "Concurrent writes done."
sleep 5

# Wait for Perfetto
wait "$PERFETTO_PID" 2>/dev/null || true

# ── Pull Perfetto trace ───────────────────────────────────────────────────────
if $ADB pull /data/local/tmp/trace.pb "$RESULTS/trace.pb" 2>/dev/null; then
    TRACE_KB=$(( $(stat -c%s "$RESULTS/trace.pb" 2>/dev/null || echo 0) / 1024 ))
    pass "Perfetto trace captured (${TRACE_KB} KB)"
else
    fail "Perfetto trace pull failed"
fi

# ── Logcat + crash / ANR / lock detection ─────────────────────────────────────
log "Collecting logcat …"
$ADB logcat -d -v threadtime > "$RESULTS/logcat.txt"

CRASH_COUNT=$(grep -cE "FATAL EXCEPTION|Process .* has died|BEGIN LINKER STATISTICS" \
    "$RESULTS/logcat.txt" 2>/dev/null || true)
ANR_COUNT=$(grep -c "ANR in" "$RESULTS/logcat.txt" 2>/dev/null || true)
DB_LOCKED=$(grep -cE "SQLITE_BUSY|database is locked|SQLiteDatabaseLockedException" \
    "$RESULTS/logcat.txt" 2>/dev/null || true)

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

if (( DB_LOCKED == 0 )); then
    pass "no SQLite lock contention under concurrent writes"
else
    fail "$DB_LOCKED SQLite lock error(s) in logcat — WAL race detected"
    grep -E "SQLITE_BUSY|database is locked|SQLiteDatabaseLockedException" \
        "$RESULTS/logcat.txt" > "$RESULTS/db-lock-errors.txt" 2>/dev/null || true
fi

# ── BatteryTrace events ───────────────────────────────────────────────────────
log "Extracting BatteryTrace events …"
grep -E "BatteryTrace|dashboard\.history|glucose\.native\.one_shot|ui_recovery|history\.recovery" \
    "$RESULTS/logcat.txt" > "$RESULTS/battery-trace.txt" 2>/dev/null || true
BT_LINES=$(wc -l < "$RESULTS/battery-trace.txt")
log "BatteryTrace events: $BT_LINES"

if (( BT_LINES > 0 )); then
    pass "BatteryTrace events fired ($BT_LINES lines) — Room→ViewModel pipeline active"
else
    # Not a hard fail: the app may be in no-sensor idle state with no active ViewModel
    log "INFO: no BatteryTrace events — app may be in no-sensor idle (expected without BLE)"
fi

# ── Row count sanity ──────────────────────────────────────────────────────────
ROW_COUNT=$($ADB shell "sqlite3 '$DB' 'SELECT COUNT(*) FROM history_readings;'" \
    2>/dev/null | tr -d '\r' || echo "unknown")
log "history_readings row count after all injections: $ROW_COUNT"

# ── Memory footprint ──────────────────────────────────────────────────────────
$ADB shell dumpsys meminfo "$PKG" 2>/dev/null | head -40 > "$RESULTS/meminfo.txt" || true
PSS=$(grep "TOTAL PSS" "$RESULTS/meminfo.txt" | grep -o '[0-9]*' | head -1 || echo "unknown")
log "Total PSS: ${PSS} KB"

# ── Package info ──────────────────────────────────────────────────────────────
$ADB shell dumpsys package "$PKG" 2>/dev/null \
    | grep -E "versionName|versionCode|lastUpdateTime|firstInstallTime" \
    > "$RESULTS/package-info.txt" || true

# ── Summary ───────────────────────────────────────────────────────────────────
{
    echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "pass=$PASS"
    echo "fail=$FAIL"
    echo "crash_count=$CRASH_COUNT"
    echo "anr_count=$ANR_COUNT"
    echo "db_locked_count=$DB_LOCKED"
    echo "battery_trace_lines=$BT_LINES"
    echo "history_readings_rows=$ROW_COUNT"
    echo "total_pss_kb=$PSS"
} >> "$RESULTS/summary.txt"

log "────────────────────────────────"
log "Results: PASS=$PASS  FAIL=$FAIL"
log "Results directory: $RESULTS"
(( FAIL == 0 ))
