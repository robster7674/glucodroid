# Outbound API — Architecture & Extension Guide

> Outbound-only HTTP / chat delivery of CGM readings to user-configured
> destinations. GlucoDroid does not currently receive from any of these
> channels; the inbound direction is reserved for future work.

---

## 1. Scope

`OutboundApi` is the single framework that ships glucose readings from
GlucoDroid to user-configured third-party destinations. The supported
presets are:

| Preset constant          | Display name        | Protocol                                   |
|--------------------------|---------------------|--------------------------------------------|
| `custom_json`            | Custom JSON webhook | User-supplied HTTP POST with full JSON body |
| `telegram_bot`           | Telegram bot        | `POST https://api.telegram.org/bot{token}/sendMessage` |
| `glucowatch_vk`          | VK direct message   | `POST https://api.vk.com/method/messages.send` (DM) |
| `vk_messages`            | VK text message     | `POST https://api.vk.com/method/messages.send` (text) |
| `delta_chat_relay` *(v2)*| Delta Chat relay    | `POST <user relay URL>/glucose` (chatmail) |

The framework is intentionally generic: a destination is `preset + url +
token + chatId/recipients + template + headers + trigger thresholds + min
interval`. New presets plug in via three extension points (see §7).

---

## 2. Files

| File | Role |
|---|---|
| `Common/src/main/java/tk/glucodata/SuperGattCallback.java` (line 577) | **Producer** — calls `OutboundApi.enqueueGlucose(...)` on every new CGM reading. |
| `Common/src/main/java/tk/glucodata/OutboundApi.kt` | **Orchestrator + worker.** Object `OutboundApi` (queue, trigger gating, rate limit, network guard), data class `Reading` (post-render fields), data class `JournalSnapshot`, and `class OutboundApiWorker` (`runOnce`, `sendTelegram`, `sendVk`, `sendJson`, HTTP plumbing). |
| `Common/src/main/java/tk/glucodata/OutboundApiSettings.kt` | **Persistence + destination model.** `Config`, `Destination`, presets, defaults, encode/decode to JSON, legacy migration. |
| `Common/src/mobile/java/tk/glucodata/OutboundApiJournalSnapshot.kt` | **Journal data source.** Builds a JSON snapshot of recent insulin / carb entries for `{journal}`, `{iob}`, `{cob}` substitution. `mobile` flavour only. |
| `Common/src/mobile/java/tk/glucodata/ui/OutboundApiSettingsScreen.kt` | **UI.** Compose screen, preset picker, per-destination editor, status row, test button. |
| `Common/src/mobile/java/tk/glucodata/ui/MainNavigation.kt` | **Wiring** — registers the route into the settings nav graph. |

---

## 3. Data flow

```
   CGM sensor
       │
       ▼
 SuperGattCallback                ◀── producer (Bluetooth LE notify)
       │
       │  outboundApiEnabled && shouldEmitExchangeUpdate
       ▼
 OutboundApi.enqueueGlucose(…)    ◀── public API; 4 overloads (varying
       │                              rawValue / autoValue inclusion)
       │
       │  enqueueGlucoseInternal()
       ▼
 OutboundApiSettings.Config       ◀── cached in-memory; reread from
                                    SharedPreferences("outbound_api")
                                    on first call. JSON-encoded array
                                    of Destination objects.

   For each active, ready destination:
       │
       ├── shouldSendForGlucose(mgdl)  ◀── trigger thresholds
       │
       ├── shouldQueue(...)            ◀── min interval + duplicate event-id guard
       │
       ├── recordQueued(...)
       │
       ├── build androidx.work.Data with reading fields
       │
       └── sendInProcess(...)
              │
              ├── hasUsableNetwork()   ◀── ConnectivityManager gate
              │
              ├── pendingSends < MAX_PENDING_SENDS (=12)
              │
              └── sendExecutor.execute { OutboundApiWorker.runOnce(...) }
                                         │
                                         ▼
                                  send()  ◀── dispatches on preset
                                         │
                            ┌────────────┼────────────┬────────────┐
                            ▼            ▼            ▼            ▼
                       sendTelegram   sendVk       sendJson   sendDeltaChatRelay
                                                                 (v2, not yet)
```

