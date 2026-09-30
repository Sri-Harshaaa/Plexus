package com.plexus.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal object PlexusPushToken {
    private const val PREFS = "plexus_push"
    private const val TOKEN = "token"

    fun save(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN, token).apply()
    }

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN, null)
}

internal object PlexusSyncWork {
    private const val UNIQUE_NAME = "plexus-pending-sync"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<PlexusPendingSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        enqueue { name, policy ->
            WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
        }
    }

    internal fun enqueue(enqueueUniqueWork: (String, ExistingWorkPolicy) -> Unit) {
        // Append a follow-up even when the current sync has already queried AWS.
        // A failed/cancelled old chain must not prevent a fresh wake from syncing.
        enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }
}

class PlexusFirebaseMessagingService : FirebaseMessagingService() {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        PlexusPushToken.save(applicationContext, token)
        PlexusPersonalMessaging.registerPushToken(token)
        PlexusSyncWork.enqueue(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "plexus_sync") PlexusSyncWork.enqueue(applicationContext)
    }
}

class PlexusPendingSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val completed = withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine { continuation ->
                PlexusPersonalMessaging.syncPending(applicationContext) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        return if (completed != null) Result.success() else Result.retry()
    }
}
