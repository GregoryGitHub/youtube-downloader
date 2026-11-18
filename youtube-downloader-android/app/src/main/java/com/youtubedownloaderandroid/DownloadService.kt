package com.youtubedownloaderandroid

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * Foreground service that keeps the process (and the CPU / Wi-Fi) awake while
 * the queue has work, so downloads continue with the screen locked.
 * The queue itself lives in [DownloadQueueManager].
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning = false
            private set
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        // Start foreground immediately to satisfy OS constraints (Android 14+)
        val initialNotification = buildNotification("Inicializando fila de downloads...", 0, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        acquireLocks()

        // If the system restarted us after killing the process, make sure the
        // queue is loaded (Application.onCreate already does this normally).
        DownloadQueueManager.init(applicationContext)

        // Observe queue changes to update notifications
        serviceScope.launch {
            DownloadQueueManager.queue.collect { queue ->
                val activeItems = queue.filter {
                    it.status == DownloadStatus.DOWNLOADING ||
                        it.status == DownloadStatus.FETCHING_INFO ||
                        it.status == DownloadStatus.CONVERTING
                }
                val queuedItems = queue.filter { it.status == DownloadStatus.QUEUED }

                if (activeItems.isEmpty() && queuedItems.isEmpty()) {
                    showFinishedNotification(queue)
                    stopSelf()
                    return@collect
                }

                val done = queue.count { it.status == DownloadStatus.COMPLETED }
                val totalActive = activeItems.size
                val waitingRetry = queuedItems.count { it.nextAttemptAt > System.currentTimeMillis() }

                val textContent = buildString {
                    if (totalActive > 0) {
                        val firstItem = activeItems[0]
                        append(
                            when (firstItem.status) {
                                DownloadStatus.CONVERTING -> "Convertendo: ${firstItem.title}"
                                DownloadStatus.FETCHING_INFO -> "Conectando: ${firstItem.title}"
                                else -> "Baixando: ${firstItem.title} (${(firstItem.progress * 100).toInt()}%)"
                            }
                        )
                        if (totalActive > 1) append(" +${totalActive - 1} em andamento")
                    } else {
                        append("Aguardando nova tentativa...")
                    }
                    append(" • $done/${queue.size} concluídos")
                    if (waitingRetry > 0) append(" • $waitingRetry p/ retentar")
                }

                val avgProgress = if (activeItems.isNotEmpty()) {
                    (activeItems.map { it.progress }.average() * 100).toInt()
                } else 0

                updateNotification(textContent, avgProgress, activeItems.all { it.status == DownloadStatus.FETCHING_INFO })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Recreate the service (and resume the persisted queue) if the system kills it.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+: dataSync services have a daily time budget.
        Log.w(TAG, "Foreground service timeout reached, stopping.")
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        releaseLocks()
        serviceJob.cancel()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YDownloader::Downloads").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed", e)
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "YDownloader::Downloads").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WifiLock failed", e)
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads de Música",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Informa o progresso dos downloads do YDownloader"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private var lastNotificationTime = 0L
    private var lastNotificationText = ""
    private var lastNotificationProgress = -1

    private fun updateNotification(text: String, progress: Int, indeterminate: Boolean) {
        val currentTime = System.currentTimeMillis()
        if (text != lastNotificationText ||
            Math.abs(progress - lastNotificationProgress) >= 5 ||
            currentTime - lastNotificationTime >= 1200
        ) {
            // Notification updates are rate limited by the system; don't spam.
            if (currentTime - lastNotificationTime < 500) return
            lastNotificationTime = currentTime
            lastNotificationText = text
            lastNotificationProgress = progress

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text, progress, indeterminate))
        }
    }

    private fun showFinishedNotification(queue: List<DownloadItem>) {
        if (queue.isEmpty()) return
        val done = queue.count { it.status == DownloadStatus.COMPLETED }
        val failed = queue.count { it.status == DownloadStatus.FAILED }
        val text = if (failed > 0) "$done concluídos, $failed com erro" else "$done downloads concluídos"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloads finalizados")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, notification)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(contentText: String, progress: Int, indeterminate: Boolean): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Baixando Músicas")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, indeterminate)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
