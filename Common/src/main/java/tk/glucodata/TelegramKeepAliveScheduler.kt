package tk.glucodata

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the periodic Telegram getMe keep-alive
 * ([TelegramKeepAliveWorker]). The work runs every
 * [KEEP_ALIVE_INTERVAL_MINUTES] minutes, well within Telegram's per-bot
 * rate limits (≈30 req/s, millions/day).
 *
 * Idempotent: re-scheduling replaces the existing unique work via
 * [ExistingPeriodicWorkPolicy.UPDATE], which is what the user-facing
 * configuration change flow relies on.
 */
object TelegramKeepAliveScheduler {

    const val KEEP_ALIVE_INTERVAL_MINUTES = 10L
    private const val UNIQUE_WORK_NAME = "telegram-keep-alive"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<TelegramKeepAliveWorker>(
            KEEP_ALIVE_INTERVAL_MINUTES, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
