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

### Waydroid on Hetzner CAX (ARM64)

Waydroid runs Android in a Linux container using the host kernel's binder — no nested virtualisation. ARM64 host + ARM64 Android image means no translation overhead.

**Setup (Debian/Ubuntu on CAX):**

```bash
sudo apt install curl python3
curl -s https://repo.waydro.id | sudo bash
sudo apt install waydroid
sudo waydroid init
```

Verify binder is available before ordering:

```bash
ls /dev/binder 2>/dev/null || echo "binder missing"
```

Once running, ADB connects normally over the network:

```bash
adb connect <CAX-IP>:5555
adb devices
```

Perfetto capture then works exactly as on a physical device (see FINDINGS.md for the capture config).

### Capturing a Perfetto trace

```bash
adb shell perfetto \
  -c - --txt \
  -o /data/misc/perfetto-traces/trace.pb \
<<EOF
buffers: { size_kb: 65536 }
data_sources: { config { name: "linux.ftrace"
  ftrace_config { ftrace_events: "sched/sched_switch"
                  atrace_categories: "view"
                  atrace_categories: "am"
                  atrace_apps: "cloud.glucodroid" } } }
duration_ms: 10000
EOF
adb pull /data/misc/perfetto-traces/trace.pb
```

Open at `ui.perfetto.dev`.
