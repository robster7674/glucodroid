# GlucoDroid chatbot integrations — branch: `chatbots`

Created 2026-06-05 as an investigation-only branch (no code changes yet).

## 1. Telegram integration — current state

GlucoDroid does **not** have a "Telegram bot integration" in the conversational
sense (no incoming-message webhook, no /commands, no polling, no bidirectional
chat). What it has is **Telegram Bot API as one of four outbound HTTP
destinations** for CGM readings.

### Architecture (outbound-only)

1. **Trigger site** — `Common/src/main/java/tk/glucodata/SuperGattCallback.java`
   line 577. Every new glucose reading is enqueued via
   `OutboundApi.enqueueGlucose(...)` (only when `outboundApiEnabled` and
   `shouldEmitExchangeUpdate`).

2. **Queue / orchestration** — `Common/src/main/java/tk/glucodata/OutboundApi.kt`
   (object `OutboundApi`). Single-thread executor
   (`OutboundApiSend`) handles dispatch. Network guard
   (`hasUsableNetwork`) + throttling (`MAX_PENDING_SENDS=12`,
   `lastQueueBusyStatusAtMs`, `lastNetworkUnavailableStatusAtMs`).

3. **Per-destination model** — `OutboundApiSettings.Destination` data class
   holds: `id`, `enabled`, `name`, `preset` (one of four constants),
   `url`, `token`, `chatId`, `apiVersion`, `headers`, `messageTemplate`,
   `minIntervalMinutes`, `triggerMode` (always / at_or_below / at_or_above /
   outside_range), `triggerLowMgdl`, `triggerHighMgdl`, plus last-queued /
   last-attempt / last-success bookkeeping. Persisted as JSON in
   `SharedPreferences("outbound_api")`.

4. **Send worker** — `class OutboundApiWorker` in same file. Pure
   `HttpURLConnection` POST. **No** background `WorkManager` scheduling in
   the current code path — the worker is invoked directly via
   `OutboundApiWorker.runOnce(...)` from the in-process executor, so the
   class is essentially a static send helper. (The `Result.retry()` branch
   exists but `allowRetry=false` is hardcoded at the call site, so retries
   never happen in practice.)

5. **UI** — `Common/src/mobile/java/tk/glucodata/ui/OutboundApiSettingsScreen.kt`
   provides a Jetpack Compose settings screen with a preset picker sheet,
   per-destination card (name, preset, token, chat_id, template, headers,
   trigger thresholds, min interval, test-button). Presets currently
   exposed (`destinationPresetSpecs()`):
   - `custom_json` (webhook POST with full JSON body)
   - `telegram_bot` — `https://api.telegram.org/bot{token}/sendMessage`
   - `glucowatch_vk` (VK direct messages)
   - `vk_messages` (VK API)

6. **Telegram send path** — `OutboundApiWorker.sendTelegram(...)` builds
   `{"chat_id": <recipient>, "text": <rendered message>}` POSTs it to the
   resolved URL. Recipients are validated by
   `isTelegramRecipient()` which accepts either a numeric chat_id or
   `@username`. Multiple recipients supported via comma/semicolon/newline
   separator in the chat-id field. 20 s connect / 30 s read timeout. Error
   parser `parseTelegramError` recognises `error_code` 429/500/502/503
   as retryable.

### What Telegram is good for here

- Push notifications to a user-owned chat (personal or group) whenever a
  new CGM reading lands. That's the entire surface.
- One-way. GlucoDroid never receives Telegram messages, never registers
  commands, never opens a long-poll. The user cannot text the bot back.

### What it would need to be a "chatbot"

- A persistent inbound listener (Telegram long-poll via
  `getUpdates` or webhook) — currently absent.
- A command/response grammar (e.g. `/current`, `/graph`, `/iob`,
  `/start`, `/help`).
- A state machine to keep glucose snapshot, journal, settings available
  to answer queries.
- Background service / foreground notification to keep the listener
  alive past Activity destruction — Android 14+ FOREGROUND_SERVICE rules
  apply.
- Battery / Doze / standby impact: long-poll must respect network
  policy.  Webhook is cleaner but requires a publicly reachable URL
  (HTTPS, valid cert, registered in BotFather).

## 2. Delta Chat as an alternative integration

Delta Chat is a credible second integration target — Rob already has a
working adapter in Hermes at
`~/.hermes/plugins/platforms/deltachat/adapter.py` (email-as-chat via
Autocrypt/PGP, chatmail servers, JSON-RPC core). The question for this
branch is whether Delta Chat fits the GlucoDroid user model.

### Two architectural options

#### Option A — Outbound-only, mirroring Telegram (low effort, ~1 day)

Treat Delta Chat as another preset in `OutboundApiSettings.Destination`,
parallel to `telegram_bot`:

- New constant: `PRESET_DELTA_CHAT = "delta_chat"` in
  `OutboundApiSettings`.
