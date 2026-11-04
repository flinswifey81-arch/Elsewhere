package com.example.domain.generation

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
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ElsewhereApplication
import com.example.MainActivity
import com.example.R

/** Generation lifecycle is reported from persistence, not merely from HTTP stream termination. */
interface GenerationLifecycleReporter {
    fun started(chatId: String, chatTitle: String)
    fun replySaved(chatId: String, chatTitle: String, speakerName: String, messageId: String)
    fun finished(chatId: String)
}

/**
 * One foreground data-transfer service supports all concurrent, explicitly user-initiated
 * streams. It keeps the app process eligible to run while backgrounded; it does not restart
 * billable requests after process death or promise survival of an Android force stop.
 */
class AndroidGenerationNotifications(
    context: Context,
    private val retainedStores: RetainedChatViewModelStores
) : GenerationLifecycleReporter {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val activeChatTitles = linkedMapOf<String, String>()

    @Volatile private var appVisible = true

    init {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ACTIVE_CHANNEL,
                "Active roleplay generations",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows when Elsewhere is generating roleplay replies." }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                REPLIES_CHANNEL,
                "Completed roleplay replies",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifies you after a generated reply is saved." }
        )
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
    }

    @Synchronized
    fun activeCount(): Int = activeChatTitles.size

    @Synchronized
    override fun started(chatId: String, chatTitle: String) {
        if (activeChatTitles.containsKey(chatId)) return
        val first = activeChatTitles.isEmpty()
        activeChatTitles[chatId] = chatTitle
        if (first) {
            try {
                // Called from the user's active generation action; Android may still deny a
                // foreground-service start if the app was backgrounded during preparation.
                ContextCompat.startForegroundService(
                    appContext, Intent(appContext, GenerationForegroundService::class.java)
                )
            } catch (error: Exception) {
                Log.w(TAG, "Background generation protection unavailable; foreground-only fallback.", error)
            }
        } else {
            updateOngoingNotification()
        }
    }

    @Synchronized
    override fun replySaved(chatId: String, chatTitle: String, speakerName: String, messageId: String) {
        // A stream Done event does NOT mean the response was saved; only this callback does.
        val hasPermission = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!ReplyNotificationPolicy.shouldAlert(
                saved = true,
                chatVisible = retainedStores.isVisible(chatId),
                appVisible = appVisible,
                permissionGranted = hasPermission
            )
        ) return

        val deepLink = Intent(appContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.Builder().scheme("elsewhere").authority("chat").appendPath(chatId).build()
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openChat = PendingIntent.getActivity(
            appContext,
            chatId.hashCode(),
            deepLink,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ready = Notification.Builder(appContext, REPLIES_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${speakerName} replied")
            .setContentText(chatTitle)
            .setContentIntent(openChat)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        try {
            notificationManager.notify(REPLY_TAG, chatId.hashCode(), ready)
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission was revoked before reply delivery.", error)
        }
    }

    @Synchronized
    override fun finished(chatId: String) {
        if (activeChatTitles.remove(chatId) == null) return
        if (activeChatTitles.isEmpty()) {
            appContext.stopService(Intent(appContext, GenerationForegroundService::class.java))
        } else {
            updateOngoingNotification()
        }
    }

    fun dismissReady(chatId: String) {
        notificationManager.cancel(REPLY_TAG, chatId.hashCode())
    }

    @Synchronized
    fun ongoingNotification(): Notification {
        val count = activeChatTitles.size.coerceAtLeast(1)
        return Notification.Builder(appContext, ACTIVE_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Elsewhere is generating")
            .setContentText(if (count == 1) "One reply in progress" else "$count replies in progress")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    private fun updateOngoingNotification() {
        try {
            notificationManager.notify(ACTIVE_NOTIFICATION_ID, ongoingNotification())
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to refresh ongoing generation notification.", error)
        }
    }

    companion object {
        private const val TAG = "ElsewhereGeneration"
        private const val ACTIVE_CHANNEL = "elsewhere_active_generation"
        private const val REPLIES_CHANNEL = "elsewhere_reply_ready"
        const val ACTIVE_NOTIFICATION_ID = 53011
        private const val REPLY_TAG = "elsewhere_reply"
    }
}

class GenerationForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifier = (application as ElsewhereApplication).container.generationNotifications
        if (notifier == null || notifier.activeCount() == 0) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(
            AndroidGenerationNotifications.ACTIVE_NOTIFICATION_ID,
            notifier.ongoingNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ dataSync services have a cumulative background execution limit.
        // Stop promptly to avoid a RemoteServiceException. Generation cancellation/recovery
        // on this path still needs a dedicated OS-timeout integration test.
        Log.w("ElsewhereGeneration", "Android foreground service time limit reached.")
        stopSelf(startId)
    }
}
