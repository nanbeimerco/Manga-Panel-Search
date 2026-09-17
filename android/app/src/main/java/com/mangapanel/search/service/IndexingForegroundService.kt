package com.mangapanel.search.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mangapanel.search.MainActivity
import com.mangapanel.search.data.engine.IndexingStateManager
import com.mangapanel.search.data.engine.LocalSearchEngine
import com.mangapanel.search.data.local.LocalArchiveManager
import kotlinx.coroutines.*

class IndexingForegroundService : Service() {

    private val TAG = "IndexingService"
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentJob: Job? = null

    companion object {
        const val CHANNEL_ID = "manga_indexing_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "ACTION_START_INDEXING"
        const val ACTION_CANCEL = "ACTION_CANCEL_INDEXING"

        fun startService(context: Context) {
            val intent = Intent(context, IndexingForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, IndexingForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                Log.d(TAG, "Cancel requested via action")
                IndexingStateManager.cancel()
                currentJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (currentJob?.isActive == true) {
                    Log.d(TAG, "Indexing job already active. Ignoring duplicate start request.")
                    return START_NOT_STICKY
                }
                startForegroundWithNotification()
                startIndexingJob()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "漫画事前作成サービス",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "漫画データベース事前作成の進捗状況を通知します"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun getPendingIntentForApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun getCancelPendingIntent(): PendingIntent {
        val intent = Intent(this, IndexingForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getService(this, 1, intent, flags)
    }

    private fun buildNotification(
        title: String,
        contentText: String,
        progress: Int,
        maxProgress: Int,
        isFinished: Boolean = false
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(getPendingIntentForApp())
            .setOngoing(!isFinished)
            .setOnlyAlertOnce(true)

        if (!isFinished) {
            builder.setProgress(maxProgress, progress, maxProgress == 0)
            builder.addAction(android.R.drawable.ic_delete, "中止", getCancelPendingIntent())
        }

        return builder.build()
    }

    private fun startForegroundWithNotification() {
        val initialNotif = buildNotification(
            title = "漫画データベース事前作成中...",
            contentText = "準備中...",
            progress = 0,
            maxProgress = 100
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }
    }

    private fun startIndexingJob() {
        currentJob = serviceScope.launch {
            val localArchiveManager = LocalArchiveManager(applicationContext)
            val localSearchEngine = LocalSearchEngine(applicationContext)

            val archives = localArchiveManager.getArchivesAsync(forceRescan = false).sortedBy { it.name }
            val totalPages = archives.sumOf { it.pageCount }
            if (totalPages == 0) {
                IndexingStateManager.finish()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            IndexingStateManager.start(totalPages)

            var processed = 0
            var lastNotificationUpdateTime = 0L

            for (arc in archives) {
                if (IndexingStateManager.isCancelRequested) break

                for (p in 0 until arc.pageCount) {
                    if (IndexingStateManager.isCancelRequested) break

                    // Resume check: if already cached, fast skip without image decoding
                    val isCached = localSearchEngine.isPageCached(arc, p)
                    if (!isCached) {
                        localSearchEngine.precomputePageFeaturesDiskOnly(arc, p)
                    }

                    processed++
                    val detail = "${arc.name} (${p + 1}/${arc.pageCount})"
                    IndexingStateManager.updateProgress(arc.name, processed, totalPages, detail)

                    // Throttle notification updates (at most every 300ms) to avoid IPC lag
                    val now = System.currentTimeMillis()
                    if (now - lastNotificationUpdateTime > 300 || processed == totalPages) {
                        lastNotificationUpdateTime = now
                        val notif = buildNotification(
                            title = "漫画事前作成中 (${(processed * 100) / totalPages}%)",
                            contentText = detail,
                            progress = processed,
                            maxProgress = totalPages
                        )
                        val manager = getSystemService(NotificationManager::class.java)
                        manager?.notify(NOTIFICATION_ID, notif)
                    }
                }
            }

            val manager = getSystemService(NotificationManager::class.java)
            if (IndexingStateManager.isCancelRequested) {
                Log.d(TAG, "Indexing cancelled by user. Discarding notification.")
                manager?.cancel(NOTIFICATION_ID)
            } else {
                IndexingStateManager.finish()
                val completedNotif = buildNotification(
                    title = "事前作成が完了しました！",
                    contentText = "全 $totalPages ページのインデックスが完了しました。",
                    progress = totalPages,
                    maxProgress = totalPages,
                    isFinished = true
                )
                manager?.notify(NOTIFICATION_ID, completedNotif)
            }

            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
