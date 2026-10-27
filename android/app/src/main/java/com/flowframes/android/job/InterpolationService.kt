package com.flowframes.android.job

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.flowframes.android.MainActivity
import com.flowframes.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service running the interpolation queue so a job survives the app going to the
 * background and the screen turning off.
 */
class InterpolationService : Service() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationPercent = -1
    private var lastNotificationAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                AppGraph.jobs.addLog("Annulation demandée")
                AppGraph.jobs.requestCancel()
            }

            else -> startProcessing()
        }
        return START_NOT_STICKY
    }

    private fun startProcessing() {
        if (worker?.isActive == true) return
        AppGraph.jobs.resetCancel()
        startForegroundCompat(buildNotification("Préparation…", 0, true))
        acquireWakeLock()
        worker = scope.launch { runQueue() }
    }

    private suspend fun runQueue() {
        AppGraph.jobs.running.value = true
        var completed = 0
        try {
            while (true) {
                if (AppGraph.jobs.cancelRequested) break
                val next = AppGraph.jobs.nextQueued() ?: break
                AppGraph.jobs.update(next.id) { it.copy(status = JobStatus.RUNNING, progress = 0f, error = null) }
                notifyProgress(next.displayName, 0)
                AppGraph.jobs.addLog("▶ ${next.displayName}")

                try {
                    val job = Interpolator(
                        context = this@InterpolationService,
                        settings = AppGraph.settings.current,
                        log = { line -> AppGraph.jobs.addLog(line) },
                        onProgress = { progress, detail ->
                            AppGraph.jobs.update(next.id) {
                                it.copy(progress = progress.coerceIn(0f, 1f), detail = detail)
                            }
                            notifyProgress("${next.displayName} · $detail", (progress * 100).toInt())
                        },
                        isCancelled = { AppGraph.jobs.cancelRequested }
                    )
                    val result = job.process(next.uri, next.displayName)
                    AppGraph.jobs.update(next.id) {
                        it.copy(
                            status = JobStatus.DONE,
                            progress = 1f,
                            detail = "${result.frames} images → ${result.label}",
                            outputName = result.label,
                            outputLabel = result.path ?: result.uri.toString()
                        )
                    }
                    completed++
                } catch (cancelled: JobCancelledException) {
                    AppGraph.jobs.update(next.id) {
                        it.copy(status = JobStatus.CANCELLED, detail = "Annulé", progress = 0f)
                    }
                    AppGraph.jobs.addLog("■ ${next.displayName} annulé")
                    break
                } catch (oom: OutOfMemoryError) {
                    val message = "Mémoire insuffisante — réduisez la résolution de traitement (par exemple 720p ou 480p) dans les réglages"
                    AppGraph.jobs.update(next.id) {
                        it.copy(status = JobStatus.FAILED, error = message, detail = "Échec")
                    }
                    AppGraph.jobs.addLog("✖ ${next.displayName}: $message")
                } catch (t: Throwable) {
                    val message = t.message ?: t.javaClass.simpleName
                    AppGraph.jobs.update(next.id) {
                        it.copy(status = JobStatus.FAILED, error = message, detail = "Échec")
                    }
                    AppGraph.jobs.addLog("✖ ${next.displayName}: $message")
                }
            }
        } finally {
            AppGraph.jobs.running.value = false
            releaseWakeLock()
            withContext(Dispatchers.Main) {
                stopForegroundCompat()
                stopSelf()
            }
            if (completed > 0) {
                AppGraph.jobs.addLog("File terminée: $completed vidéo(s) traitée(s)")
            }
        }
    }

    private fun notifyProgress(text: String, percent: Int) {
        val now = System.currentTimeMillis()
        if (percent == lastNotificationPercent || (now - lastNotificationAt < 900 && percent < 100)) return
        lastNotificationPercent = percent
        lastNotificationAt = now
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIF_ID, buildNotification(text, percent, true)) }
    }

    private fun buildNotification(text: String, percent: Int, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, InterpolationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flowframes)
            .setContentTitle("FlowFrames")
            .setContentText(text)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Annuler", cancel)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlowFrames:interpolation").apply {
            setReferenceCounted(false)
            runCatching { acquire(6 * 60 * 60 * 1000L) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "interpolation"
        const val NOTIF_ID = 42
        const val ACTION_START = "com.flowframes.android.START"
        const val ACTION_CANCEL = "com.flowframes.android.CANCEL"

        fun start(context: Context) {
            val intent = Intent(context, InterpolationService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, InterpolationService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