`OutboundApiWorker` is invoked directly from the in-process executor; the
`allowRetry=false` argument is hardcoded at the call site, so the
`Result.retry()` branch is currently dead code.

---

## 4. Destination model

```kotlin
data class Destination(
    val id: String,                    // UUID; primary key in the JSON array
    val enabled: Boolean,
    val name: String,                  // user-facing label
    val preset: String,                // one of PRESET_*; normalisePreset() maps legacy values
    val url: String,                   // resolved via resolvedUrl() → defaultUrl(preset) if blank
    val token: String,                 // bot token / VK access_token / relay auth
    val chatId: String,                // comma/semicolon/newline-split recipient list
    val apiVersion: String,            // VK only; defaults to "5.199"
    val headers: String,               // newline-separated `Name: value`, applied via applyHeaders()
    val messageTemplate: String,       // see §6 tokens
    val minIntervalMinutes: Int,       // dedup window; 0 = always
    val triggerMode: String,           // always | at_or_below | at_or_above | outside_range
    val triggerLowMgdl: Int,           // default 70
    val triggerHighMgdl: Int,          // default 180
    val lastQueuedEventId: String,     // dedup guard
    val lastQueuedAtMs: Long,
    val lastAttemptAtMs: Long,
    val lastSuccessAtMs: Long,
    val lastResponseCode: Int,
    val lastError: String?
)
```

`isReady()` gates dispatch:

- Telegram: `token.isNotBlank() && recipients().isNotEmpty() && all valid (numeric or @username)`
- VK: `token.isNotBlank() && recipients().isNotEmpty() && all numeric`
- Custom JSON / Delta Chat relay: just `resolvedUrl().isNotBlank()`

`withPreset(nextPreset)` swaps defaults smartly: if the user hasn't
overridden the URL, template, or name, they're replaced with the new
preset's defaults.

---

## 5. Message template tokens

`OutboundApi.renderMessage(template, reading)` substitutes the
following placeholders. `reading` is a flat view of the glucose + journal
state at the moment of send.

| Token              | Source                                                |
|--------------------|-------------------------------------------------------|
| `{event_id}`       | `{sensorId}:{timeMillis}:{mgdl}` (or `test-<ts>`)     |
| `{recipient}`      | The chat_id / VK peer_id / email the message is going to |
| `{value}`          | `displayText` — primary value formatted per `Applic.unit` |
| `{unit}`           | `mg/dL` or `mmol/L`                                   |
| `{mgdl}`           | Raw mg/dL integer                                     |
| `{mmol}`           | mmol/L, 2 decimals                                    |
| `{auto}` / `{auto_value}` | Raw auto-mode reading formatted per unit         |
| `{auto_mgdl}` / `{auto_mmol}` | Numeric auto reading in the named unit        |
| `{raw}`            | Raw sensor value, unconverted                         |
| `{raw_mgdl}` / `{raw_mmol}` | Raw value converted to mg/dL or mmol/L        |
| `{trend}`          | Trend name (e.g. `SingleUp`, `Flat`)                  |
| `{trend_arrow}`    | Unicode arrow (↑↗→↘↓ etc.)                            |
| `{rate_mgdl}` / `{rate_mmol}` | Rate of change in the named unit             |
| `{timestamp}`      | `timeMillis` as integer                               |
| `{time}`           | Locale-formatted short date+time                      |
| `{sensor}`         | Sensor ID                                             |
| `{sensor_gen}`     | Sensor generation                                     |
| `{alarm}`          | Alarm code                                            |
| `{iob}` / `{journal_iob}` | Active insulin (units)                         |
| `{cob}` / `{journal_cob}` | Active carbs (grams)                           |
| `{journal_events}` | JSON array of recent treatments (last 12h, capped 64) |
| `{journal}`        | Full journal JSON snapshot (`tk.glucodata.journal.snapshot.v2`) |
| `{test}`           | `true` for test sends, `false` for live readings      |

The full list is also exposed at runtime in
`templateTokens` (private val in `OutboundApiSettingsScreen.kt`).

Default templates:

- `DEFAULT_CHAT_TEMPLATE = "{value} {unit} {trend_arrow} {time}"`
  (Telegram, VK messages)