- New `Destination` field: `email` (the bot's chatmail address) and
  `password` (mail_pw). Plus `recipient` list of validated `@delta.chat`
  / `@nine.testrun.org` / regular email addresses. Reuse the existing
  `recipients()` splitter.
- Default URL: empty (or a placeholder `delta-chat://send`).
- Default template: same `DEFAULT_CHAT_TEMPLATE = "{value} {unit}
  {trend_arrow} {time}"`.
- New send branch in `OutboundApiWorker.send(...)`:
  `sendDeltaChat(...)`. **Problem:** Delta Chat is not an HTTP API. To
  send, you need either (a) a chatmail SMTP account + PGP-signed
  Autocrypt message, or (b) the deltachat-rpc-server running on the
  device and reachable.
  - (a) is technically possible from Android via `JavaMail` + a small
    PGP wrapper, but Android does not ship BouncyCastle by default and
    minSdk 23 means no JDK 11 `javax.mail`. You'd need to add
    `bouncycastle-android` or a chatmail client library. Heavy.
  - (b) requires the user to install `deltachat` (or the RPC server) on
    the phone and configure it — most GlucoDroid users will not do
    this. Not viable as a default.

- **Verdict:** outbox-only via raw SMTP+PGP is too much dependency
  surface for the limited win. Skip.

#### Option B — Companion server pattern (medium effort, 2-4 days)

This is the right shape. Treat Delta Chat as **inbound+outbound** via a
small companion service that the user runs on a server (Hetzner, RPi,
NAS). GlucoDroid stays a thin client:

1. **GlucoDroid side:**
   - Add a new preset `presets/deltachat_relay` whose URL points to the
     companion server (e.g. `https://glucodroid-relay.example.com/delta/send`),
     and which sends a `delta_chat_message` JSON body in the existing
     custom_json shape. The HTTP destination framework already exists —
     the only addition is a preset name + an icon + a default URL pattern.
   - For inbound commands: add a long-poll endpoint to the same relay
     (or use Firebase Cloud Messaging push, which GlucoDroid already
     pulls for other features). On inbound, the relay is responsible
     for authenticating the user and routing the command; GlucoDroid
     just renders the response and updates the home screen.
   - Persist relay URL + auth token in `OutboundApiSettings` like the
     other destinations. Add a "Delta Chat" toggle in settings that
     hits the relay's pairing endpoint and shows a QR code generated
     server-side for the user to scan in Delta Chat.

2. **Companion server side** (lives outside this repo, would be a new
   repo `glucodroid-delta-relay` or similar):
   - Python or Go HTTP service.
   - Uses the same deltachat-rpc-client Rob already has working in
     Hermes (PyPI `deltachat-rpc-server` + `deltachat-rpc-client`).
   - Endpoints: `POST /glucose` (inbound from GlucoDroid), `GET /poll`
     (commands), `POST /delta/send` (admin test).
   - Account: created via `POST https://nine.testrun.org/new` (chatmail
     REST) — or user's own chatmail server. Credentials stored
     encrypted on disk.
   - State: tiny SQLite with last glucose snapshot, journal, IOB/COB
     cache so command responses are fast and don't need to query
     GlucoDroid.

3. **UX:**
   - User installs GlucoDroid as normal, adds a Delta Chat destination,
     enters the relay URL + auth token, scans the relay's QR in their
     Delta Chat app.
   - From then on, every CGM reading flows to the user's Delta Chat
     self-chat or a contact (configurable).
   - User can reply `/current` / `/graph` / `/iob` / `/help` in Delta
     Chat → relay queries the local cache or calls back to
     GlucoDroid via push → responds inline.

### Caveats specific to Delta Chat on Android / GlucoDroid

- **No in-process Delta Chat core on Android.** deltachat-core is Rust
  with C FFI; an Android port exists in the upstream Delta Chat app but
  is tightly coupled to that app's lifecycle. Shipping a bundled
  deltachat-rpc-server binary is technically possible (28 MB static
  ELF — but it's a Linux/x86_64 binary, not an Android `.so`). For
  arm64-v8a you'd need a NDK cross-compile of the Rust core. That is a
  multi-week project, not a branch exploration.
- **SecureJoin BVH handshake** for first contact requires the bot to be
  reachable on chatmail (inbox + Autocrypt headers). The relay solves
  this.
- **E2E encryption is the point.** All commands travel over Autocrypt-
  encrypted mail, which is the differentiator vs Telegram. Trade-off:
  ~30-60s chatmail latency on the relay side, not the sub-second
  webhook feel of Telegram. Acceptable for glucose alerts.

### What I recommend for the `chatbots` branch

1. **Don't** add a Delta Chat preset to `OutboundApiSettings` in this
   repo. The HTTP-and-recipient model doesn't fit chatmail's E2E
   semantics, and bundling a Delta Chat core is out of scope.
2. **Do** add a new destination preset `delta_chat_relay` whose only
   job is to POST to a user-configured companion URL. This reuses the
   existing outbound machinery and unlocks bidirectional flows once
   the relay is built. Roughly 200 lines of Kotlin (preset constant,
   default URL pattern, friendly validation, icon, settings UI).
3. **Defer** the relay implementation to a separate repo. Build it
   with the same `deltachat-rpc-client` library Rob already has
   working for Hermes. Make it a 12-factor app: a single Python file
   + Dockerfile, deployable on any $5 VPS.
4. **Long-term:** if/when the user base grows, consider upstreaming
   the relay into the chatmail server fleet so no per-user VPS is
   needed.

## 3. Status of this branch

- No code changes.
- `chatbots` branch created off `glucodroid` and pushed to origin.
- `docs/outbound-api.md` is the canonical architecture & extension
  guide for the OutboundApi framework.
- This audit document is the only other commit on the branch.

## 4. Direction (decided 2026-06-05)

- **v1 (this branch):** outbound-only `delta_chat_relay` preset, parity
  with Telegram. Pure notification fan-out — no inbound commands.
  The relay does the SMTP+PGP+Autocrypt heavy lifting; GlucoDroid just
  POSTs JSON. Concretely: ~50 lines in `OutboundApiSettings.kt` for
  constant/defaults/validation, ~30 lines in `OutboundApi.kt` for the
  send branch, one `PresetSpec` entry, and string resources. Full diff
  recipe in `docs/outbound-api.md` §7.
- **v2 (future branch, not yet):** bidirectional — `current`, `graph`,
  `iob`, etc. Lives outside the `OutboundApi` plumbing since it
  needs a long-poll / push listener and a foreground service.
