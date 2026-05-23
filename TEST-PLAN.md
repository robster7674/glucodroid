# GlucoDroid Device Test Plan

## Goals

Catch stability regressions — race conditions, crashes, ANRs, memory leaks, and
Compose recomposition storms — before they reach users. This supplements the
existing type-checker and the JVM unit test suite, which cover pure logic but
cannot exercise the Android runtime, Compose scheduler, or Room coroutine flows
under real conditions.

---

## Infrastructure

```
This VPS (build + JVM tests)
        │
        │  hcloud API
        ▼
 Hetzner CAX31 (ARM64, 8 vCPU, 16 GB)
   └── Waydroid (Android container, no nested virt needed)
         └── ADB localhost:5555
               └── cloud.glucodroid APK
```

The CAX31 is a **phoenix VM**: it exists only while tests run (~20 min), then
is destroyed. A snapshot preserves the provisioned Waydroid state between runs.
Cost: ~€0.30/month at one run per day.

---

## Test tiers

### Tier 1 — JVM unit tests (this machine, ~1 min)

Run without a device. Cover pure Kotlin logic in the history pipeline.

| Suite | What it guards |
|-------|---------------|
| `HistoryRecoveryPolicyTests` | Recovery trigger logic: 10 cases covering empty history, start-time gap, tail gap, sensor mismatch, absent current reading |
| `HistoryEdgeSignatureTests` | `distinctUntilChangedBy` deduplication gate: value/serial/size/raw changes, sparse mid-list hash, empty/single-element edge cases |
| `DashboardChartPipelineTests` | 512-point consumer cap (both smoothing paths), prediction window trim |
| `DashboardHistoryCollectionPolicyTests` | Mode routing: dashboard vs full-history; coalesce skip on first emission |

**Run:**
```bash
./gradlew :Common:testMobileLibre3SiDexGoogleReleaserUnitTest \
  --tests "tk.glucodata.ui.viewmodel.*" \
  --tests "tk.glucodata.ui.DashboardChartPipelineTests" \
  --no-daemon
```

### Tier 2 — Waydroid device tests (CAX31, ~20 min)

Run against a real Android runtime. Cover what the JVM tests cannot.

| Check | Tool | What it finds |
|-------|------|--------------|
| App launch / crash | ADB + logcat | Crashes on startup, missing native libs |
| ANR detection | logcat grep | Main thread blocks > 5 s |
| Compose recomposition counts | Perfetto `view` slice | Recomposition storms during data flow |
| Frame timeline | `surfaceflinger.frametimeline` | Jank during chart updates |
| Heap profiling | Perfetto `heapprofd` | Memory leaks over launch cycles |
| BatteryTrace emission counts | logcat grep | Regression in flow rate-limiting |
| Cold-start time | `am start` timing | Performance regression on init |

**What cannot be tested on Waydroid** (no BLE stack in the VM):

- Live glucose readings from a sensor
- BLE reconnect recovery path
- Sensor scanning and pairing

These require a physical device. The Waydroid suite covers everything up to the
BLE layer: app lifecycle, UI, database, and coroutine flows with simulated data.

---

## Scheduled run (once per 24 hours)

Add to cron on this VPS (or any machine with `hcloud` installed):

```cron
0 3 * * * cd /home/rob/git/JugglucoNG && ./scripts/device-test-cycle.sh >> /tmp/glucodroid-test.log 2>&1
```

Results land in `./device-test-results/YYYYMMDD-HHMMSS/`.

---

## CAX31 lifecycle per run

```
1. Find latest snapshot (label: role=glucodroid-device-test)
2. hcloud server create  ← CAX31 running
3. Upload glucodroid.apk + remote-test-suite.sh
4. Start Waydroid session, wait for ADB
5. adb install glucodroid.apk
6. Run remote-test-suite.sh → /tmp/test-results/
7. scp /tmp/test-results/ → ./device-test-results/<timestamp>/
8. hcloud server shutdown
9. hcloud server create-image (new snapshot, same label)
10. hcloud image delete (old snapshot)
11. hcloud server delete  ← CAX31 gone
```

Failure at any step leaves the server running. The script prints the SSH
command and delete command so you can investigate, then clean up manually
with `./scripts/hetzner-cleanup.sh`.