- `DEFAULT_GLUCO_WATCH_TEMPLATE = "GV:{mmol}|RAW:{raw}|TR:{trend_arrow}|AL:{alarm}|RT:{rate_mmol}|IOB:{iob}|COB:{cob}|TS:{timestamp}"`
  (GlucoWatch VK)
- `DEFAULT_CUSTOM_TEMPLATE = "{value} {unit} {trend_arrow} RAW:{raw} ({rate_mgdl} mg/dL/min) IOB:{iob} COB:{cob} {time}"`
  (Custom JSON webhook)

---

## 6. HTTP plumbing

`OutboundApiWorker.executePost(...)` is shared by all senders. It:

- `HttpURLConnection` with 15 s connect / 30 s read timeout (20 s / 30 s
  for Telegram because the bot API can be slow).
- `User-Agent: JugglucoNG API destinations`.
- Sends body as UTF-8 bytes; content-type differs by preset (JSON or
  form-urlencoded).
- Applies extra headers from `Destination.headers` (newline-separated
  `Name: value`); `Content-Type` cannot be overridden.
- Pre-response retry loop (off by default, `preResponseRetries=0`).
- Parses the response into `SendResponse(code, ok, retryable, error)`.
  Retryable: HTTP 429, 5xx, plus preset-specific API error codes
  (Telegram: 429/500/502/503; VK: 6/9/10).
- Records outcome: `recordSuccess()` on 2xx, `recordAttempt()` on failure
  (writes `lastResponseCode`, `lastError`, `lastAttemptAtMs`).

`friendlyError()` rewrites raw `HttpURLConnection` exceptions for
Telegram failures so the user sees
`"Telegram Bot API connection failed before a JSON response: <cause>"`
instead of the underlying `SocketTimeoutException` text.

---

## 7. Adding a new preset (e.g. `delta_chat_relay`)

Three extension points; for the Delta Chat relay all three are needed.

### 7.1 Constant in `OutboundApiSettings.kt`

```kotlin
const val PRESET_DELTA_CHAT_RELAY = "delta_chat_relay"
```

Add to `normalizePreset(...)` whitelist so the value round-trips through
JSON. Decide on validation rules for `Destination.isReady()` — the
relay needs `url.isNotBlank() && token.isNotBlank()` (token is the relay
auth bearer). Recipients are required; add an `isDeltaChatRecipient()`
helper accepting either a chatmail address (`@<server>`) or a numeric
chat-id-equivalent (delta-chat doesn't use numeric ids, so the helper
just validates that the address contains an `@`).

### 7.2 Default URL + template + name in `OutboundApiSettings.kt`

```kotlin
fun defaultUrl(preset: String, token: String = ""): String = when (normalizePreset(preset)) {
    …
    PRESET_DELTA_CHAT_RELAY -> ""  // user must configure
    else -> DEFAULT_CUSTOM_URL
}

fun defaultTemplate(preset: String): String = when (normalizePreset(preset)) {
    …
    PRESET_DELTA_CHAT_RELAY -> DEFAULT_CHAT_TEMPLATE  // same concise format as Telegram
    else -> DEFAULT_CUSTOM_TEMPLATE
}

fun defaultName(preset: String): String = when (normalizePreset(preset)) {
    …
    PRESET_DELTA_CHAT_RELAY -> "Delta Chat relay"
    else -> "Custom JSON webhook"
}
```

### 7.3 Send branch in `OutboundApiWorker.send(...)` and the `OutboundApiSettingsScreen` preset list

