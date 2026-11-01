package com.vscode.desktop.standalone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the app process (and therefore the PRoot children) alive.
 *
 *  - MODE_PROVISION : first-run extraction/setup (wake lock with a timeout)
 *  - MODE_X11       : Mode 1 — full desktop, unthrottled (wake lock held while the service runs)
 *  - MODE_DIRECT    : Mode 2 — `code serve-web`, battery-optimised: NO wake lock, and a heartbeat that lets the
 *                     guest-side watcher hibernate the server after 10 idle minutes.
 */
class LinuxRuntimeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentMode = MODE_PROVISION
    private var deadTicks = 0
    private val manager: ProotProcessManager by lazy { ProotProcessManager(applicationContext) }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (Mode2Server.isProcessAlive) {
                deadTicks = 0
                if (Mode2Server.clientActive) Mode2Server.touchHeartbeat(manager)
                handler.postDelayed(this, HEARTBEAT_MS)
            } else {
                deadTicks++
                if (deadTicks < 6) {
                    handler.postDelayed(this, HEARTBEAT_MS) // server may still be starting
                } else {
                    deadTicks = 0
                    stopIfIdle()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // Enter the foreground right away: stopping a service that was started with startForegroundService() before
        // it called startForeground() crashes the app (ForegroundServiceDidNotStartInTimeException).
        startForeground(NOTIFICATION_ID, buildNotification(this, currentMode, null, null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Mode2Server.stop()
            Mode1Session.stop()
            handler.removeCallbacks(heartbeat)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: currentMode
        currentMode = mode
        startForeground(NOTIFICATION_ID, buildNotification(this, mode, null, null))
        applyWakeLock(mode)

        if (mode == MODE_DIRECT) {
            deadTicks = 0
            handler.removeCallbacks(heartbeat)
            handler.post(heartbeat)
        }
        return START_NOT_STICKY
    }

    private fun applyWakeLock(mode: String) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        when (mode) {
            MODE_DIRECT -> if (!Mode1Session.isAlive) releaseWakeLock()
            MODE_X11 -> {
                releaseWakeLock()
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            else -> {
                releaseWakeLock()
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                    setReferenceCounted(false)
                    acquire(PROVISION_WAKE_TIMEOUT_MS)
                }
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopIfIdle() {
        if (!ProotProcessManager.isAnySessionAlive() && !StandaloneProvisioner.isRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "vscode_standalone_runtime"
        const val ACTION_STOP = "com.pocket.vscode.STOP"
        const val EXTRA_MODE = "mode"
        const val MODE_PROVISION = "provision"
        const val MODE_X11 = "x11"
        const val MODE_DIRECT = "direct"

        private const val NOTIFICATION_ID = 101
        private const val WAKE_TAG = "PocketVSCode::EmbeddedLinuxLock"
        private const val HEARTBEAT_MS = 20_000L
        private const val PROVISION_WAKE_TIMEOUT_MS = 90L * 60L * 1000L

        @Volatile
        private var lastNotifiedPercent = -1

        fun start(context: Context, mode: String) {
            val intent = Intent(context, LinuxRuntimeService::class.java).putExtra(EXTRA_MODE, mode)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // e.g. started from the background on API 31+: the work still runs, only the keep-alive is missing
                AppLog.add("Warning: could not start the keep-alive service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinuxRuntimeService::class.java))
            lastNotifiedPercent = -1
        }

        /** Updates the ongoing notification with a determinate progress bar (first-run setup). */
        fun updateProgress(context: Context, percent: Int, text: String) {
            if (percent == lastNotifiedPercent) return
            lastNotifiedPercent = percent
            try {
                ensureChannel(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(context, MODE_PROVISION, "$percent% · $text", percent))
            } catch (_: Exception) {
            }
        }

        private fun buildNotification(context: Context, mode: String, text: String?, progress: Int?): Notification {
            val target = if (mode == MODE_DIRECT) WorkbenchActivity::class.java else MainActivity::class.java
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val title = when (mode) {
                MODE_DIRECT -> "Pocket VS Code · Mode 2 (web host)"
                MODE_X11 -> "Pocket VS Code · Mode 1 (desktop)"
                else -> "Pocket VS Code · setting up"
            }
            val defaultText = when (mode) {
                MODE_DIRECT -> "Battery saver: server hibernates after 10 idle minutes"
                MODE_X11 -> "Embedded ARM64 Linux & Electron runtime active"
                else -> "Extracting Linux and VS Code (one time)…"
            }
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text ?: defaultText)
                .setSmallIcon(R.drawable.ic_stat_vscode)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)

            if (progress != null) builder.setProgress(100, progress, false)

            if (mode != MODE_PROVISION) {
                val stop = PendingIntent.getService(
                    context,
                    1,
                    Intent(context, LinuxRuntimeService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(0, "Stop", stop)
            }
            return builder.build()
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Embedded VS Code Runtime",
                    NotificationManager.IMPORTANCE_LOW
                )
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
        }
    }
}
