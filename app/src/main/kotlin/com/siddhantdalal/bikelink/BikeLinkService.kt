package com.siddhantdalal.bikelink

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
import android.util.Log
import java.io.IOException

/**
 * Keeps the command listener running in the background.
 *
 * It's a foreground service, with an ongoing notification, because that's the only reliable way to keep listening
 * for hours. Without it, both Android and phone makers' battery savers stop background apps.
 */
class BikeLinkService : Service() {

    private var server: CommandServer? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startInForeground()
        } catch (e: RuntimeException) {
            // Android refused the foreground start, for example a background start it doesn't allow.
            // Opening the app starts the service again.
            Log.w(TAG, "Couldn't start in the foreground", e)
            stopSelf()
            return
        }
        val handler = CommandHandler(applicationContext, BikeCode.get(this))
        server = try {
            CommandServer(PORT, handler::handle) { Log.w(TAG, "Command server error", it) }.also { it.start() }
        } catch (e: IOException) {
            Log.e(TAG, "Couldn't listen on port $PORT", e)
            stopSelf()
            null
        }
        isRunning = server != null
        // The Bluetooth link to the iPhone (calls and music) lives as long as this service.
        IphoneLink.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        IphoneLink.stop()
        server?.stop()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val PORT = 8765
        private const val TAG = "BikeLinkService"
        private const val CHANNEL_ID = "ride_link"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, BikeLinkService::class.java))
            } catch (e: IllegalStateException) {
                // Android 12+ only allows this from the foreground, at boot, after an app update, and so on.
                Log.w(TAG, "Android didn't allow starting the service right now", e)
            }
        }
    }
}