```kotlin
// OutboundApi.kt
private fun send(
    destination: OutboundApiSettings.Destination,
    reading: OutboundApi.Reading
): SendResponse {
    val message = OutboundApi.renderMessage(destination.resolvedTemplate(), reading)
    return when (destination.normalizedPreset()) {
        OutboundApiSettings.PRESET_TELEGRAM_BOT -> sendTelegram(destination, reading, message)
        OutboundApiSettings.PRESET_GLUCO_WATCH_VK,
        OutboundApiSettings.PRESET_VK_MESSAGES -> sendVk(destination, reading, message)
        OutboundApiSettings.PRESET_DELTA_CHAT_RELAY -> sendDeltaChatRelay(destination, reading, message)
        else -> sendJson(destination, reading, message)
    }
}

private fun sendDeltaChatRelay(
    destination: OutboundApiSettings.Destination,
    reading: OutboundApi.Reading,
    message: String
): SendResponse {
    val body = JSONObject()
        .put("preset", "delta_chat_relay")
        .put("event_id", reading.eventId)
        .put("recipient", reading.recipient)
        .put("message", message)
        .put("test", reading.test)
        .toString()
        .toByteArray(Charsets.UTF_8)
    val headers = "Authorization: Bearer ${destination.token.trim()}"
    return executePost(
        urlString = destination.resolvedUrl(),
        contentType = "application/json; charset=UTF-8",
        headers = headers,
        body = body,
        parseApiError = ::parseDeltaChatRelayError,
        connectTimeoutMs = 20_000,
        readTimeoutMs = 30_000
    )
}
```

```kotlin
// OutboundApiSettingsScreen.kt
private fun destinationPresetSpecs(): List<PresetSpec> = listOf(
    …,
    PresetSpec(
        id = OutboundApiSettings.PRESET_DELTA_CHAT_RELAY,
        titleRes = R.string.outbound_api_preset_delta_chat_relay,
        descriptionRes = R.string.outbound_api_preset_delta_chat_relay_desc,
        icon = Icons.Filled.MailOutline  // or similar; pick from filled icons
    )
)
```

For v1 (outbound only, no commands) this is the entire change set:
~50 lines in `OutboundApiSettings.kt`, ~30 lines in `OutboundApi.kt`, one
`PresetSpec` entry, and corresponding string resources. No changes to
`SuperGattCallback` — the producer is preset-agnostic.

### 7.4 String resources

Add to `Common/src/mobile/res/values/strings.xml`:

- `outbound_api_preset_delta_chat_relay` = "Delta Chat relay"
- `outbound_api_preset_delta_chat_relay_desc` = "Send readings to a Delta Chat relay (chatmail)."
- `outbound_api_delta_chat_relay_url_label` = "Relay URL"
- `outbound_api_delta_chat_relay_url_help` = "URL of your Delta Chat relay, e.g. https://glucodroid-relay.example.com"
- `outbound_api_delta_chat_relay_token_label` = "Relay auth token"
- `outbound_api_delta_chat_relay_recipient_help` = "One chatmail address per line (e.g. you@chatmail.example)."

---

## 8. Persistence and migration

Destinations are stored as a JSON array under the key
`destinations_json` in `SharedPreferences("outbound_api")`. Top-level
boolean `enabled` is kept for backward compatibility with the
pre-destinations migration.

`migrateLegacy(context)` (in `OutboundApiSettings.kt`) runs once if no
JSON array is present: reads the flat `provider/url/token/chat_id/...`
keys (where `provider` is `webhook_json` or `vk`) and synthesises a
single `Destination` of the matching preset. The result is
immediately `save()`d back so the legacy keys become inert.

JSON encoding is symmetric via `encodeDestinations(...)` /
`decodeDestinations(...)`. The `id` field is deduped
case-insensitively on load.

---

## 9. Known limitations and v2 ideas

- **No inbound.** GlucoDroid never listens for messages on any of these
  channels. Adding inbound means a foreground service, a server
  endpoint (or long-poll), and a command grammar.
- **No retry on transient failure.** The `allowRetry=false` hardcode
  means a failed POST is logged but not re-queued. A simple fix is to
  persist `(destinationId, eventId, payload)` and have a periodic
  alarm retry pending failures.
- **No media (images, charts).** All senders are text-only.
- **No auth refresh.** VK tokens and Telegram tokens are user-supplied
  strings; rotation is manual. A future preset could integrate
  service-account OAuth.
- **Single-process executor.** A single thread
  (`OutboundApiSend`) handles all sends. Throughput is fine for a
  single user but is a bottleneck if multiple destinations are
  configured and one is slow.
- **No "send only on change" beyond min-interval.** Two identical
  readings 6 minutes apart are sent. Adding a
  "only on delta ≥ N mg/dL" trigger would help noisy sensors.

These are tracked in the audit document
(`docs/chatbots-branch-audit.md`).
