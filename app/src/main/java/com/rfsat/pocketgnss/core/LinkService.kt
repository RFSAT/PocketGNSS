package com.rfsat.pocketgnss.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.GnssApp
import com.rfsat.pocketgnss.ui.MainActivity
import com.rfsat.pocketgnss.ui.fixLabel
import com.rfsat.pocketgnss.ui.formatAccuracy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the receiver link alive with the screen off while it feeds something: a recording, the NTRIP
 * client, mock location for other apps or the NMEA server. Foreground service with an ongoing notification
 * (fix, accuracy, what runs) and a Stop action; it stops by itself when nothing needs it any more.
 * Same pattern as PocketSpectro's JobService. Not used with the simulator.
 */
class LinkService : Service() {

    companion object {
        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 5301
        const val ACTION_STOP = "com.rfsat.pocketgnss.action.STOP_BACKGROUND"
        private const val WAKE_MS = 10 * 60_000L

        @Volatile var running = false
            private set

        /** Called while the app is in front (Android allows starting foreground services only then). */
        fun start(context: Context, engine: GnssEngine) {
            if (running || !engine.needsBackground()) return
            try { ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java)) } catch (_: Exception) {}
        }

        fun stop(context: Context) { try { context.stopService(Intent(context, LinkService::class.java)) } catch (_: Exception) {} }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private val engine get() = (application as GnssApp).engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notif_channel_help); setShowBadge(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { engine.stopBackground(); stopSelf(); return START_NOT_STICKY }
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketGNSS:link").apply { setReferenceCounted(false) }
        if (scope.coroutineContext[kotlinx.coroutines.Job]?.children?.any() != true) scope.launch {
            while (isActive) {
                if (!engine.needsBackground()) { stopSelf(); break }
                wake?.acquire(WAKE_MS)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                delay(3_000)
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val f = engine.fix.value
        val parts = ArrayList<String>()
        if (engine.recording.value.active) parts.add(getString(R.string.notif_logging, engine.recording.value.fixes))
        if (engine.ntripClient.running) parts.add(getString(R.string.notif_ntrip, engine.ntripClient.ntrip.value.status.name.lowercase()))
        if (engine.store.value.mockLocation) parts.add(getString(R.string.notif_mock))
        if (engine.store.value.nmeaServer) parts.add(getString(R.string.notif_server, engine.server.clientCount.value))
        val title = if (f != null) getString(R.string.notif_title_fix, getString(fixLabel(f.quality)), formatAccuracy(f.horizontalAccuracy))
            else getString(R.string.notif_title_nofix)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, LinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rtk)
            .setContentTitle(title)
            .setContentText(parts.joinToString(" · "))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
