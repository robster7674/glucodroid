package tk.glucodata

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Periodic "is the bot still alive?" warm-up for Telegram destinations.
 *
 * Fires every [KEEP_ALIVE_INTERVAL_MINUTES] per destination and performs a
 * single `getMe` POST against `https://api.telegram.org/bot{token}/getMe`.
 * The shared [OutboundApi.httpClient] is used so the connection is the same
 * one warm-pooled by [OutboundApi.executePostOnce] (HTTP/2 PING every 55s,
 * 5-idle / 10-min connection pool).
 *
 * Why this exists: the system [java.net.HttpURLConnection] pool closed
 * idle connections after Telegram's 75s nginx keepalive_timeout, which made
 * the next CGM-driven send fail with "unexpected end of stream". The
 * PING-based keep-alive in the OkHttp client fixes the steady-state case,
 * but flat-glucose stretches (no sends for an hour) can still let
 * destinations sit idle. This worker is a belt-and-braces health probe:
 *
 *  - On success, the same [OutboundApiSettings.recordSuccess] path is
 *    used so the destination's `lastSuccessAtMs` advances. This resets
 *    the planned "no success in N min" UI banner condition.
 *  - On failure, only a warning is logged. We deliberately do *not* raise
 *    a "failing" state here, because `getMe` failures indicate "the
 *    connection is dead", not "Telegram is broken for this chat". The
 *    PING keep-alive and the CGM-driven send path are the load-bearing
 *    health signals.
 */
class TelegramKeepAliveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val config = OutboundApiSettings.load(context)
        val now = System.currentTimeMillis()
        for (destination in config.destinations) {
            if (!destination.enabled) continue
            if (destination.normalizedPreset() != OutboundApiSettings.PRESET_TELEGRAM_BOT) continue
            if (!destination.isReady()) continue
            pingOne(context, destination.id, destination.token.trim(), now)
        }
        return Result.success()
    }

    private fun pingOne(context: Context, destinationId: String, token: String, nowMs: Long) {
        if (token.isBlank()) return
        val url = "https://api.telegram.org/bot$token/getMe"
        val body = "{}".toByteArray(Charsets.UTF_8)
        val contentType = "application/json; charset=UTF-8"
        val request = try {
            Request.Builder()
                .url(url)
                .header("Content-Type", contentType)
                .header("Accept", "application/json, text/plain")
                .header("User-Agent", "JugglucoNG API destinations")
                .post(body.toRequestBody(contentType.toMediaTypeOrNull()))
                .build()
        } catch (th: Throwable) {
            Log.w(TAG, "build failed for $destinationId: ${th.message}")
            return
        }
        try {
            OutboundApi.httpClient.newCall(request).execute().use { response ->
                val code = response.code
                if (code in 200..299) {
                    OutboundApiSettings.recordSuccess(context, destinationId, code)
                    Log.v(
                        TAG,
                        "getMe ok destination=$destinationId code=$code at=$nowMs"
                    )
                } else {
                    val snippet = response.body?.string().orEmpty().take(200)
                    Log.w(
                        TAG,
                        "getMe non-2xx destination=$destinationId code=$code body=$snippet"
                    )
                }
            }
        } catch (io: IOException) {
            Log.w(TAG, "getMe IO failure destination=$destinationId: ${io.message}")
        } catch (th: Throwable) {
            Log.w(TAG, "getMe failure destination=$destinationId: ${th.message}")
        }
    }

    companion object {
        private const val TAG = "TelegramKeepAlive"
    }
}
