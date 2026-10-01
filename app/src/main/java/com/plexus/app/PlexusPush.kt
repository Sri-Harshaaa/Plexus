package com.plexus.app

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.CancellationException
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

/** A running pass snapshots the durable wake generation; later wakes require another pass. */
internal class PendingSyncWakes(var requested: Long = 0, var completed: Long = 0) {
    private var scheduledId: String? = null
    private var runningId: String? = null
    private var waitingRetry = false

    fun wake(workId: String): Boolean {
        requested++
        if (runningId != null || (scheduledId != null && !waitingRetry)) return false
        scheduledId = workId
        waitingRetry = false
        return true
    }

    fun begin(workId: String): Boolean {
        if (runningId != null || (scheduledId != null && scheduledId != workId)) return false
        runningId = workId
        waitingRetry = false
        return true
    }

    fun finishPass(workId: String, generation: Long): Boolean {
        check(runningId == workId)
        completed = maxOf(completed, generation)
        if (requested > completed) return true
        runningId = null
        scheduledId = null
        waitingRetry = false
        return false
    }

    fun failedPass(workId: String, generation: Long): Boolean {
        check(runningId == workId)
        if (requested > generation) return true
        runningId = null
        waitingRetry = true
        return false
    }

    fun release(workId: String) {
        if (runningId == workId) {
            runningId = null
            waitingRetry = true
        }
    }
}

internal object PlexusSyncWork {
    private const val UNIQUE_NAME = "plexus-pending-sync"
    private const val PREFS = "plexus_sync_wakes"
    private var wakes: PendingSyncWakes? = null

    private fun state(context: Context): PendingSyncWakes = wakes ?: context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
            PendingSyncWakes(it.getLong("requested", 0), it.getLong("completed", 0)).also { state -> wakes = state }
        }

    private fun persist(context: Context, state: PendingSyncWakes) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("requested", state.requested).putLong("completed", state.completed).commit())
    }

    @Synchronized fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<PlexusPendingSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        val state = state(context)
        val schedule = state.wake(request.id.toString())
        persist(context, state)
        if (schedule) {
            // No useful worker is running: replace queued/backoff work, never append a chain.
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
        PlexusPersonalMessaging.wakeForSync()
    }

    @Synchronized fun begin(context: Context, id: String): Boolean = state(context).begin(id)
    @Synchronized fun generation(context: Context): Long = state(context).requested
    @Synchronized fun finishPass(context: Context, id: String, generation: Long): Boolean {
        val state = state(context)
        return state.finishPass(id, generation).also { persist(context, state) }
    }
    @Synchronized fun failedPass(context: Context, id: String, generation: Long): Boolean = state(context).failedPass(id, generation)
    @Synchronized fun release(context: Context, id: String) { state(context).release(id) }
}

class PlexusFirebaseMessagingService : FirebaseMessagingService() {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        PlexusPushToken.save(applicationContext, token)
        PlexusPersonalMessaging.registerPushToken(token)
        PlexusSyncWork.enqueue(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "plexus_sync") {
            PlexusSyncWork.enqueue(applicationContext)
        }
    }
}

class PlexusPendingSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val workId = id.toString()
        if (!PlexusSyncWork.begin(applicationContext, workId)) {
            return Result.success()
        }
        try {
            while (true) {
                val generation = PlexusSyncWork.generation(applicationContext)
                val completed = withTimeoutOrNull(20_000L) {
                    suspendCancellableCoroutine { continuation ->
                        val cancel = PlexusPersonalMessaging.syncPending(applicationContext) { published ->
                            if (continuation.isActive) continuation.resume(published)
                        }
                        continuation.invokeOnCancellation { cancel() }
                    }
                }
                if (completed != true) {
                    if (PlexusSyncWork.failedPass(applicationContext, workId, generation)) continue
                    return Result.retry()
                }
                if (!PlexusSyncWork.finishPass(applicationContext, workId, generation)) {
                    return Result.success()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            PlexusSyncWork.release(applicationContext, workId)
        }
    }
}
