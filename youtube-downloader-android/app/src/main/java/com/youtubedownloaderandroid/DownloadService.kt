package com.youtubedownloaderandroid

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Start foreground immediately to satisfy OS constraints (Android 14+)
        val initialNotification = buildNotification("Inicializando fila de downloads...", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
        
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
                    // All downloads finished or queue is empty, stop service
                    stopSelf()
                    return@collect
                }

                // Update notification text and progress bar
                val totalActive = activeItems.size
                val totalRemaining = queuedItems.size + totalActive
                
                val textContent = if (totalActive > 0) {
                    val firstItem = activeItems[0]
                    val statusText = when (firstItem.status) {
                        DownloadStatus.CONVERTING -> "Convertendo: ${firstItem.title}"
                        DownloadStatus.FETCHING_INFO -> "Conectando: ${firstItem.title}"
                        else -> "Baixando: ${firstItem.title} (${(firstItem.progress * 100).toInt()}%)"
                    }
                    if (totalRemaining > 1) {
                        "$statusText (+${totalRemaining - 1} restantes)"
                    } else {
                        statusText
                    }
                } else {
                    "Aguardando na fila (${totalRemaining} restantes)..."
                }

                // Average progress of active items
                val avgProgress = if (activeItems.isNotEmpty()) {
                    (activeItems.map { it.progress }.average() * 100).toInt()
                } else {
                    0
                }

                updateNotification(textContent, avgProgress)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
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

    private fun updateNotification(text: String, progress: Int) {
        val currentTime = System.currentTimeMillis()
        if (text != lastNotificationText || 
            Math.abs(progress - lastNotificationProgress) >= 5 || 
            currentTime - lastNotificationTime >= 1200) {
            
            lastNotificationTime = currentTime
            lastNotificationText = text
            lastNotificationProgress = progress
            
            val manager = getSystemService(NotificationManager::class.java)
            val notification = buildNotification(text, progress)
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String, progress: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, pendingIntentFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Baixando Músicas")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0 && contentText.contains("Conectando"))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
