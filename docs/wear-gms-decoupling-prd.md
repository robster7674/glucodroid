# PRD: Decouple Wear OS / GMS from the nogoogle Build Variant

**Status:** Ready for implementation  
**Branch target:** `glucodroid`  
**Estimated effort:** 1–2 days  
**Priority:** Medium — blocks a clean F-Droid submission

---

## Problem

The `nogoogle` flavor is the intended distribution target for F-Droid and
other non-Play-Store channels.  It sets `Google=0` and `minSdk 23`, has no
`requireWatch` manifest flag, and produces a version name without a `-g`
suffix.

However, the following GMS libraries are compiled into **every** build
unconditionally:

```
com.google.android.gms:play-services-base:18.9.0
com.google.android.gms:play-services-wearable:19.0.0
org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0
```

These are declared in the top-level `dependencies {}` block in
`Common/build.gradle` with no flavor qualifier, so R8 includes them in the
nogoogle APK even though no Google-specific feature is intentionally
enabled for that variant.

**Consequence:** The nogoogle APK depends on Google Play Services at
runtime for Wear OS communication.  On GMS-free devices (GrapheneOS,
CalyxOS, LineageOS without MicroG) the Wear OS data link is silently
non-functional or causes a crash at startup via `ProviderInstaller`.
F-Droid's reproducible-build checker will also flag the GMS dependency.

---

## Scope

### Files that import `com.google.android.gms.wearable.*`

All in shared source trees (compiled into every mobile build):

| File | Role |
|---|---|
| `Common/src/main/java/tk/glucodata/MessageReceiver.kt` | `WearableListenerService` — receives glucose requests from the watch |
| `Common/src/main/java/tk/glucodata/MessageSender.kt` | Sends glucose readings, capabilities, and config to the watch |
| `Common/src/mobile/java/tk/glucodata/WatchInterop.kt` | Node discovery, watch pairing state |
| `Common/src/mobile/java/tk/glucodata/Wearos.java` | High-level Wear OS send/receive orchestration |

### Files that import `com.google.android.gms.common.*` / `gms.security.*`

| File | Role |
|---|---|
| `Common/src/main/java/tk/glucodata/GoogleServices.java` | Checks GMS availability via `GoogleApiAvailabilityLight` |
| `Common/src/main/java/tk/glucodata/NightPost.java` | Calls `ProviderInstaller.installIfNeededAsync()` for TLS |
| `Common/src/mobile/java/tk/glucodata/Libreview.java` | Same `ProviderInstaller` call for LibreView HTTPS |

---

## Proposed Architecture

### 1 — Wearable transport interface

Create a new interface in `Common/src/main/java/tk/glucodata/wear/`:

```kotlin
// WearTransport.kt
interface WearTransport {
    fun sendGlucose(serial: String, mgdl: Int, rate: Float, timMs: Long, alarm: Int)
    fun sendConfig()
    fun sendMessage(path: String, payload: ByteArray)
    fun getConnectedNodes(callback: (List<String>) -> Unit)
    fun isAvailable(): Boolean
}
```

### 2 — GMS implementation (google flavor only)

Move the bodies of `MessageSender.kt`, `WatchInterop.kt`, and the
wearable parts of `Wearos.java` into a new class:

```
Common/src/mobileSiGoogle/java/tk/glucodata/wear/GmsWearTransport.kt
```

This class `implements WearTransport` and contains all existing
`com.google.android.gms.wearable.*` call sites verbatim.

### 3 — No-op implementation (nogoogle flavor)

```
Common/src/mobileSiNogoogle/java/tk/glucodata/wear/NoopWearTransport.kt
```

```kotlin
class NoopWearTransport : WearTransport {
    override fun sendGlucose(...) = Unit
    override fun sendConfig() = Unit
    override fun sendMessage(...) = Unit
    override fun getConnectedNodes(callback: (List<String>) -> Unit) = callback(emptyList())
    override fun isAvailable() = false
}
```

### 4 — Factory / injection point

In `Applic.kt` (or a new `WearTransportFactory.kt` in `main/`), add:

```kotlin
// Resolved at compile time via flavor source sets — no reflection needed.
val wearTransport: WearTransport = WearTransportFactory.create()
```

`WearTransportFactory.kt` exists in **both** source sets:
- `mobileSiGoogle/java/` → returns `GmsWearTransport()`
- `mobileSiNogoogle/java/` → returns `NoopWearTransport()`

All existing callers replace direct `MessageSender` / `Wearos` calls with
`Applic.wearTransport.sendGlucose(...)` etc.