---

## What each run collects

| File | Contents |
|------|----------|
| `summary.txt` | PASS/FAIL counts, crash count, ANR count, BatteryTrace line count, PSS |
| `trace.pb` | Perfetto system trace — open at `ui.perfetto.dev` |
| `logcat.txt` | Full logcat since app launch |
| `crashes.txt` | Extracted crash stacks (present only if crashes found) |
| `anrs.txt` | Extracted ANR traces (present only if ANRs found) |
| `battery-trace.txt` | BatteryTrace emission lines for history/recovery flows |
| `meminfo.txt` | `dumpsys meminfo` snapshot (PSS, Java heap, native heap) |
| `package-info.txt` | Installed version name/code and install timestamps |

### Reading a Perfetto trace

Open `trace.pb` at `ui.perfetto.dev`. Slices to check:

- **`view` slices** for `cloud.glucodroid`: recomposition storms show as many
  rapid `Choreographer#doFrame` calls. A healthy dashboard update from a single
  new glucose reading should produce one recomposition, not three.
- **`surfaceflinger.frametimeline`**: frames exceeding 16 ms show as red
  `Expected Timeline` / `Actual Timeline` pairs.
- **`sched/sched_switch`**: coroutine thread contention — look for the
  `DefaultDispatcher` workers blocking each other on `_glucoseHistory` writes.
- **`heapprofd`**: allocations attributed to `cloud.glucodroid` accumulate
  across the navigation cycle; a leak shows as steadily growing retained bytes.

### Reading BatteryTrace output

`battery-trace.txt` contains log lines like:

```
BatteryTrace dashboard.history.emission        count=3   detail=mode=DASHBOARD size=288
BatteryTrace dashboard.history.recovery.request count=1  detail=serial=ABC123 reason=ui_recovery
BatteryTrace glucose.native.one_shot_sync       count=1
```

Expect `dashboard.history.emission` to increment once per new reading (not
three times — the triple-write race from 0.9.7). A `recovery.request` more
than once per 30-second window indicates the throttle has been bypassed.

---

## Script reference

| Script | Purpose |
|--------|---------|
| `scripts/hetzner-setup.sh` | **One-time**: provision base snapshot (run before everything else) |
| `scripts/device-test-cycle.sh` | **Daily**: full phoenix cycle — create, test, snapshot, destroy |
| `scripts/hetzner-open.sh` | Open a server for manual investigation (not auto-deleted) |
| `scripts/hetzner-cleanup.sh` | List and delete orphaned servers and old snapshots |
| `scripts/remote-test-suite.sh` | Runs inside the CAX31; not called directly |

All scripts read credentials from `scripts/.env.hetzner`:

```bash
cp scripts/.env.hetzner.example scripts/.env.hetzner
# edit scripts/.env.hetzner and paste your HCLOUD_TOKEN
```

---

## Setup sequence (first time)

```bash
# 1. Fill in credentials
cp scripts/.env.hetzner.example scripts/.env.hetzner
$EDITOR scripts/.env.hetzner

# 2. Build the APK
./gradlew assembleMobileLibre3SiDexGoogleReleaser -Pno_x86 -Pno_x86_64
cp Common/build/outputs/apk/*/release/*.apk ~/Downloads/glucodroid.apk

# 3. Provision the base snapshot (~15 min, one-time)
./scripts/hetzner-setup.sh

# 4. Run a test cycle
./scripts/device-test-cycle.sh

# 5. Open results
open device-test-results/$(ls -t device-test-results | head -1)/summary.txt
# Perfetto: drag trace.pb to ui.perfetto.dev
```

---

## Future work

- **Instrumented tests (UI Automator / Espresso)**: replace the current tap
  coordinates in `remote-test-suite.sh` with deterministic `UiObject2` selectors.
  Target: navigate Dashboard → History → Settings and back, assert no ANR.
- **Synthetic glucose injection**: mock the native layer to feed readings into
  Room directly, testing the full Flow pipeline without BLE.
- **Jank budget assertion**: parse `summary.txt` and fail the CI run if
  `surfaceflinger.frametimeline` shows > N janked frames per trace.
- **Physical device tier**: add a Tier 3 for BLE reconnect and live sensor tests
  once a dedicated test device is available.
