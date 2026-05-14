# JugglucoNG — Feature Status Findings

Review date: 2026-05-14 (branch `feat-rob`)

---

## 1. FreeStyle Libre 2 / Libre 3 — Bluetooth Readings & Alarms

### What's working

| Area | File(s) | Status |
|---|---|---|
| BLE scanning | `BleDeviceScanner.kt` | Complete — modern permission model (API 31+) |
| Libre2 BLE handshake | `Libre2GattCallback.java` (806 lines) | Complete — Gen1 + Gen2 challenge-response, multi-packet reassembly (3 packets → 46 bytes) |
| Libre3 BLE | `Libre3GattCallback.java` (libre3 flavor) | Complete — all characteristics (glucose, historic, clinical, factory, cert, challenge) |
| Native glucose processing | `libre2.cpp` | Complete — dynamic-links Abbott `libfreestyle`, handles `processTooth`, crypto `V1`/`V2` |
| Glucose result pipeline | `SuperGattCallback.java` | Complete — unpacks mgdL / alarm code / rate; raw + calibrated modes |
| High/low alarms | `AlertRuntimeManager` + `LossOfSensorAlarm.java` | Complete — threshold alerts + `setExactAndAllowWhileIdle` signal-loss alarm |
| Alarm suspension | `Natives.readalarmsuspension` / `writealarmsuspension` | Present; persistence across restarts unverified |
| Broadcast to backends | `SuperGattCallback.dowithglucose()` | 8+ targets: xDrip+, HealthConnect, Garmin, Gadgetbridge, Juggluco, LibreLink, EverSense, OutboundAPI |
| NFC path | `nfcdata.hpp/cpp`, `AlgNfcV` | Separate, complete |
| libreOld flavor Libre3 stub | `src/libreOld/.../Libre3GattCallback.java` | Intentional stub (gen=0) — LibreOld doesn't do Libre3 BLE |

### Known gaps / risks

- **No handshake timeout** — a stuck phase 2–4 hangs indefinitely with no retry limit; a watchdog timer is needed.
- **Thread safety** — `online`, `nexttime`, and several other statics in `SuperGattCallback` are non-volatile; race conditions possible with multi-sensor setups.
- **Crypto black box** — key derivation uses Abbott's proprietary `.so` via `dlopen`; cannot be audited.
- **Alarm suspension persistence** — state stored via `Natives.readalarmsuspension()` but not verified to survive app restarts cleanly.

---

## 2. Spoken BG Values — Schedule & Reliability

### What's working

| Area | File(s) | Status |
|---|---|---|
| TTS engine | `Talker.java` | Complete — Android `TextToSpeech`, configurable voice / speed / pitch / stream |
| Speak on new reading | `SuperGattCallback.java` ~line 1570 | Works — every BLE reading calls `talker.selspeak()` when `dotalk` is set |
| Minimum-interval throttle | `Talker.selspeak()` | Works — `now > nexttime` gate with user-configurable `cursep` (1–999 s) |
| Alarm speech | `AlertRuntimeManager` path | Works — separate from `selspeak`, fires on threshold alerts |
| Settings UI | `TalkerSettingsScreen.kt` | Mostly complete — on/off, alarm speak, talkTouch, separator, speed, pitch, voice picker |
| Time-of-day profiles | `NumAlarm.java` + `Settings.java` | Works — `AlarmManager.setAlarmClock()` fires profile changes; re-inits TTS settings |

### What's missing / unreliable

- **No independent timed schedule** — speech is only triggered by incoming BLE readings. If the sensor drops out, there is silence. There is no "speak the current value every N minutes" timer (no `WorkManager` job, no `AlarmManager` periodic wakeup for speech itself).
- **No screen-state gate** — no check of `KeyguardManager` before speaking; the app speaks on every throttled reading regardless of whether the screen is on or the user is interacting.
- **No conditional speech** — no "only speak if out of target range", "only speak if asleep", etc.
- **Doze reliability** — depends entirely on the foreground service staying alive. No explicit wake-lock acquired before TTS call; on aggressive OEMs the TTS engine may be throttled.
- **Several settings commented out** — `speakMessages`, `talk_touch`, profile selector row are dead UI code in `TalkerSettingsScreen.kt`.

### Recommended improvements

1. Add a `WorkManager` periodic task (or `AlarmManager` exact wakeup) that fires `Talker.speak(currentValue)` at the user-configured interval, independent of readings.
2. Add a `KeyguardManager.isDeviceLocked` check so speech can optionally be suppressed when the screen is on / phone unlocked.
3. Acquire a partial wake-lock briefly around the TTS call to survive doze.

---

## 3. AOD / Lock Screen Notification

### What's working

| Area | File(s) | Status |
|---|---|---|
| Persistent foreground notification | `Notify.java` lines 1047–3350 | Complete — value + trend arrow + 3-hour sparkline graph via `RemoteViews` |
| Collapsed layout | `notification_material.xml` | Complete — value + arrow + status text |
| Expanded layout | `notification_material_regular_expanded.xml` | Complete — adds full chart |
| Chart / bitmap rendering | `NotificationChartDrawer.java` | Complete — IBM Plex Sans font, color-coded value, target range bands |
| AOD / lock screen overlay | `AODOverlayService.kt` (588 lines) | Complete — `TYPE_ACCESSIBILITY_OVERLAY` + `FLAG_SHOW_WHEN_LOCKED` via AccessibilityService |
| Lock/unlock detection | `AODOverlayService.kt` lines 151–158 | Complete — `KeyguardManager` + screen state broadcasts + 100 ms poll |
| Light sensor adaptive dim | `AODOverlayService.kt` lines 220–252 | Complete — logarithmic lux→alpha, 0.15 smoothing |
| Burn-in protection | `AODOverlayService.kt` lines 326–399 | Complete — position randomisation (TOP/CENTER/BOTTOM × LEFT/CENTER/RIGHT) |
| Home screen widget | `ExpressiveAppWidget.kt` (Glance) | Complete — value + arrow + optional chart |
| Wear complications | `src/wear/.../glucosecomplication/` | Complete — value, arrow, icon, short-arrow variants |

### Differences from xDrip+

- **Approach**: JugglucoNG uses an `AccessibilityService` overlay (works on Android 6+, universally) rather than native lock screen widget APIs (Android 12+ Keyguard host) or lock screen Complications (Android 13+). Requires user opt-in via Accessibility settings; may be blocked on some Samsung/Huawei OEM lock screens.
- **Richer AOD**: light sensor dimming and burn-in prevention exceed xDrip+'s static overlay.
- **Chart in collapsed notification**: exists but disabled by default (`notification_chart_collapsed` pref).
- **No snooze/dismiss action on the persistent glucose notification** — alarm notifications have action buttons, the always-on glucose notification does not.

---

## Planned Work (feat-rob branch)

- [x] Nightscout Settings UX — URL/secret always visible; mutually exclusive Upload / Follow mode selector
- [ ] Spoken BG: independent schedule timer (WorkManager + wake-lock)
- [ ] Spoken BG: optional screen-state gate
- [ ] BLE: connection handshake timeout + retry limit