### 5 — ProviderInstaller decoupling (smaller scope)

`NightPost.java` and `Libreview.java` call `ProviderInstaller` for TLS
hardening on old Android versions.  Wrap this in a helper:

```
Common/src/main/java/tk/glucodata/GmsProviderInstaller.java  (interface)
Common/src/mobileSiGoogle/.../GmsProviderInstallerImpl.java  → real call
Common/src/mobileSiNogoogle/.../GmsProviderInstallerImpl.java → no-op
```

### 6 — Build.gradle dependency scoping

Move from unconditional to flavor-specific:

```groovy
// Before (unconditional — applies to all variants):
implementation "com.google.android.gms:play-services-base:$GMSBASE"
implementation "com.google.android.gms:play-services-wearable:$GMSWEAR"
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0'

// After:
mobileLibre3SiDexGoogleImplementation "com.google.android.gms:play-services-base:$GMSBASE"
mobileLibre3SiDexGoogleImplementation "com.google.android.gms:play-services-wearable:$GMSWEAR"
mobileLibre3SiDexGoogleImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0'
wearLibre3SiDexGoogleImplementation "com.google.android.gms:play-services-wearable:$GMSWEAR"
wearLibre3SiDexGoogleImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0'
```

---

## Implementation Steps

1. **Create `WearTransport` interface** in `main/` source set.
2. **Extract `GmsWearTransport`** from `MessageSender.kt`, `WatchInterop.kt`,
   and `Wearos.java` into `mobileSiGoogle/java/`.  Keep the original files
   as thin delegators to `Applic.wearTransport` during the transition.
3. **Write `NoopWearTransport`** in `mobileSiNogoogle/java/`.
4. **Add `WearTransportFactory`** to both source sets.
5. **Wire `Applic.wearTransport`** — initialise in `Applic.onCreate()` via
   the factory, guarded by `!isWearable` (the watch build initialises
   differently).
6. **Replace call sites** — global search for `MessageSender.`, `Wearos.`,
   `WatchInterop.` and replace with `wearTransport.*`.  The watch-side
   `wear/` source set is unaffected (it talks to the phone, not GMS directly).
7. **Scope GMS deps** in `build.gradle` as shown above.
8. **Remove now-empty shared files** (`MessageReceiver.kt` may still need to
   live in `main/` as a `WearableListenerService` registration — gate its
   body with `BuildConfig.Google == 1` or move the service declaration to
   the `mobileSiGoogle` manifest overlay).
9. **Build and verify both variants:**
   - `assembleMobileLibre3SiDexNogoogleRelease` — must produce an APK with
     no `com.google.android.gms.wearable` classes in the DEX
   - `assembleMobileLibre3SiDexGoogleRelease` — full watch functionality
     unchanged
10. **Verify with `apkanalyzer`:**
    ```
    apkanalyzer dex packages --defined-only \
      Common/build/outputs/apk/mobileLibre3SiDexNogoogle/release/*.apk \
      | grep "com.google.android.gms.wearable" | wc -l
    # Must print 0
    ```

---

## What NOT to Do

- **Do not use `if (BuildConfig.Google == 1)` guards inline** — that still
  compiles the GMS symbols into the nogoogle DEX; R8 may not strip them if
  any reflection or service-loader path touches them.
- **Do not use reflection or `Class.forName`** to load the GMS transport at
  runtime — flavor source sets give you compile-time separation for free.
- **Do not touch the `wear` / `small` flavor source sets** — the watch APK
  communicates over BLE directly, not via the phone's GMS Wearable layer.
  Only the `mobile` source set is in scope.

---

## Testing

| Test | How |
|---|---|
| nogoogle DEX clean | `apkanalyzer dex packages --defined-only nogoogle.apk \| grep gms.wearable` → 0 lines |
| google watch sync still works | Install google build on phone + watch; confirm glucose appears on watch within 90 s of a reading |
| nogoogle on GMS-free device | Install nogoogle build on GrapheneOS device; app starts, glucose displays, no crash |
| nogoogle on GMS device | Install nogoogle build on stock Android; Wear OS section absent from UI, no crash |
| ProviderInstaller | `adb logcat \| grep ProviderInstaller` on nogoogle build → no output |

---

## Out of Scope for This Work

- Garmin ConnectIQ SDK (`ciq-companion-app-sdk`) — already `mobileImplementation`,
  no GMS symbols, not a blocker for F-Droid.
- `androidx.health.connect` — not a GMS dependency; F-Droid compatible.
- `WearInt` (Garmin/Gadgetbridge watch integration path) — unaffected.
