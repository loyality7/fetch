package com.fetch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fetch.core.engine.WebEngine

/**
 * Foreground Service that hosts the [LocalApiServer] on localhost.
 */
public class FetchForegroundService : Service() {

    private var server: LocalApiServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val token = intent?.getStringExtra(EXTRA_TOKEN)
        if (token.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent.getIntExtra(EXTRA_PORT, LocalApiServer.DEFAULT_PORT)

        if (server == null && engineProvider != null) {
            val engine = engineProvider!!.invoke(applicationContext)
            server = LocalApiServer(engine, token, port).also {
                it.start()
            }
            startForeground(NOTIFICATION_ID, createNotification())
        }

        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val channelId = "fetch_service_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Fetch Local Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs the Fetch local API engine server"
            }
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Fetch Engine Active")
            .setContentText("Local engine API server is running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    public companion object {
        public const val ACTION_START: String = "com.fetch.service.action.START"
        public const val ACTION_STOP: String = "com.fetch.service.action.STOP"
        public const val EXTRA_TOKEN: String = "extra_token"
        public const val EXTRA_PORT: String = "extra_port"

        public const val NOTIFICATION_ID: Int = 8471

        /**
         * Provider callback for engine instance initialization.
         */
        public var engineProvider: ((Context) -> WebEngine)? = null

        public fun start(context: Context, token: String, port: Int = LocalApiServer.DEFAULT_PORT) {
            val intent = Intent(context, FetchForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        public fun stop(context: Context) {
            val intent = Intent(context, FetchForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
