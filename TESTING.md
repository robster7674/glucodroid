# GlucoDroid — Testing

## JVM unit tests (runs on this machine, no device needed)

These tests target pure Kotlin logic in the history pipeline — no Android runtime, no emulator, no JNI.

### Run

```bash
./gradlew :Common:testMobileLibre3SiDexGoogleReleaserUnitTest \
  --tests "tk.glucodata.ui.viewmodel.*" \
  --tests "tk.glucodata.ui.DashboardChartPipelineTests"
```

To run the full suite (includes two pre-existing unrelated failures — see below):

```bash
./gradlew :Common:testMobileLibre3SiDexGoogleReleaserUnitTest --no-daemon
```

Add `--no-daemon` when running on a memory-constrained VPS to avoid a persistent Gradle daemon accumulating heap.

### What is covered

#### `HistoryRecoveryPolicyTests` (10 cases)
`Common/src/test/java/tk/glucodata/ui/viewmodel/HistoryRecoveryPolicyTests.kt`

Tests `DashboardHistoryCollectionPolicy.shouldRequestHistoryRecovery()` — the predicate that decides whether to fire a native history sync when the Room-backed history appears incomplete.

Key cases:
- Empty history always triggers recovery
- Oldest point starts after `startTimeMs + 5 min tolerance` → triggers (guards against a Room query that returned a truncated window)
- `startTimeMs = 0` disables the start-time check (used when querying from the beginning of time)
- No current reading (`currentTimeMs = 0`) → no recovery (avoids spurious syncs on cold start before BLE connects)
- Blank serial → no recovery
- Sensor serial mismatch → no recovery (prevents cross-sensor false positives)
- Current reading timestamp exceeds `lastHistoryTimestamp + 2 min tail tolerance` → triggers (the main ongoing-session gap detector)
- Current reading within tail tolerance → no recovery

#### `HistoryEdgeSignatureTests` (9 cases)
`Common/src/test/java/tk/glucodata/ui/viewmodel/HistoryEdgeSignatureTests.kt`

Tests `historyEdgeSignature()` and `sparseHistorySampleHash()` — the structural fingerprint used with `distinctUntilChangedBy` to gate Compose recompositions. Room emits a new list on every DB write; without this gate every incoming reading triggers a full chart redraw.

Key cases:
- Identical lists → same signature (gate suppresses re-render correctly)
- Appending a point → different signature (new reading reaches the UI)
- Changing last calibrated value, raw value, or sensor serial → different signature
- Changing a mid-list point in a 20-point list → different sparse hash (interior samples are covered, not just endpoints)
- Empty list → zero hash, null serial
- Single point → signature reflects that point's fields exactly

#### `DashboardChartPipelineTests` (8 cases)
`Common/src/test/java/tk/glucodata/ui/DashboardChartPipelineTests.kt`

Tests `buildSmoothedConsumerHistory()` (512-point consumer cap) and `trimHistoryForPrediction()` (1-hour prediction window).

Key cases:
- 600-point list with smoothing disabled → capped at 512, correct tail retained
- 700-point list with `smoothOnlyGraph = true` → capped at 512 even when smoothing minutes > 0
- List ≤ 512 points → passed through unchanged
- List at exactly 512 → no truncation
- Empty input → empty output
- Prediction trim: 200-point list → result ≤ 96 (`PREDICTION_HISTORY_MAX_POINTS`)
- Prediction trim: 10-point list → passed through unchanged
- Prediction trim: result window ≤ 1 hour of timestamps

#### `DashboardHistoryCollectionPolicyTests` (2 cases, pre-existing)
`Common/src/test/java/tk/glucodata/ui/viewmodel/DashboardHistoryCollectionPolicyTests.kt`

Mode routing: `DASHBOARD` uses per-sensor history, `FULL_HISTORY` uses merged cross-sensor. Coalescing skips only the first emission on the dashboard path.

### Pre-existing test failures (not introduced here)

Two tests in the full suite fail on the current branch and are unrelated to the history pipeline:

- `ManagedSensorStatusPolicyTests.resolveLifecycleSummary_prefersDriverRemainingHoursAndExpectedEnd`
- `DefaultParamCatalogCompareTests.testImportedCatalogOverlaysSnapshotEntry`

Exclude them when running the full suite to see a clean result:

```bash
./gradlew :Common:testMobileLibre3SiDexGoogleReleaserUnitTest --no-daemon \
  --tests "tk.glucodata.ui.viewmodel.*" \
  --tests "tk.glucodata.ui.*" \
  --tests "tk.glucodata.CurrentDisplaySourceTests" \
  --tests "tk.glucodata.DataSmoothingTests" \
  --tests "tk.glucodata.SensorIdentityTests" \
  --tests "tk.glucodata.LiveContinuityPolicyTests"
```

### Dependencies added

In `Common/build.gradle`:

```gradle
testImplementation "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2"
testImplementation 'app.cash.turbine:turbine:1.2.0'
```

`kotlinx-coroutines-test` and Turbine are wired up for future Flow-level tests (e.g. testing that `UiRefreshBus` delivers `StatusOnly` without triggering a full history reload, or that `collectLatest` in `startHistoryCollectionForMode` correctly cancels an in-flight coalesce delay when a new emission arrives). These cannot run without a device because the ViewModel and bus have Android side-effects; they would need either Robolectric or the CAX Waydroid setup below.

