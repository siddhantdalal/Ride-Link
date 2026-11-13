package com.siddhantdalal.bikelink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.IOException

/**
 * Bike Link's background work, for as long as the phone is on: the command listener for the iPhone's Shortcuts, the
 * Bluetooth link for its calls and music, and the call and music overlays over Google Maps.
 *
 * It's a foreground service, with an ongoing notification, because that's the only reliable way to keep running for
 * hours. Without it, both Android and phone makers' battery savers stop background apps.
 */
class BikeLinkService : Service() {

    private var server: CommandServer? = null
    private var overlays: Overlays? = null
    private val iphoneListener: (IphoneLink.State) -> Unit = { overlays?.render(it) }

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
        overlays = Overlays(this)
        IphoneLink.addListener(iphoneListener)
        IphoneLink.start(this)
        isRunning = true
    }

    /** Every start request (opening the app, boot, the Maps helper) also retries the listener if it isn't up. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning && server == null) startServer()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotated, or switched between light and dark: rebuild the overlays to match.
        overlays?.refresh()
    }

    override fun onDestroy() {
        IphoneLink.removeListener(iphoneListener)
        overlays?.release()
        overlays = null
        IphoneLink.stop()
        server?.stop()
        server = null
        isRunning = false
        isListening = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        val handler = CommandHandler(applicationContext, BikeCode.get(this))
        server = try {
            CommandServer(PORT, handler::handle) { Log.w(TAG, "Command server error", it) }.also { it.start() }
        } catch (e: IOException) {
            // Calls and music keep working. The next start request tries again.
            Log.e(TAG, "Couldn't listen on port $PORT", e)
            null
        }
        isListening = server != null
    }

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

        /** The service is up: the iPhone link and the overlays are running. */
        @Volatile
        var isRunning = false
            private set

        /** The command listener is up, so the iPhone's Shortcuts can reach this phone. */
        @Volatile
        var isListening = false
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
