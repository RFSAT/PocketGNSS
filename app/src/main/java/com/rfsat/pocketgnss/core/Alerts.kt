package com.rfsat.pocketgnss.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.protocol.FixQuality

enum class AlertEvent { FIX_LOST, FIX_REGAINED, CORRECTIONS_STALE, CORRECTIONS_BACK }

data class AlertState(val fixLost: Boolean = false, val correctionsStale: Boolean = false)

/**
 * When to warn the operator (on a tractor, the screen is not watched). Arms only once the wanted fix quality has
 * held for [armMs]; then a drop below it, or corrections older than [staleSec], must last [debounceMs] before
 * [AlertEvent.FIX_LOST] / [AlertEvent.CORRECTIONS_STALE] is raised (once), and the recovery likewise before the
 * matching "back" event. Pure logic, unit-tested.
 */
class FixAlertLogic(
    var threshold: FixQuality = FixQuality.RTK_FIXED,
    var staleSec: Double = 10.0,
    private val debounceMs: Long = 3000,
    private val armMs: Long = 5000,
) {
    private var goodSince = -1L
    private var badSince = -1L
    private var armed = false
    private var lost = false
    private var corrSeen = false
    private var staleSince = -1L
    private var freshSince = -1L
    private var stale = false

    /** One update: the current fix quality (NONE when there is no fix) and the correction age (null = none). */
    fun update(now: Long, quality: FixQuality, ageSec: Double?): List<AlertEvent> {
        val ev = ArrayList<AlertEvent>(2)
        val good = quality.rank >= threshold.rank
        if (good) {
            badSince = -1
            if (goodSince < 0) goodSince = now
            if (!armed && now - goodSince >= armMs) armed = true
            if (lost && now - goodSince >= debounceMs) { lost = false; ev.add(AlertEvent.FIX_REGAINED) }
        } else {
            goodSince = -1
            if (badSince < 0) badSince = now
            if (armed && !lost && now - badSince >= debounceMs) { lost = true; ev.add(AlertEvent.FIX_LOST) }
        }
        // Corrections: only watched once they have been received in this session.
        val fresh = ageSec != null && ageSec <= staleSec
        if (fresh) corrSeen = true
        if (corrSeen) {
            if (fresh) {
                staleSince = -1
                if (freshSince < 0) freshSince = now
                if (stale && now - freshSince >= debounceMs) { stale = false; ev.add(AlertEvent.CORRECTIONS_BACK) }
            } else {
                freshSince = -1
                if (staleSince < 0) staleSince = now
                if (!stale && now - staleSince >= debounceMs) { stale = true; ev.add(AlertEvent.CORRECTIONS_STALE) }
            }
        }
        return ev
    }

    val fixLost: Boolean get() = lost
    val correctionsStale: Boolean get() = stale

    fun reset() { goodSince = -1; badSince = -1; armed = false; lost = false; corrSeen = false; staleSince = -1; freshSince = -1; stale = false }
}

/** Sound, vibration and a high-priority notification for [AlertEvent]s. */
class Alerter(private val context: Context) {
    private val tone: ToneGenerator? = try { ToneGenerator(AudioManager.STREAM_ALARM, 90) } catch (_: Exception) { null }

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.alert_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.alert_channel_help)
        })
    }

    fun signal(e: AlertEvent, sound: Boolean, vibrate: Boolean, detail: String) {
        val bad = e == AlertEvent.FIX_LOST || e == AlertEvent.CORRECTIONS_STALE
        if (sound) try {
            tone?.startTone(if (bad) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_PROP_ACK, if (bad) 1500 else 300)
        } catch (_: Exception) {}
        if (vibrate) vibrate(if (bad) longArrayOf(0, 500, 200, 500, 200, 500) else longArrayOf(0, 150))
        val title = context.getString(when (e) {
            AlertEvent.FIX_LOST -> R.string.alert_fix_lost
            AlertEvent.FIX_REGAINED -> R.string.alert_fix_back
            AlertEvent.CORRECTIONS_STALE -> R.string.alert_stale
            AlertEvent.CORRECTIONS_BACK -> R.string.alert_corr_back
        })
        val nm = NotificationManagerCompat.from(context)
        if (!bad) { nm.cancel(if (e == AlertEvent.FIX_REGAINED) ID_FIX else ID_CORR); return }
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(context, 2, Intent(context, com.rfsat.pocketgnss.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            nm.notify(if (e == AlertEvent.FIX_LOST) ID_FIX else ID_CORR, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_rtk).setContentTitle(title).setContentText(detail)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: SecurityException) {}
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        try { v?.vibrate(VibrationEffect.createWaveform(pattern, -1)) } catch (_: Exception) {}
    }

    companion object {
        const val CHANNEL = "alerts"
        private const val ID_FIX = 5401
        private const val ID_CORR = 5402
    }
}
