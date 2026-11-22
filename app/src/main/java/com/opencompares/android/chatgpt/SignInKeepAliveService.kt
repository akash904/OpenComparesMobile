package com.opencompares.android.chatgpt

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
import androidx.core.content.ContextCompat
import com.opencompares.android.MainActivity

/**
 * Keeps the app process running while the user signs in to ChatGPT in the browser.
 *
 * The sign-in redirect is answered by a small server inside the app. Once the browser
 * covers the app, Android (and Samsung's app freezer in particular) may freeze it and the
 * redirect would hang. A short foreground service prevents that for the few minutes needed.
 */
class SignInKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    /** Short services get about three minutes; sign-in usually finishes well within that. */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ChatGPT sign-in", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Signing in to ChatGPT")
            .setContentText("Finish in your browser, then return to Open Compares.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "chatgpt_sign_in"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SignInKeepAliveService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SignInKeepAliveService::class.java))
        }
    }
}