---

## Device / emulator tests (requires CAX ARM instance or physical device)

The following cannot be tested on a plain JVM:

| Concern | Why device needed | Suggested tool |
|---------|-------------------|----------------|
| Compose recomposition counts | Compose runtime only runs on Android | Composition tracing + Perfetto |
| `collectLatest` coalesce cancel race | Needs real coroutine scheduler under Android's Looper | Espresso / Compose test |
| BLE reconnect recovery storm | Needs real BLE stack | Manual + BatteryTrace log |
| Jank / frame drops | Frame metrics API is Android-only | JankStats |
| `UiRefreshBus` routing end-to-end | `GlucoseUpdateBroadcaster` and `Floating` are Android | Espresso |

### Automated device test cycle (phoenix VM)

`scripts/device-test-cycle.sh` recreates a CAX21 from a stored snapshot, runs the test suite, collects results, snapshots the new state, and destroys the server. The VM exists for ~15–20 minutes per run; cost is a few cents.

```
scripts/device-test-cycle.sh [path/to/glucodroid.apk]
```

**Required environment:**

| Variable | Description |
|----------|-------------|
| `HCLOUD_TOKEN` | Hetzner Cloud API token |
| `HCLOUD_SSH_KEY` | Name of the SSH public key registered in Hetzner Cloud |
| `TEST_SSH_KEY` | Path to the matching private key file on this machine |

**Optional:**

| Variable | Default |
|----------|---------|
| `SERVER_TYPE` | `cax21` |
| `SERVER_LOCATION` | `nbg1` |
| `SNAPSHOT_LABEL` | `role=glucodroid-device-test` |
| `RESULTS_BASE` | `./device-test-results` |

**On failure:** the server is left running so you can SSH in to investigate. Delete it manually with `hcloud server delete <name>` when done. The script prints the exact SSH command.

**What the script collects:**

- `trace.pb` — 15-second Perfetto system trace (open at `ui.perfetto.dev`)
- `logcat.txt` — full logcat since app launch
- `crashes.txt` — extracted crash stacks (if any FATAL EXCEPTION found)
- `battery-trace.txt` — BatteryTrace emission counts for history/recovery flows
- `package-info.txt` — installed version and timestamps

---

### First-time snapshot setup (one-off, done manually)

Before the script can run you need a base snapshot. Spin up a CAX21 manually, set up Waydroid, then snapshot it:

```bash
# 1. Create the server (replace YOUR_SSH_KEY with your Hetzner key name)
hcloud server create --name glucodroid-base \
    --type cax21 --image ubuntu-24.04 \
    --ssh-key YOUR_SSH_KEY --location nbg1

# 2. SSH in
ssh root@$(hcloud server describe glucodroid-base -o json | jq -r '.public_net.ipv4.ip')

# 3. Inside the server: install Waydroid
apt update && apt install -y curl python3 adb
curl -s https://repo.waydro.id | bash
apt install -y waydroid
waydroid init          # downloads the Android image (~800 MB)

# Verify binder is available
ls /dev/binder* || modprobe binder_linux devices="binder,hwbinder,vndbinder"

# Start Waydroid and confirm ADB connects
waydroid session start &
sleep 30
adb connect localhost:5555
adb devices            # should show the device

# 4. Back on your machine: power off and snapshot
hcloud server shutdown glucodroid-base
hcloud server create-image glucodroid-base \
    --type snapshot \
    --description "glucodroid-device-test-base" \
    --label "role=glucodroid-device-test"

# 5. Delete the base server (snapshot persists)
hcloud server delete glucodroid-base
```

The snapshot costs ~€0.01/GB/month (≈ €0.10/month for a ~10 GB ARM image). The cycle script rotates it automatically — only one snapshot is kept at a time.

---

### Waydroid on Hetzner CAX (ARM64) — manual use

For interactive investigation without the full cycle script:

```bash
# Create a server from the latest snapshot
SNAP_ID=$(hcloud image list --type snapshot \
    --selector "role=glucodroid-device-test" \
    -o json | jq -r 'sort_by(.created) | last | .id')

hcloud server create --name glucodroid-manual \
    --type cax21 --image "$SNAP_ID" \
    --ssh-key YOUR_SSH_KEY --location nbg1

# Connect
ssh root@$(hcloud server describe glucodroid-manual -o json | jq -r '.public_net.ipv4.ip')

# Inside: start Waydroid, connect ADB
waydroid session start &
sleep 30
adb connect localhost:5555

# Install and run
adb install /path/to/glucodroid.apk
adb shell am start -n cloud.glucodroid/.MainActivity
```

### Capturing a Perfetto trace (manual)

```bash
adb shell perfetto \
  --txt -c - \
  -o /data/local/tmp/trace.pb \
<<EOF
buffers: { size_kb: 32768 }
data_sources: { config { name: "linux.ftrace"
  ftrace_config { ftrace_events: "sched/sched_switch"
                  atrace_categories: "view"
                  atrace_categories: "am"
                  atrace_apps: "cloud.glucodroid" } } }
data_sources: { config { name: "android.surfaceflinger.frametimeline" } }
duration_ms: 15000
EOF
adb pull /data/local/tmp/trace.pb ./trace.pb
```

Open `trace.pb` at `ui.perfetto.dev`.
