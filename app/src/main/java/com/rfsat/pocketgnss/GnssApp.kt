package com.rfsat.pocketgnss

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.rfsat.pocketgnss.core.AppLog
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.data.SettingsStore
import java.io.File

class GnssApp : Application() {
    lateinit var engine: GnssEngine
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(File(filesDir, "logs"))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { AppLog.crash(t, e) } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        AppLog.i("App", "Started: PocketGNSS $version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        logPreviousExits()
        // Screen off / on: problems that follow a screen lock show up between these lines.
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> AppLog.i("App", "Screen off")
                    Intent.ACTION_SCREEN_ON -> AppLog.i("App", "Screen on")
                    Intent.ACTION_USER_PRESENT -> AppLog.i("App", "Phone unlocked")
                }
            }
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        engine = GnssEngine(this, SettingsStore(this))
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            AppLog.w("App", "The system is short of memory (level $level): the app may be stopped in the background")
    }

    /**
     * Why the app ended last time (Android 11+): a crash, a native crash, "not responding", the system reclaiming
     * memory... each reported once. For crashes and ANRs the system's own report is added (first lines).
     */
    private fun logPreviousExits() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = getSystemService(ActivityManager::class.java) ?: return
            val prefs = getSharedPreferences("applog", MODE_PRIVATE)
            val seen = prefs.getLong("lastExit", 0L)
            val exits = am.getHistoricalProcessExitReasons(packageName, 0, 10).filter { it.timestamp > seen }.sortedBy { it.timestamp }
            for (x in exits) {
                val (level, what) = when (x.reason) {
                    ApplicationExitInfo.REASON_CRASH -> AppLog.Level.ERROR to "crashed"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> AppLog.Level.ERROR to "crashed in native code (RTK engine or a library)"
                    ApplicationExitInfo.REASON_ANR -> AppLog.Level.ERROR to "stopped responding (ANR)"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> AppLog.Level.WARN to "was stopped by the system for memory"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> AppLog.Level.WARN to "was stopped for excessive resource use"
                    ApplicationExitInfo.REASON_SIGNALED -> AppLog.Level.WARN to "was killed by the system (signal ${x.status})"
                    ApplicationExitInfo.REASON_FREEZER -> AppLog.Level.WARN to "was frozen and stopped by the system"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> AppLog.Level.INFO to "was stopped by the user (force stop or swipe)"
                    ApplicationExitInfo.REASON_USER_STOPPED -> AppLog.Level.INFO to "was stopped by the user"
                    ApplicationExitInfo.REASON_EXIT_SELF -> AppLog.Level.INFO to "exited normally"
                    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> AppLog.Level.INFO to "was updated"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> AppLog.Level.INFO to "was restarted after a permission change"
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> AppLog.Level.WARN to "was stopped because a component it uses ended"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> AppLog.Level.ERROR to "failed to start"
                    ApplicationExitInfo.REASON_OTHER -> AppLog.Level.INFO to "was closed by Android while in the background (normal)"
                    else -> AppLog.Level.INFO to "ended (reason ${x.reason})"
                }
                val desc = x.description?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
                val report = if (x.reason == ApplicationExitInfo.REASON_ANR || x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) try {
                    x.traceInputStream?.bufferedReader()?.use { r -> r.lineSequence().take(60).joinToString("\n") }?.let { "\n$it" } ?: ""
                } catch (_: Exception) { "" } else ""
                val mem = if (x.pss > 0) " · memory ${x.pss / 1024} MB" else ""
                AppLog.add(level, "Exit", "Last run (${AppLog.stamp(x.timestamp)} UTC) $what$desc$mem$report")
            }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("lastExit", it).apply() }
        } catch (e: Exception) { AppLog.w("App", "Previous exit reasons not available: ${e.message}") }
    }
}
