package com.plexus.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

internal fun isPersonalMessagingReady(starting: Boolean, runtimePresent: Boolean): Boolean =
    !starting && runtimePresent

/** Invalidates deferred participation starts when STOP arrives before initialization finishes. */
internal class NetworkParticipationRequests {
    private val generation = AtomicLong()

    fun startWhenReady(action: () -> Unit): () -> Unit {
        val requestedGeneration = generation.incrementAndGet()
        return { if (requestedGeneration == generation.get()) action() }
    }

    fun stop() { generation.incrementAndGet() }
}

/** Process-wide owner for authenticated personal Internet messaging. */
internal object PlexusPersonalMessaging {
    private val main = Handler(Looper.getMainLooper())
    private var runtime: PlexusTransportRuntime? = null
    private var starting = false
    private val startCallbacks = mutableListOf<() -> Unit>()
    private val startFailureCallbacks = mutableListOf<(Throwable) -> Unit>()
    private val participationRequests = NetworkParticipationRequests()

    @Suppress("DEPRECATION")
    fun start(context: Context, onStarted: (() -> Unit)? = null, onFailed: ((Throwable) -> Unit)? = null) {
        Log.d("PlexusSync", "PERSONAL_RUNTIME_START_REQUESTED")
        main.post {
            if (starting) {
                onStarted?.let(startCallbacks::add)
                onFailed?.let(startFailureCallbacks::add)
                return@post
            }
            val current = runtime
            if (isPersonalMessagingReady(starting, current != null)) {
                Log.d("PlexusSync", "PERSONAL_RUNTIME_READY")
                onStarted?.invoke()
                return@post
            }
            if (!ProfileManager.isSetupComplete(context) ||
                ProfileManager.getDisplayName(context).isNullOrBlank()) return@post
            starting = true
            onStarted?.let(startCallbacks::add)
            onFailed?.let(startFailureCallbacks::add)
            val created = try {
                val identity = IdentityManager.getOrCreateIdentity(
                    ProfileManager.getDisplayName(context).orEmpty()
                )
                PlexusTransportRuntime(context.applicationContext, identity, PlexusDatabase.getDatabase(context))
            } catch (failure: Exception) {
                starting = false
                startCallbacks.clear()
                Log.e("PlexusPersonal", "PERSONAL_RUNTIME_START_FAILED")
                startFailureCallbacks.toList().also { startFailureCallbacks.clear() }.forEach { it(failure) }
                return@post
            }
            runtime = created
            created.startPersonalMessaging(
                onStarted = {
                    Log.d("PlexusSync", "PERSONAL_RUNTIME_READY")
                    starting = false
                    startFailureCallbacks.clear()
                    FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                        PlexusPushToken.save(context, token)
                        registerPushToken(token)
                    }
                    startCallbacks.toList().also { startCallbacks.clear() }.forEach { it() }
                },
                onFailed = { failure ->
                    if (runtime !== created) return@startPersonalMessaging
                    Log.e("PlexusPersonal", "PERSONAL_RUNTIME_START_FAILED")
                    // A failed Room observer must not leave live managers behind the next startup.
                    runCatching { created.shutdown() }
                        .onFailure { Log.e("PlexusPersonal", "PERSONAL_RUNTIME_SHUTDOWN_FAILED") }
                    runtime = null
                    starting = false
                    startCallbacks.clear()
                    startFailureCallbacks.toList().also { startFailureCallbacks.clear() }.forEach { it(failure) }
                }
            )
        }
    }

    fun startNetworkParticipation(
        context: Context,
        onStarted: (() -> Unit)? = null,
        onFailed: ((Throwable) -> Unit)? = null
    ) {
        val onReady = participationRequests.startWhenReady {
            runtime?.startNetworkParticipation()
            onStarted?.invoke()
        }
        start(context, onReady, onFailed)
    }

    fun stopNetworkParticipation() {
        participationRequests.stop()
        if (Looper.myLooper() == Looper.getMainLooper()) runtime?.stopNetworkParticipation()
        else main.post { runtime?.stopNetworkParticipation() }
    }

    fun startNearbyIfPermitted() {
        main.post { runtime?.startNearbyIfPermitted() }
    }

    fun setVisibleChat(contactNodeId: String?) {
        main.post { runtime?.setVisibleChat(contactNodeId) }
    }

    fun sendOwnMeshPacket(packet: NearbyMeshPacket): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        return runtime?.sendOwnMeshPacket(packet) ?: false
    }

    fun sendConnectionRequest(packet: ConnectionRequestPacket): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        return runtime?.sendConnectionRequest(packet) ?: false
    }

    fun sendConnectionResponse(packet: ConnectionResponsePacket): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        return runtime?.sendConnectionResponse(packet) ?: false
    }

    fun retryPending() {
        main.post { runtime?.retryPersonalPending() }
    }

    fun syncPending(context: Context, onComplete: (Boolean) -> Unit): () -> Unit {
        val cancelled = AtomicBoolean(false)
        val requestId = java.util.UUID.randomUUID().toString()
        start(context) {
            if (!cancelled.get()) runtime?.syncPersonalPending(requestId, onComplete)
        }
        return {
            cancelled.set(true)
            main.post { runtime?.cancelPersonalSync(requestId) }
            Unit
        }
    }

    fun wakeForSync() { main.post { runtime?.wakeForSync() } }

    fun refreshPeerPolicy() { main.post { runtime?.refreshPeerPolicy() } }

    fun registerPushToken(token: String) {
        main.post { runtime?.registerPushToken(token) }
    }

    fun isNodeVerified(nodeId: String): Boolean =
        Looper.myLooper() == Looper.getMainLooper() && runtime?.isNodeVerified(nodeId) == true

    fun sendBytesToNode(nodeId: String, bytes: ByteArray, priority: NearbyManager.SendPriority): Boolean =
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runtime?.sendBytesToNode(nodeId, bytes, priority) ?: false
        } else false

    fun broadcastBytes(bytes: ByteArray, priority: NearbyManager.SendPriority) {
        if (Looper.myLooper() == Looper.getMainLooper()) runtime?.broadcastBytes(bytes, priority)
    }
}
