package com.plexus.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun isFreshTransportStart(action: String?, requestedAtNanos: Long, stoppedAtNanos: Long): Boolean =
    action == PlexusForegroundService.ACTION_START && requestedAtNanos > stoppedAtNanos

class PlexusForegroundService : Service() {
    enum class TransportStatus { STOPPED, STARTING, RUNNING }

    companion object {
        const val ACTION_START = "com.plexus.app.action.START_TRANSPORT"
        const val ACTION_STOP = "com.plexus.app.action.STOP_TRANSPORT"
        const val EXTRA_START_REQUESTED_AT = "com.plexus.app.extra.START_REQUESTED_AT"
        private const val CHANNEL_ID = "plexus_transport"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "plexus_transport"
        private const val KEY_ENABLED = "enabled"

        internal fun wasEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
    }

    private val status = MutableStateFlow(TransportStatus.STOPPED)
    private val binder = LocalBinder()
    private var runtime: PlexusTransportRuntime? = null
    private var stopping = false
    private var stoppedAtNanos = Long.MIN_VALUE

    inner class LocalBinder : Binder() {
        val transportStatus: StateFlow<TransportStatus> get() = status

        fun startNearbyIfPermitted() {
            if (!stopping) runtime?.startNearbyIfPermitted()
        }

        fun sendMeshPacket(packet: NearbyMeshPacket): Boolean =
            if (!stopping) runtime?.sendMeshPacket(packet) ?: false else false

        fun sendConnectionRequest(packet: ConnectionRequestPacket): Boolean =
            if (!stopping) runtime?.sendConnectionRequest(packet) ?: false else false

        fun sendConnectionResponse(packet: ConnectionResponsePacket): Boolean =
            if (!stopping) runtime?.sendConnectionResponse(packet) ?: false else false

        fun isNodeVerified(nodeId: String): Boolean =
            if (!stopping) runtime?.isNodeVerified(nodeId) ?: false else false

        fun sendBytesToNode(
            nodeId: String,
            bytes: ByteArray,
            priority: NearbyManager.SendPriority
        ): Boolean =
            if (!stopping) runtime?.sendBytesToNode(nodeId, bytes, priority) ?: false else false

        fun broadcastBytes(bytes: ByteArray, priority: NearbyManager.SendPriority) {
            if (!stopping) runtime?.broadcastBytes(bytes, priority)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).commit()
            shutdown(stopService = true)
            return START_NOT_STICKY
        }
        if (stopping) {
            val freshVisibleStart = isFreshTransportStart(
                intent?.action,
                intent?.getLongExtra(EXTRA_START_REQUESTED_AT, 0L) ?: 0L,
                stoppedAtNanos
            )
            if (!freshVisibleStart) return START_NOT_STICKY
            stopping = false
        }

        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (intent == null) {
            if (!preferences.getBoolean(KEY_ENABLED, false)) {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        } else if (intent.action == ACTION_START) {
            preferences.edit().putBoolean(KEY_ENABLED, true).commit()
        } else {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (runtime != null) return START_STICKY

        try {
            promoteToForeground()
            status.value = TransportStatus.STARTING
            val displayName = ProfileManager.getDisplayName(applicationContext)
                ?.takeIf { ProfileManager.isSetupComplete(applicationContext) }
                ?: error("Plexus profile is not ready")
            val identity = IdentityManager.getOrCreateIdentity(displayName)
            val database = PlexusDatabase.getDatabase(applicationContext)
            val newRuntime = PlexusTransportRuntime(applicationContext, identity, database)
            runtime = newRuntime
            newRuntime.start(
                onStarted = {
                    if (!stopping && runtime === newRuntime) {
                        status.value = TransportStatus.RUNNING
                    }
                },
                onFailed = failureHandler@ { failure ->
                    if (stopping || runtime !== newRuntime) return@failureHandler
                    Log.e("PlexusService", "Transport initialization failed", failure)
                    preferences.edit().putBoolean(KEY_ENABLED, false).commit()
                    shutdown(stopService = true)
                }
            )
        } catch (exception: Exception) {
            Log.e("PlexusService", "Transport startup failed", exception)
            preferences.edit().putBoolean(KEY_ENABLED, false).commit()
            shutdown(stopService = true)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.transport_channel_name),
                NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlexusForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_plexus_service)
            .setContentTitle(getString(R.string.transport_notification_title))
            .setContentText(getString(R.string.transport_notification_text))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.transport_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else {
                0
            }
        startForeground(NOTIFICATION_ID, notification, serviceTypes)
    }

    private fun shutdown(stopService: Boolean) {
        if (!stopping) {
            stopping = true
            stoppedAtNanos = SystemClock.elapsedRealtimeNanos()
            status.value = TransportStatus.STOPPED
            runCatching { runtime?.shutdown() }
                .onFailure { Log.e("PlexusService", "Transport shutdown failed", it) }
            runtime = null
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                .onFailure { Log.e("PlexusService", "Foreground removal failed", it) }
        }
        if (stopService) stopSelf()
    }

    override fun onDestroy() {
        shutdown(stopService = false)
        super.onDestroy()
    }
}
