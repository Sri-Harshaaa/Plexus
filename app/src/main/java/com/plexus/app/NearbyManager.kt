package com.plexus.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.security.MessageDigest
import android.util.Base64
import org.json.JSONObject
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy


class NearbyManager(

    context: Context,

    private val localEndpointName: String,

    private val listener: Listener

) {


    interface Listener {


        fun onAdvertisingStarted()


        fun onDiscoveryStarted()


        fun onEndpointFound(
            endpointId: String,
            endpointName: String
        )


        fun onEndpointLost(
            endpointId: String
        )


        fun onConnectionInitiated(
            endpointId: String,
            endpointName: String,
            authenticationDigits: String
        )


        fun onConnected(
            endpointId: String
        )


        fun onDisconnected(
            endpointId: String
        )


        fun onBytesReceived(
            endpointId: String,
            bytes: ByteArray
        )


        fun onError(
            message: String,
            exception: Exception? = null
        )
    }



    companion object {


        private const val TAG =
            "PlexusNearby"


        /*
         * A challenge should only live for a short time.
         *
         * This uses OUR local clock only, so it does not depend
         * on both phones having perfectly synchronized clocks.
         */
        private const val AUTH_CHALLENGE_LIFETIME_MS =
            120_000L


        /*
         * Nearby may keep discovery state cached across an established
         * connection. When a transport such as WIFI_LAN dies, we restart
         * advertising/discovery after the disconnect so Play Services can
         * rediscover the peer over the currently available medium.
         */
        private const val RECOVERY_DEBOUNCE_MS =
            750L


        /*
         * Small gap between stop*() and start*() so the previous Nearby
         * session has time to release its medium-specific resources.
         */
        private const val RECOVERY_RESTART_SETTLE_MS =
            350L


        private const val START_WATCHDOG_MS =
            10_000L


        private const val BASE_START_BACKOFF_MS =
            2_000L


        private const val MAX_START_BACKOFF_MS =
            30_000L
        private const val IDLE_DISCOVERY_REFRESH_MS = 60_000L
    }



    private data class PendingAuthentication(

        val challengerNodeId: String,

        val responderNodeId: String,

        val challenge: String,

        val createdAt: Long
    )



    private val appContext =
        context.applicationContext


    private val connectionsClient:
            ConnectionsClient =
        Nearby.getConnectionsClient(
            appContext
        )


    private val serviceId =
        appContext.packageName


    private val strategy =
        Strategy.P2P_CLUSTER



    /*
     * =========================================================
     * CONNECTION STATE
     * =========================================================
     */

    private val connectedEndpoints =
        mutableSetOf<String>()


    private val pendingEndpoints =
        mutableSetOf<String>()


    private val discoveredEndpointNames =
        mutableMapOf<String, String>()


    /*
     * Tracks whether Plexus wants Nearby discovery/advertising to remain
     * alive. stopEverything() disables this before tearing the manager down,
     * which prevents disconnect callbacks from accidentally resurrecting
     * Nearby while the Activity is being disposed.
     */
    private var maintainNearbySessions =
        false


    private var advertisingActive =
        false


    private val advertisingStart = StartAttemptGate()
    private val advertisingGeneration get() = advertisingStart.generation


    private var advertisingRetryCount =
        0


    private var scheduledAdvertisingWatchdog:
            Runnable? =
        null


    private var scheduledAdvertisingRetry:
            Runnable? =
        null


    private var discoveryActive =
        false


    private val discoveryStart = StartAttemptGate()
    private val discoveryGeneration get() = discoveryStart.generation


    private var discoveryRetryCount =
        0


    private var scheduledDiscoveryWatchdog:
            Runnable? =
        null


    private var scheduledDiscoveryRetry:
            Runnable? =
        null


    /*
     * A single peer can disappear through several callbacks in quick
     * succession. Debounce recovery so those events collapse into one
     * advertising/discovery reset instead of producing a restart storm.
     */
    private val recoveryHandler =
        Handler(
            Looper.getMainLooper()
        )


    private var recoveryEpoch =
        0L
    private val mediumMonitor = NearbyMediumMonitor(appContext, recoveryHandler) { reason ->
        if (maintainNearbySessions) scheduleNearbyRecovery(reason, 2_000L)
    }


    private var scheduledRecoveryRunnable:
            Runnable? =
        null


    private var scheduledRestartRunnable:
            Runnable? =
        null
    private var idleRefreshRunnable: Runnable? = null
    private fun armIdleRefresh() {
        if (idleRefreshRunnable != null) return
        val refresh = object : Runnable {
            override fun run() {
                if (!maintainNearbySessions || idleRefreshRunnable !== this) return
                if (verifiedEndpoints.isEmpty() && pendingEndpoints.isEmpty() &&
                    scheduledRecoveryRunnable == null && scheduledRestartRunnable == null) {
                    scheduleNearbyRecovery("idle discovery refresh", 0L)
                }
                recoveryHandler.postDelayed(this, IDLE_DISCOVERY_REFRESH_MS)
            }
        }
        idleRefreshRunnable = refresh
        recoveryHandler.postDelayed(refresh, IDLE_DISCOVERY_REFRESH_MS)
    }


    /*
     * =========================================================
     * PRIORITIZED OUTBOUND BYTES QUEUE
     * =========================================================
     *
     * Nearby Connections accepts many sendPayload() calls immediately and
     * may queue them internally. That is fine for bulk relay traffic, but it
     * means a later control packet (connection request / response / auth)
     * can otherwise sit behind a large anti-packet backlog.
     *
     * Plexus therefore keeps ONE in-flight BYTES payload per endpoint and
     * orders the remaining work itself:
     *
     *     CONTROL    -> auth + connection control
     *     MESSAGE    -> user chat payloads
     *     BACKGROUND -> anti-packets + relay propagation
     *
     * A new CONTROL packet cannot interrupt the single payload already on
     * the wire, but it jumps ahead of every queued MESSAGE/BACKGROUND item.
     */
    enum class SendPriority(
        internal val rank: Int
    ) {
        CONTROL(0),
        MESSAGE(1),
        BACKGROUND(2)
    }


    private val outboxes = mutableMapOf<String, EndpointOutbox>()
    private val transferWatchdogs = mutableMapOf<String, Runnable>()
    private val connectionWatchdogs = mutableMapOf<String, Runnable>()
    private var nextConnectionSession = 0L
    private val connectionSessions = mutableMapOf<String, Long>()
    private val recentVaccineTransfers = LinkedHashMap<String, Long>()
    private val receiveBudgets = mutableMapOf<String, ReceiveBudget>()
    private val backgroundOffsets = mutableMapOf<String, Int>()
    fun nextBackgroundOffset(peer: String, count: Int, step: Int = 4): Int {
        if (count == 0) return 0
        val offset = (backgroundOffsets[peer] ?: 0) % count
        backgroundOffsets[peer] = (offset + step) % count
        return offset
    }
    fun hasBackgroundCapacity(peer: String): Boolean = nodeIdToEndpoint[peer]?.let {
        (outboxes[it]?.backgroundSize ?: 8) < 8
    } ?: false
    private data class ReceiveBudget(var tokens: Double = 128.0, var updatedAt: Long = SystemClock.elapsedRealtime())
    private var lastRecoveryAt = -5_000L

    private val peerRetries = PeerRetryState { SystemClock.elapsedRealtime() }
    private val peerCooldownState get() = peerRetries.cooldown
    private val attemptCandidates = mutableMapOf<String, PeerRetryState.Candidate>()
    private val scheduledCooldownRetries = mutableMapOf<String, Runnable>()

    /*
     * =========================================================
     * PLEXUS IDENTITY MAPPING
     * =========================================================
     *
     * Nearby:
     *
     *     RGVJ
     *
     * Plexus:
     *
     *     cff6b3fb...
     *
     * Identity packets establish the claimed mapping.
     *
     * IMPORTANT:
     *
     * Mapping alone does NOT mean authenticated.
     */

    private val peerRoutes = PeerRoutes()
    private val endpointToNodeId = peerRoutes.claims
    private val nodeIdToEndpoint = peerRoutes.verified



    /*
     * =========================================================
     * AUTHENTICATION STATE
     * =========================================================
     */

    private val pendingAuthentications =
        mutableMapOf<String, PendingAuthentication>()


    /*
     * Endpoint IDs that successfully proved ownership of their
     * Plexus signing private key during THIS connection.
     */
    private val verifiedEndpoints =
        mutableSetOf<String>()



    /*
     * =========================================================
     * PAYLOAD CALLBACK
     * =========================================================
     */

    private fun payloadCallback(session: Long) =
        object :
            PayloadCallback() {


            override fun onPayloadReceived(
                endpointId: String,
                payload: Payload
            ) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    if (!recoveryHandler.post { receivePayload(endpointId, payload, session) }) {
                        Log.w(TAG, "Nearby payload could not be posted to Main")
                    }
                    return
                }
                receivePayload(endpointId, payload, session)
            }

            private fun receivePayload(endpointId: String, payload: Payload, session: Long) {
                if (connectionSessions[endpointId] != session) {
                    return
                }
                if (endpointId !in connectedEndpoints) {
                    return
                }
                when (
                    payload.type
                ) {


                    Payload.Type.BYTES -> {


                        val bytes =
                            try { payload.asBytes() } catch (failure: Throwable) {
                                Log.e(TAG, "Nearby payload read failed")
                                throw failure
                            }


                        if (
                            bytes == null || bytes.size > EndpointOutbox.MAX_BYTES
                        ) {


                            Log.d(
                                TAG,
                                "EMPTY BYTES PAYLOAD from $endpointId"
                            )


                            return
                        }


                        Log.d(
                            TAG,
                            "BYTES RECEIVED ✅ from " +
                                    "$endpointId | " +
                                    "${bytes.size} bytes"
                        )


                        val budget = receiveBudgets.getOrPut(endpointId) { ReceiveBudget() }
                        val now = SystemClock.elapsedRealtime()
                        budget.tokens = minOf(128.0, budget.tokens + (now - budget.updatedAt) / 50.0)
                        budget.updatedAt = now
                        if (budget.tokens < 1.0) {
                            return
                        }
                        budget.tokens -= 1.0
                        try {
                            listener.onBytesReceived(
                                endpointId = endpointId,
                                bytes = bytes
                            )
                        } catch (failure: Throwable) {
                            Log.e(TAG, "Nearby payload listener failed")
                            throw failure
                        }
                    }


                    else -> {


                        Log.d(
                            TAG,
                            "UNSUPPORTED PAYLOAD TYPE from " +
                                    "$endpointId -> ${payload.type}"
                        )
                    }
                }
            }



            override fun onPayloadTransferUpdate(
                endpointId: String,
                update: PayloadTransferUpdate
            ) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    recoveryHandler.post { onPayloadTransferUpdate(endpointId, update) }
                    return
                }



                if (connectionSessions[endpointId] != session) {
                    return
                }
                val box = outboxes[endpointId] ?: run {
                    return
                }
                val transfer = box.inFlight ?: run {
                    return
                }
                if (transfer.payloadId != update.payloadId) {
                    return
                }

                when (
                    update.status
                ) {


                    PayloadTransferUpdate.Status.SUCCESS -> {


                        Log.d(
                            TAG,
                            "PAYLOAD TRANSFER SUCCESS ✅ -> " +
                                    "$endpointId | " +
                                    "payload=${update.payloadId}"
                        )


                        finishOutgoingPayload(endpointId, transfer)
                    }


                    PayloadTransferUpdate.Status.FAILURE,
                    PayloadTransferUpdate.Status.CANCELED -> {
                        failOutgoingPayload(endpointId, transfer, "terminal status=${update.status}")
                    }

                    PayloadTransferUpdate.Status.IN_PROGRESS -> {

                        /*
                         * BYTES payloads are small and do not need progress
                         * UI right now. Keep the state until a terminal
                         * SUCCESS / FAILURE / CANCELED update arrives.
                         */
                    }
                }
            }
        }



    /*
     * =========================================================
     * CONNECTION LIFECYCLE
     * =========================================================
     */

    private fun connectionLifecycleCallback(requestEndpoint: String? = null, requestSession: Long? = null,
                                            advertisingOwner: Long? = null) =
        object : ConnectionLifecycleCallback() {
            private val ownedSessions = mutableMapOf<String, Long>()


            override fun onConnectionInitiated(
                endpointId: String,
                connectionInfo: ConnectionInfo
            ) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    recoveryHandler.post { onConnectionInitiated(endpointId, connectionInfo) }
                    return
                }

                if (!maintainNearbySessions) return
                if (requestEndpoint != null && (endpointId != requestEndpoint ||
                    connectionSessions[endpointId] != requestSession)) return
                if (advertisingOwner != null && advertisingOwner != advertisingGeneration) return
                // Nearby lifecycle events carry only an endpoint ID. Never reuse that ID in
                // the same callback owner: an old disconnect would otherwise be ambiguous.
                if (ownedSessions.containsKey(endpointId)) {
                    if (ownedSessions[endpointId] != connectionSessions[endpointId]) {
                        // A stale callback's ID can now belong to a healthy
                        // replacement session; do not reject that endpoint.
                        Log.w(TAG, "STALE CONNECTION CALLBACK -> endpoint=$endpointId")
                        scheduleNearbyRecovery("endpoint ID reused by retired callback -> $endpointId")
                    }
                    return
                }
                if (connectionSessions.size >= 16 && !connectionSessions.containsKey(endpointId)) {
                    connectionsClient.rejectConnection(endpointId)
                    return
                }
                if (endpointId in connectedEndpoints) {
                    connectedEndpoints.remove(endpointId)
                    clearAuthentication(endpointId)
                    removeIdentityMapping(endpointId)
                    listener.onDisconnected(endpointId)
                }
                receiveBudgets[endpointId] = ReceiveBudget()
                if (endpointId !in attemptCandidates) {
                    val candidate = peerRetries.found(endpointId, connectionInfo.endpointName)
                    if (candidate == null || !peerRetries.begin(candidate)) {
                        runCatching { connectionsClient.rejectConnection(endpointId) }
                        return
                    }
                    attemptCandidates[endpointId] = candidate
                }
                val session = ++nextConnectionSession
                ownedSessions[endpointId] = session
                connectionSessions[endpointId] = session
                clearOutgoingForEndpoint(endpointId)
                armConnectionWatchdog(endpointId)



                pendingEndpoints.add(
                    endpointId
                )


                val endpointName = discoveredEndpointNames[endpointId] ?: connectionInfo.endpointName


                Log.d(
                    TAG,
                    "CONNECTION INITIATED -> " +
                            "$endpointName | " +
                            "endpoint=$endpointId | " +
                            "auth=${connectionInfo.authenticationDigits}"
                )


                listener.onConnectionInitiated(

                    endpointId =
                        endpointId,

                    endpointName =
                        endpointName,

                    authenticationDigits =
                        connectionInfo.authenticationDigits
                )


                /*
                 * Development behaviour:
                 *
                 * Nearby transport connections are auto-accepted.
                 *
                 * Plexus cryptographic authentication happens
                 * AFTER transport connection establishment.
                 */
                connectionsClient
                    .acceptConnection(

                        endpointId,

                        payloadCallback(session)
                    )
                    .addOnSuccessListener {
                        if (connectionSessions[endpointId] != session) return@addOnSuccessListener
                        Log.d(
                            TAG,
                            "ACCEPTED -> $endpointId"
                        )
                    }
                    .addOnFailureListener { exception ->
                        if (!maintainNearbySessions || connectionSessions[endpointId] != session) return@addOnFailureListener


                        Log.e(
                            TAG,
                            "ACCEPT CONNECTION FAILED -> $endpointId",
                            exception
                        )


                        listener.onError(

                            message =
                                "ACCEPT CONNECTION FAILED -> $endpointId",

                            exception =
                                exception
                        )


                        val status = (exception as? com.google.android.gms.common.api.ApiException)?.statusCode
                        invalidateEndpoint(endpointId, "accept connection failure", status != null &&
                            NearbyFailureClassifier.forStatus(status) == NearbyRecoveryPolicy.MEDIUM_REFRESH)
                    }
            }



            override fun onConnectionResult(
                endpointId: String,
                result: ConnectionResolution
            ) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    recoveryHandler.post { onConnectionResult(endpointId, result) }
                    return
                }

                if (!maintainNearbySessions || ownedSessions[endpointId] != connectionSessions[endpointId] ||
                    !connectionSessions.containsKey(endpointId)) return



                pendingEndpoints.remove(
                    endpointId
                )


                when (
                    result.status.statusCode
                ) {


                    com.google.android.gms.common.api
                        .CommonStatusCodes.SUCCESS -> {


                        if (endpointId in connectedEndpoints) return
                        clearOutgoingForEndpoint(endpointId)
                        outboxes[endpointId] = EndpointOutbox(connectionSessions.getValue(endpointId))
                        connectedEndpoints.add(endpointId)


                        /*
                         * A fresh transport connection requires
                         * fresh authentication.
                         */
                        clearAuthentication(
                            endpointId
                        )


                        Log.d(
                            TAG,
                            "CONNECTED ✅ -> $endpointId"
                        )


                        listener.onConnected(
                            endpointId
                        )
                    }


                    else -> {
                        Log.e(
                            TAG,
                            "CONNECTION REJECTED/FAILED -> " +
                                    "$endpointId | " +
                                    "status=${result.status.statusCode}"
                        )


                        listener.onError(

                            message =
                                "CONNECTION REJECTED/FAILED -> " +
                                        "$endpointId | " +
                                        "status=${result.status.statusCode}"
                        )


                        invalidateEndpoint(endpointId, "connection result failure",
                            NearbyFailureClassifier.forStatus(result.status.statusCode) == NearbyRecoveryPolicy.MEDIUM_REFRESH)
                    }
                }
            }



            override fun onDisconnected(
                endpointId: String
            ) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    recoveryHandler.post { onDisconnected(endpointId) }
                    return
                }

                if (!maintainNearbySessions || ownedSessions[endpointId] != connectionSessions[endpointId] ||
                    !connectionSessions.containsKey(endpointId)) return
                invalidateEndpoint(endpointId, "endpoint disconnected", recoverMedium = true)
            }
        }



    /*
     * =========================================================
     * DISCOVERY
     * =========================================================
     */

    private fun endpointDiscoveryCallback(generation: Long) =
        object :
            EndpointDiscoveryCallback() {


            override fun onEndpointFound(
                endpointId: String,
                info: DiscoveredEndpointInfo
            ) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    recoveryHandler.post { onEndpointFound(endpointId, info) }
                    return
                }

                if (!maintainNearbySessions) return
                if (generation != discoveryGeneration) return
                val candidate = peerRetries.found(endpointId, info.endpointName) ?: return
                discoveredEndpointNames.keys.removeAll { old ->
                    old != endpointId && NearbyEndpointName.key(discoveredEndpointNames.getValue(old)) == candidate.key
                }


                discoveredEndpointNames[endpointId] = info.endpointName


                Log.d(
                    TAG,
                    "FOUND -> " +
                            "${info.endpointName} | " +
                            endpointId
                )


                listener.onEndpointFound(

                    endpointId =
                        endpointId,

                    endpointName =
                        info.endpointName
                )


                /*
                 * Ignore our own advertisement.
                 */
                if (
                    info.endpointName ==
                    localEndpointName
                ) {


                    Log.d(
                        TAG,
                        "IGNORING SELF -> ${info.endpointName}"
                    )


                    return
                }

                val endpointName = info.endpointName
                if (peerCooldownState.isCoolingDown(candidate.key)) {
                    Log.d(TAG, "FOUND SKIPPED - PEER IN COOLDOWN -> $endpointName")
                    val expireAt = peerCooldownState.getExpireAt(candidate.key)
                    if (expireAt != null) {
                        scheduleCooldownRetry(endpointId, endpointName, expireAt)
                    }
                    return
                }

                /*
                 * Deterministic initiator selection.
                 *
                 * This prevents both phones simultaneously
                 * requesting the same connection.
                 */
                if (
                    NearbyEndpointName.initiates(localEndpointName, info.endpointName)
                ) {


                    Log.d(
                        TAG,
                        "INITIATOR -> ${info.endpointName}"
                    )


                    connect(

                        endpointId =
                            endpointId,

                        endpointName =
                            info.endpointName
                    )

                } else {


                    Log.d(
                        TAG,
                        "WAITING FOR PEER -> ${info.endpointName}"
                    )
                }
            }



            override fun onEndpointLost(
                endpointId: String
            ) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    recoveryHandler.post { onEndpointLost(endpointId) }
                    return
                }

                if (!maintainNearbySessions || generation != discoveryGeneration) return
                val endpointName = discoveredEndpointNames.remove(
                    endpointId
                )
                val lostCandidate = peerRetries.candidate(endpointId)
                peerRetries.lost(endpointId)
                lostCandidate?.let { scheduledCooldownRetries.remove(it.key)?.let(recoveryHandler::removeCallbacks) }

                Log.d(
                    TAG,
                    "LOST -> $endpointId"
                )


                listener.onEndpointLost(
                    endpointId
                )
            }
        }



    /*
     * =========================================================
     * ADVERTISING
     * =========================================================
     */

    fun startAdvertising() {
        mediumMonitor.start()
        armIdleRefresh()


        maintainNearbySessions =
            true


        ensureAdvertising()
    }



    /*
     * =========================================================
     * DISCOVERY START
     * =========================================================
     */

    fun startDiscovery() {
        mediumMonitor.start()
        armIdleRefresh()


        maintainNearbySessions =
            true


        ensureDiscovery()
    }



    /*
     * =========================================================
     * ADVERTISING / DISCOVERY INTERNAL START
     * =========================================================
     */

    private fun ensureAdvertising() {


        if (
            !maintainNearbySessions
        ) {

            return
        }


        if (
            advertisingActive ||
            scheduledAdvertisingWatchdog != null ||
            scheduledAdvertisingRetry != null
        ) {


            return
        }


        val generation = advertisingStart.begin()


        Log.d(
            TAG,
            "ADVERTISING START REQUESTED generation=$generation"
        )


        val options =
            AdvertisingOptions
                .Builder()
                .setStrategy(
                    strategy
                )
                .build()


        val watchdogRunnable =
            Runnable {


                if (
                    !maintainNearbySessions ||
                    generation != advertisingGeneration || advertisingActive || scheduledAdvertisingWatchdog == null
                ) {

                    return@Runnable
                }


                Log.e(
                    TAG,
                    "ADVERTISING START TIMEOUT generation=$generation"
                )
                if (!advertisingStart.complete(generation)) return@Runnable
                advertisingStart.invalidate()


                scheduledAdvertisingWatchdog =
                    null


                connectionsClient
                    .stopAdvertising()


                scheduleAdvertisingRetry()
            }


        scheduledAdvertisingWatchdog =
            watchdogRunnable


        recoveryHandler
            .postDelayed(

                watchdogRunnable,

                START_WATCHDOG_MS
            )


        try { connectionsClient
            .startAdvertising(

                localEndpointName,

                serviceId,

                connectionLifecycleCallback(advertisingOwner = generation),

                options
            )
            .addOnSuccessListener {


                if (
                    !maintainNearbySessions ||
                    generation != advertisingGeneration || !advertisingStart.complete(generation)
                ) {

                    return@addOnSuccessListener
                }


                scheduledAdvertisingWatchdog
                    ?.let { runnable ->

                        recoveryHandler
                            .removeCallbacks(
                                runnable
                            )
                    }


                scheduledAdvertisingWatchdog =
                    null


                advertisingActive =
                    true


                advertisingRetryCount = 0


                Log.d(
                    TAG,
                    "Advertising started generation=$generation"
                )


                listener.onAdvertisingStarted()
            }
            .addOnFailureListener { exception ->


                if (
                    !maintainNearbySessions ||
                    generation != advertisingGeneration || !advertisingStart.complete(generation)
                ) {

                    return@addOnFailureListener
                }


                scheduledAdvertisingWatchdog
                    ?.let { runnable ->

                        recoveryHandler
                            .removeCallbacks(
                                runnable
                            )
                    }


                scheduledAdvertisingWatchdog =
                    null


                advertisingActive = false


                Log.e(
                    TAG,
                    "ADVERTISING START FAILED generation=$generation",
                    exception
                )


                listener.onError(

                    message =
                        "Advertising failed",

                    exception =
                        exception
                )


                scheduleAdvertisingRetry()
            }
        } catch (exception: Exception) {
            if (advertisingStart.complete(generation)) {
                scheduledAdvertisingWatchdog?.let(recoveryHandler::removeCallbacks)
                scheduledAdvertisingWatchdog = null
                advertisingActive = false
                Log.e(TAG, "ADVERTISING START THREW generation=$generation", exception)
                scheduleAdvertisingRetry()
            }
        }
    }


    private fun scheduleAdvertisingRetry() {


        if (
            !maintainNearbySessions
        ) {

            return
        }


        advertisingRetryCount = minOf(advertisingRetryCount + 1, 10)


        val backoff =
            (BASE_START_BACKOFF_MS * (1L shl (advertisingRetryCount - 1)))
                .coerceAtMost(
                    MAX_START_BACKOFF_MS
                )


        val retryGeneration =
            advertisingGeneration


        val retryRunnable =
            Runnable {


                if (
                    !maintainNearbySessions ||
                    retryGeneration != advertisingGeneration
                ) {

                    return@Runnable
                }


                scheduledAdvertisingRetry =
                    null


                ensureAdvertising()
            }


        scheduledAdvertisingRetry =
            retryRunnable


        recoveryHandler
            .postDelayed(

                retryRunnable,

                backoff
            )
    }



    private fun ensureDiscovery() {


        if (
            !maintainNearbySessions
        ) {

            return
        }


        if (
            discoveryActive ||
            scheduledDiscoveryWatchdog != null ||
            scheduledDiscoveryRetry != null
        ) {


            return
        }


        val generation = discoveryStart.begin()


        Log.d(
            TAG,
            "DISCOVERY START REQUESTED generation=$generation"
        )


        val options =
            DiscoveryOptions
                .Builder()
                .setStrategy(
                    strategy
                )
                .build()


        val watchdogRunnable =
            Runnable {


                if (
                    !maintainNearbySessions ||
                    generation != discoveryGeneration || discoveryActive || scheduledDiscoveryWatchdog == null
                ) {

                    return@Runnable
                }


                Log.e(
                    TAG,
                    "DISCOVERY START TIMEOUT generation=$generation"
                )


                scheduledDiscoveryWatchdog =
                    null
                if (!discoveryStart.complete(generation)) return@Runnable
                discoveryStart.invalidate()


                connectionsClient
                    .stopDiscovery()


                scheduleDiscoveryRetry()
            }


        scheduledDiscoveryWatchdog =
            watchdogRunnable


        recoveryHandler
            .postDelayed(

                watchdogRunnable,

                START_WATCHDOG_MS
            )


        try { connectionsClient
            .startDiscovery(

                serviceId,

                endpointDiscoveryCallback(generation),

                options
            )
            .addOnSuccessListener {


                if (
                    !maintainNearbySessions ||
                    generation != discoveryGeneration || !discoveryStart.complete(generation)
                ) {

                    return@addOnSuccessListener
                }


                scheduledDiscoveryWatchdog
                    ?.let { runnable ->

                        recoveryHandler
                            .removeCallbacks(
                                runnable
                            )
                    }


                scheduledDiscoveryWatchdog =
                    null


                discoveryActive =
                    true


                discoveryRetryCount = 0


                Log.d(
                    TAG,
                    "Discovery started generation=$generation"
                )


                listener.onDiscoveryStarted()
            }
            .addOnFailureListener { exception ->


                if (
                    !maintainNearbySessions ||
                    generation != discoveryGeneration || !discoveryStart.complete(generation)
                ) {

                    return@addOnFailureListener
                }


                scheduledDiscoveryWatchdog
                    ?.let { runnable ->

                        recoveryHandler
                            .removeCallbacks(
                                runnable
                            )
                    }


                scheduledDiscoveryWatchdog =
                    null


                discoveryActive = false


                Log.e(
                    TAG,
                    "DISCOVERY START FAILED generation=$generation",
                    exception
                )


                listener.onError(

                    message =
                        "Discovery failed",

                    exception =
                        exception
                )


                scheduleDiscoveryRetry()
            }
        } catch (exception: Exception) {
            if (discoveryStart.complete(generation)) {
                scheduledDiscoveryWatchdog?.let(recoveryHandler::removeCallbacks)
                scheduledDiscoveryWatchdog = null
                discoveryActive = false
                Log.e(TAG, "DISCOVERY START THREW generation=$generation", exception)
                scheduleDiscoveryRetry()
            }
        }
    }


    private fun scheduleDiscoveryRetry() {


        if (
            !maintainNearbySessions
        ) {

            return
        }


        discoveryRetryCount = minOf(discoveryRetryCount + 1, 10)


        val backoff =
            (BASE_START_BACKOFF_MS * (1L shl (discoveryRetryCount - 1)))
                .coerceAtMost(
                    MAX_START_BACKOFF_MS
                )


        val retryGeneration =
            discoveryGeneration


        val retryRunnable =
            Runnable {


                if (
                    !maintainNearbySessions ||
                    retryGeneration != discoveryGeneration
                ) {

                    return@Runnable
                }


                scheduledDiscoveryRetry =
                    null


                ensureDiscovery()
            }


        scheduledDiscoveryRetry =
            retryRunnable


        recoveryHandler
            .postDelayed(

                retryRunnable,

                backoff
            )
    }



    /*
     * =========================================================
     * NEARBY SESSION RECOVERY
     * =========================================================
     *
     * A transport handoff can look like:
     *
     *     WIFI_LAN dies
     *         ->
     *     onDisconnected()
     *         ->
     *     old discovery session still remembers the peer
     *         ->
     *     no fresh onEndpointFound()
     *
     * Resetting advertising/discovery gives Nearby a fresh medium scan.
     * Existing connected endpoints are NOT stopped here.
     */

    fun restartNearby(
        reason: String =
            "manual"
    ) {


        if (
            !maintainNearbySessions
        ) {


            Log.d(
                TAG,
                "NEARBY RECOVERY SKIPPED - MANAGER STOPPED | $reason"
            )


            return
        }


        scheduleNearbyRecovery(
            reason =
                reason
        )
    }



    private fun scheduleNearbyRecovery(
        reason: String,
        delayMs: Long = RECOVERY_DEBOUNCE_MS
    ) {


        if (
            !maintainNearbySessions
        ) {

            return
        }


        if (scheduledRecoveryRunnable != null || scheduledRestartRunnable != null) return
        recoveryEpoch +=
            1L


        val epoch =
            recoveryEpoch


        scheduledRecoveryRunnable
            ?.let { runnable ->

                recoveryHandler
                    .removeCallbacks(
                        runnable
                    )
            }


        scheduledRestartRunnable
            ?.let { runnable ->

                recoveryHandler
                    .removeCallbacks(
                        runnable
                    )
            }


        val recoveryRunnable =
            Runnable {


                if (
                    !maintainNearbySessions ||
                    epoch != recoveryEpoch
                ) {

                    return@Runnable
                }


                Log.d(
                    TAG,
                    "NEARBY RECOVERY RESET 🔄 -> $reason"
                )
                scheduledRecoveryRunnable = null
                lastRecoveryAt = SystemClock.elapsedRealtime()


                /*
                 * Discovered endpoint IDs belong to the previous discovery
                 * view of the world. Connected endpoints and authenticated
                 * mappings for still-live connections are deliberately
                 * preserved.
                 *
                 * onDisconnected()/failure callbacks already remove the
                 * endpoint that triggered recovery from pendingEndpoints.
                 *
                 * Do NOT clear the whole pending set here: another peer may
                 * legitimately be in the middle of a connection handshake.
                 */
                discoveredEndpointNames.clear()
                peerRetries.clearCandidates()
                scheduledCooldownRetries.values.forEach(recoveryHandler::removeCallbacks)
                scheduledCooldownRetries.clear()


                stopAdvertising()

                stopDiscovery()


                val restartRunnable =
                    Runnable {


                        if (
                            !maintainNearbySessions ||
                            epoch != recoveryEpoch
                        ) {

                            return@Runnable
                        }


                        Log.d(
                            TAG,
                            "NEARBY RECOVERY RESTART 🔄 -> $reason"
                        )
                        scheduledRestartRunnable = null


                        ensureAdvertising()

                        ensureDiscovery()
                    }


                scheduledRestartRunnable =
                    restartRunnable


                recoveryHandler
                    .postDelayed(

                        restartRunnable,

                        RECOVERY_RESTART_SETTLE_MS
                    )
            }


        scheduledRecoveryRunnable =
            recoveryRunnable


        Log.d(
            TAG,
            "NEARBY RECOVERY SCHEDULED 🔄 -> $reason"
        )


        recoveryHandler
            .postDelayed(

                recoveryRunnable,

                maxOf(delayMs, 5_000L - (SystemClock.elapsedRealtime() - lastRecoveryAt))
            )
    }



    private fun scheduleCooldownRetry(endpointId: String, endpointName: String, expireAt: Long) {
        val candidate = peerRetries.candidate(endpointId) ?: return
        val plan = PeerRetryState.Retry(candidate, expireAt)
        scheduledCooldownRetries.remove(candidate.key)?.let(recoveryHandler::removeCallbacks)
        val retryRunnable = object : Runnable {
            override fun run() {
                if (scheduledCooldownRetries[candidate.key] !== this) return
                scheduledCooldownRetries.remove(candidate.key)
                if (!maintainNearbySessions || !peerRetries.ready(plan)) return
                if (endpointId in connectedEndpoints || endpointId in pendingEndpoints) return
                if (NearbyEndpointName.initiates(localEndpointName, endpointName)) {
                    Log.d(TAG, "PEER RETRY -> endpoint=$endpointId name=$endpointName candidate=${candidate.generation}")
                    connect(endpointId, endpointName)
                } else {
                    // A passive node also needs a fresh discovery/advertisement if
                    // its initiator disappeared. Never disconnect other live peers.
                    scheduleNearbyRecovery("passive peer retry -> $endpointId")
                }
            }
        }
        scheduledCooldownRetries[candidate.key] = retryRunnable
        recoveryHandler.postDelayed(retryRunnable, maxOf(0L, expireAt - SystemClock.elapsedRealtime()))
    }

    /*
     * =========================================================
     * CONNECT
     * =========================================================
     */

    private fun connect(
        endpointId: String,
        endpointName: String
    ) {
        if (!maintainNearbySessions || connectedEndpoints.size + pendingEndpoints.size >= 16) return


        if (
            connectedEndpoints.contains(
                endpointId
            )
        ) {

            return
        }


        if (
            pendingEndpoints.contains(
                endpointId
            )
        ) {

            return
        }


        val candidate = peerRetries.candidate(endpointId) ?: return
        if (!peerRetries.begin(candidate)) return
        attemptCandidates[endpointId] = candidate
        pendingEndpoints.add(
            endpointId
        )


        val session = ++nextConnectionSession
        connectionSessions[endpointId] = session
        armConnectionWatchdog(endpointId)
        val requestWatchdog = connectionWatchdogs[endpointId]

        Log.d(
            TAG,
            "REQUEST CONNECTION -> " +
                    "$endpointName | $endpointId | attempt=$session candidate=${candidate.generation} local=$localEndpointName"
        )


        connectionsClient
            .requestConnection(

                localEndpointName,

                endpointId,

                connectionLifecycleCallback(endpointId, session)
            )
            .addOnSuccessListener {

                if (!maintainNearbySessions || connectionSessions[endpointId] != session) return@addOnSuccessListener
                Log.d(
                    TAG,
                    "REQUEST SENT -> $endpointId"
                )
            }
            .addOnFailureListener { exception ->
                if (!maintainNearbySessions || connectionWatchdogs[endpointId] !== requestWatchdog) return@addOnFailureListener

                Log.e(
                    TAG,
                    "CONNECTION ATTEMPT FAILED -> endpoint=$endpointId",
                    exception
                )

                listener.onError(

                    message =
                        "REQUEST CONNECTION FAILED -> $endpointId",

                    exception =
                        exception
                )

                val status = (exception as? com.google.android.gms.common.api.ApiException)?.statusCode
                invalidateEndpoint(endpointId, "request connection failure", status != null &&
                    NearbyFailureClassifier.forStatus(status) == NearbyRecoveryPolicy.MEDIUM_REFRESH)
            }
    }



    /*
     * =========================================================
     * RAW ENDPOINT SEND
     * =========================================================
     *
     * Used for protocol messages such as:
     *
     * identity
     * auth challenge
     * auth proof
     *
     * because authentication may not yet be complete.
     */

    fun sendBytes(
        endpointId: String,
        bytes: ByteArray,
        priority: SendPriority =
            SendPriority.CONTROL
    ) {


        enqueueBytes(

            endpointId =
                endpointId,

            bytes =
                bytes,

            priority =
                priority
        )
    }



    /*
     * =========================================================
     * REGISTER ANNOUNCED IDENTITY
     * =========================================================
     *
     * This means:
     *
     * endpoint RGVJ CLAIMS to be node abc...
     *
     * It does NOT mean we trust that claim yet.
     */

    fun registerPeerIdentity(endpointId: String, nodeId: String) {
        if (endpointId !in connectedEndpoints || nodeId.isBlank()) return
        if (endpointToNodeId[endpointId] != nodeId) clearAuthentication(endpointId)
        peerRoutes.claim(endpointId, nodeId)
        Log.d(TAG, "IDENTITY CLAIM MAPPED -> $endpointId <-> ${nodeId.take(8)}")
    }


    /*
     * =========================================================
     * BEGIN AUTHENTICATION
     * =========================================================
     *
     * Local node A challenges remote node B.
     */

    fun beginAuthentication(

        endpointId: String,

        challengerNodeId: String,

        responderNodeId: String

    ): Boolean {


        if (
            !connectedEndpoints.contains(
                endpointId
            )
        ) {


            Log.d(
                TAG,
                "AUTH SKIPPED - ENDPOINT NOT CONNECTED -> $endpointId"
            )


            return false
        }


        val mappedNodeId =
            endpointToNodeId[
                endpointId
            ]


        if (
            mappedNodeId !=
            responderNodeId
        ) {


            Log.e(
                TAG,
                "AUTH SKIPPED - IDENTITY MAPPING MISMATCH -> " +
                        "$endpointId | expected=${responderNodeId.take(8)}"
            )


            return false
        }


        /*
         * New challenge means any previous proof for this
         * endpoint is no longer sufficient.
         */
        verifiedEndpoints.remove(
            endpointId
        )


        val challengePacket =
            NearbyAuthPacket
                .createChallenge(

                    challengerNodeId =
                        challengerNodeId,

                    responderNodeId =
                        responderNodeId
                )


        pendingAuthentications[
            endpointId
        ] =
            PendingAuthentication(

                challengerNodeId =
                    challengePacket.challengerNodeId,

                responderNodeId =
                    challengePacket.responderNodeId,

                challenge =
                    challengePacket.challenge,

                createdAt =
                    System.currentTimeMillis()
            )


        sendBytes(

            endpointId =
                endpointId,

            bytes =
                challengePacket.toBytes()
        )


        Log.d(
            TAG,
            "AUTH CHALLENGE SENT 🔐 -> " +
                    "$endpointId | " +
                    responderNodeId.take(8)
        )


        return true
    }



    /*
     * =========================================================
     * VERIFY AUTH PROOF
     * =========================================================
     *
     * Called when the peer returns:
     *
     *      challenge + signature
     *
     * We verify the signature using the public signing key
     * already stored for that contact.
     */

    fun verifyAuthenticationProof(

        endpointId: String,

        proofPacket: NearbyAuthPacket,

        publicSigningKey: String

    ): Boolean {


        if (
            proofPacket.kind !=
            NearbyAuthPacket.Kind.PROOF
        ) {


            return false
        }


        val pending =
            pendingAuthentications[
                endpointId
            ]
                ?: run {


                    Log.e(
                        TAG,
                        "AUTH PROOF REJECTED - " +
                                "NO PENDING CHALLENGE -> $endpointId"
                    )


                    return false
                }



        /*
         * Challenge expiry is based only on OUR clock.
         */
        val age =
            System.currentTimeMillis() -
                    pending.createdAt


        if (
            age < 0L ||
            age >
            AUTH_CHALLENGE_LIFETIME_MS
        ) {


            pendingAuthentications.remove(
                endpointId
            )


            Log.e(
                TAG,
                "AUTH PROOF REJECTED - " +
                        "CHALLENGE EXPIRED -> $endpointId"
            )


            return false
        }



        /*
         * The proof must correspond EXACTLY to the challenge
         * we issued.
         */
        if (
            proofPacket.challengerNodeId !=
            pending.challengerNodeId ||
            proofPacket.responderNodeId !=
            pending.responderNodeId ||
            proofPacket.challenge !=
            pending.challenge
        ) {


            Log.e(
                TAG,
                "AUTH PROOF REJECTED - " +
                        "CHALLENGE MISMATCH -> $endpointId"
            )


            return false
        }



        /*
         * The endpoint currently claiming this connection must
         * still be the responder named by the challenge.
         */
        val mappedNodeId =
            endpointToNodeId[
                endpointId
            ]


        if (
            mappedNodeId !=
            proofPacket.responderNodeId
        ) {


            Log.e(
                TAG,
                "AUTH PROOF REJECTED - " +
                        "ENDPOINT IDENTITY CHANGED -> $endpointId"
            )


            return false
        }



        val signature =
            proofPacket.signature
                ?: return false



        val authenticationMessage =
            IdentityCrypto
                .buildAuthenticationMessage(

                    challengerNodeId =
                        proofPacket
                            .challengerNodeId,

                    responderNodeId =
                        proofPacket
                            .responderNodeId,

                    challenge =
                        proofPacket
                            .challenge
                )



        val valid =
            IdentityCrypto
                .verify(

                    data =
                        authenticationMessage,

                    signatureBase64 =
                        signature,

                    publicKeyBase64 =
                        publicSigningKey
                )



        /*
         * Once a matching proof has been attempted,
         * consume the challenge.
         *
         * The same proof cannot simply be replayed later.
         */
        pendingAuthentications.remove(
            endpointId
        )



        if (
            !valid
        ) {


            verifiedEndpoints.remove(
                endpointId
            )


            Log.e(
                TAG,
                "AUTH FAILED ❌ -> " +
                        "$endpointId | " +
                        proofPacket.responderNodeId.take(8)
            )


            return false
        }



        connectionWatchdogs.remove(endpointId)?.let(recoveryHandler::removeCallbacks)
        // Only a valid live challenge proof may replace a route for this node.
        val previousEndpoint = peerRoutes.promote(endpointId)
        if (previousEndpoint != null) {
            attemptCandidates.remove(previousEndpoint)
            connectionWatchdogs.remove(previousEndpoint)?.let(recoveryHandler::removeCallbacks)
            clearAuthentication(previousEndpoint)
            clearOutgoingForEndpoint(previousEndpoint)
            connectedEndpoints.remove(previousEndpoint)
            connectionSessions.remove(previousEndpoint)
            receiveBudgets.remove(previousEndpoint)
            runCatching { connectionsClient.disconnectFromEndpoint(previousEndpoint) }
            listener.onDisconnected(previousEndpoint)
        }
        verifiedEndpoints.add(
            endpointId
        )

        val candidate = attemptCandidates[endpointId]
        if (candidate != null) {
            peerRetries.authenticated(candidate)
            scheduledCooldownRetries.remove(candidate.key)?.let(recoveryHandler::removeCallbacks)
            Log.d(TAG, "PEER CONNECTION BACKOFF RESET -> verified=${proofPacket.responderNodeId.take(8)} name=${candidate.name}")
        }


        Log.d(
            TAG,
            "AUTH VERIFIED ✅ 🔐 -> " +
                    "$endpointId <-> " +
                    proofPacket.responderNodeId.take(8)
        )


        return true
    }



    /*
     * =========================================================
     * AUTHENTICATION QUERIES
     * =========================================================
     */

    fun isEndpointVerified(
        endpointId: String
    ): Boolean {


        return verifiedEndpoints.contains(
            endpointId
        )
    }



    fun isNodeVerified(
        nodeId: String
    ): Boolean {


        val endpointId =
            nodeIdToEndpoint[
                nodeId
            ]
                ?: return false


        return verifiedEndpoints.contains(
            endpointId
        )
    }



    /*
     * =========================================================
     * DIRECT PLEXUS SEND
     * =========================================================
     *
     * 1:1 application data is ONLY allowed after the peer has
     * proven possession of its signing private key.
     */

    fun sendBytesToNode(
        nodeId: String,
        bytes: ByteArray,
        priority: SendPriority =
            SendPriority.MESSAGE
    ): Boolean {


        val endpointId =
            nodeIdToEndpoint[
                nodeId
            ]


        if (
            endpointId == null
        ) {


            Log.d(
                TAG,
                "DIRECT SEND SKIPPED - " +
                        "NODE NOT CONNECTED -> " +
                        nodeId.take(8)
            )


            return false
        }


        if (
            !verifiedEndpoints.contains(
                endpointId
            )
        ) {


            Log.d(
                TAG,
                "DIRECT SEND SKIPPED - " +
                        "PEER NOT VERIFIED 🔒 -> " +
                        nodeId.take(8)
            )


            return false
        }


        return enqueueBytes(

            endpointId =
                endpointId,

            bytes =
                bytes,

            priority =
                priority
        )
    }



    /*
     * =========================================================
     * LOOKUPS
     * =========================================================
     */

    fun getNodeIdForEndpoint(
        endpointId: String
    ): String? {


        return endpointToNodeId[
            endpointId
        ]
    }



    fun getEndpointForNodeId(
        nodeId: String
    ): String? {


        return nodeIdToEndpoint[
            nodeId
        ]
    }



    /*
     * =========================================================
     * BROADCAST
     * =========================================================
     *
     * Retained for future mesh forwarding.
     *
     * Direct chat should NOT use this.
     */

    fun broadcastBytes(
        bytes: ByteArray,
        priority: SendPriority =
            SendPriority.BACKGROUND,
        excludeEndpointId: String? = null
    ) {


        val endpoints =
            connectedEndpoints
                .toList()


        if (
            endpoints.isEmpty()
        ) {


            Log.d(
                TAG,
                "BROADCAST SKIPPED - NO CONNECTED PEERS"
            )


            return
        }


        var queuedCount =
            0


        for (
        endpointId in
        endpoints
        ) {
            if (endpointId == excludeEndpointId || !verifiedEndpoints.contains(endpointId)) continue


            val queued =
                enqueueBytes(

                    endpointId =
                        endpointId,

                    bytes =
                        bytes,

                    priority =
                        priority
                )


            if (
                queued
            ) {

                queuedCount +=
                    1
            }
        }


        Log.d(
            TAG,
            "BROADCAST ENQUEUED -> " +
                    "$queuedCount/${endpoints.size} peer(s) | " +
                    "priority=$priority"
        )
    }



    /*
     * =========================================================
     * PRIORITIZED OUTGOING QUEUE INTERNALS
     * =========================================================
     */

    private fun enqueueBytes(
        endpointId: String,
        bytes: ByteArray,
        priority: SendPriority
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (bytes.isEmpty() || bytes.size > EndpointOutbox.MAX_BYTES) return false
        if (!connectedEndpoints.contains(endpointId)) return false
        val box = outboxes[endpointId] ?: return false
        val now = SystemClock.elapsedRealtime()
        val json = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        val kind = json?.optString("type").orEmpty()
        val digest = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes), Base64.NO_WRAP)
        val key = when (kind) {
            "delivery_anti_packet" -> "anti:$digest"
            "mesh_message" -> "mesh:${json!!.optString("sourceNodeId")}:${json.optString("destinationNodeId")}:${json.optString("packetId")}:$digest"
            else -> digest
        }
        val peerKey = endpointToNodeId[endpointId]?.let { "$it:$key" }
        if (priority == SendPriority.BACKGROUND && key.startsWith("anti:") &&
            peerKey != null && (recentVaccineTransfers[peerKey] ?: Long.MIN_VALUE) > now) {
            return true
        }
        val oldSize = box.size
        val accepted = box.offer(key, bytes, priority.rank, SystemClock.elapsedRealtime())
        if (!accepted) {
            Log.w(TAG, "OUTBOUND BACKPRESSURE -> $endpointId | priority=$priority | queued=${box.size} | durable retry retained")
            return false
        }
        if (box.size > oldSize) Log.d(TAG, "OUTBOUND ENQUEUED -> $endpointId | priority=$priority | kind=$kind | queued=${box.size}")
        drainOutboundQueue(endpointId)
        return true
    }

    private fun drainOutboundQueue(endpointId: String) {
        if (!connectedEndpoints.contains(endpointId)) return
        val box = outboxes[endpointId] ?: return
        val queued = box.poll() ?: return
        val payload = Payload.fromBytes(queued.bytes)
        val transfer = box.start(payload.id, queued)
        // Arm before sendPayload: a hung request Task also needs a bounded lifetime.
        val watchdog = Runnable {
            if (outboxes[endpointId] === box && box.matches(transfer)) {
                Log.w(TAG, "PAYLOAD WATCHDOG TIMEOUT -> $endpointId | payload=${payload.id} | session=${box.session}")
                failOutgoingPayload(endpointId, transfer, "missing terminal callback")
            }
        }
        transferWatchdogs[endpointId] = watchdog
        recoveryHandler.postDelayed(watchdog, EndpointOutbox.TRANSFER_TIMEOUT_MS)
        try {
            connectionsClient.sendPayload(endpointId, payload)
                .addOnSuccessListener {
                    if (outboxes[endpointId] === box && box.matches(transfer)) {
                        Log.d(TAG, "PAYLOAD DISPATCHED TO NEARBY -> $endpointId | payload=${payload.id} | priority=${queued.priority}")
                    }
                }
                .addOnFailureListener { exception ->
                    failOutgoingPayload(endpointId, transfer, "send request failed: ${exception.javaClass.simpleName}")
                }
                .addOnCanceledListener {
                    failOutgoingPayload(endpointId, transfer, "send request canceled")
                }
        } catch (exception: Exception) {
            failOutgoingPayload(endpointId, transfer, "send request threw: ${exception.javaClass.simpleName}")
        }
    }

    private fun finishOutgoingPayload(endpointId: String, transfer: EndpointOutbox.Transfer) {
        val box = outboxes[endpointId] ?: return
        if (!box.finish(transfer, SystemClock.elapsedRealtime())) return
        if (transfer.item.key.startsWith("anti:")) {
            endpointToNodeId[endpointId]?.let { node ->
                recentVaccineTransfers["$node:${transfer.item.key}"] = SystemClock.elapsedRealtime() + 300_000L
                while (recentVaccineTransfers.size > 4096) recentVaccineTransfers.remove(recentVaccineTransfers.keys.first())
            }
        }
        transferWatchdogs.remove(endpointId)?.let(recoveryHandler::removeCallbacks)
        // Post instead of recursively draining if Google completes requests inline.
        recoveryHandler.post { drainOutboundQueue(endpointId) }
    }

    private fun failOutgoingPayload(endpointId: String, transfer: EndpointOutbox.Transfer, reason: String) {
        val box = outboxes[endpointId] ?: return
        if (!box.matches(transfer)) return
        Log.w(TAG, "PAYLOAD TRANSFER ABORTED -> $endpointId | payload=${transfer.payloadId} | $reason")
        // Invalidate before cancelPayload: Google may deliver cancellation immediately.
        invalidateEndpoint(endpointId, "stale transfer", recoverMedium = true)
        runCatching { connectionsClient.cancelPayload(transfer.payloadId) }
    }

    private fun armConnectionWatchdog(endpointId: String) {
        connectionWatchdogs.remove(endpointId)?.let(recoveryHandler::removeCallbacks)
        lateinit var watchdog: Runnable
        watchdog = Runnable {
            if (maintainNearbySessions && connectionWatchdogs[endpointId] === watchdog &&
                endpointId !in verifiedEndpoints) {
                Log.w(TAG, "CONNECTION AUTH TIMEOUT -> $endpointId")
                invalidateEndpoint(endpointId, "connection/auth timeout")
            }
        }
        connectionWatchdogs[endpointId] = watchdog
        recoveryHandler.postDelayed(watchdog, 30_000L)
    }

    private fun invalidateEndpoint(endpointId: String, reason: String, recoverMedium: Boolean = false) {
        val candidate = attemptCandidates.remove(endpointId) ?: peerRetries.candidate(endpointId)

        clearOutgoingForEndpoint(endpointId)
        connectionWatchdogs.remove(endpointId)?.let(recoveryHandler::removeCallbacks)
        connectionSessions.remove(endpointId)
        receiveBudgets.remove(endpointId)
        connectedEndpoints.remove(endpointId)
        pendingEndpoints.remove(endpointId)
        clearAuthentication(endpointId)
        removeIdentityMapping(endpointId)
        runCatching { connectionsClient.disconnectFromEndpoint(endpointId) }
        listener.onDisconnected(endpointId)

        Log.d(TAG, "ENDPOINT RETIRED -> $endpointId | reason=$reason")

        if (candidate != null) {
            val retry = peerRetries.failed(candidate)
            val delay = (peerCooldownState.getExpireAt(candidate.key) ?: SystemClock.elapsedRealtime()) - SystemClock.elapsedRealtime()
            Log.d(TAG, "PEER CONNECTION BACKOFF -> key=${candidate.key} name=${candidate.name} failures=${peerCooldownState.failureCount(candidate.key)} delay=$delay ms policy=${if (recoverMedium) "MEDIUM_REFRESH" else "ENDPOINT"}")
            if (retry != null) scheduleCooldownRetry(retry.candidate.endpoint, retry.candidate.name, retry.deadline)
            else if (verifiedEndpoints.isEmpty()) scheduleNearbyRecovery("candidate lost -> $endpointId", maxOf(5_000L, delay))
            if (peerCooldownState.failureCount(candidate.key) >= 3 && verifiedEndpoints.isEmpty()) {
                scheduleNearbyRecovery("repeated endpoint failure -> $endpointId", maxOf(5_000L, delay))
            }
        }
        if (recoverMedium) scheduleNearbyRecovery(reason)
    }

    private fun clearOutgoingForEndpoint(endpointId: String) {
        transferWatchdogs.remove(endpointId)?.let(recoveryHandler::removeCallbacks)
        outboxes.remove(endpointId)
    }

    fun getVerifiedNodeIds(): List<String> =
        nodeIdToEndpoint.filterValues { it in verifiedEndpoints && it in connectedEndpoints }.keys.toList()

    fun discardAcknowledged(packet: NearbyAntiPacket) {
        val prefix = "mesh:${packet.sourceNodeId}:${packet.recipientNodeId}:${packet.messageId}:"
        outboxes.values.forEach { it.discardQueued(prefix) }
    }

    /*
     * =========================================================
     * CLEAR AUTHENTICATION
     * =========================================================
     */

    private fun clearAuthentication(
        endpointId: String
    ) {


        pendingAuthentications.remove(
            endpointId
        )


        verifiedEndpoints.remove(
            endpointId
        )
    }



    /*
     * =========================================================
     * REMOVE IDENTITY MAPPING
     * =========================================================
     */

    private fun removeIdentityMapping(endpointId: String) {
        val nodeId = endpointToNodeId[endpointId]
        peerRoutes.remove(endpointId)
        if (nodeId != null) Log.d(TAG, "PEER UNMAPPED -> $endpointId <-> ${nodeId.take(8)}")
    }


    /*
     * =========================================================
     * STOP
     * =========================================================
     */

    fun stopAdvertising() {


        advertisingActive =
            false


        advertisingStart.invalidate()


        scheduledAdvertisingWatchdog
            ?.let { runnable ->

                recoveryHandler
                    .removeCallbacks(
                        runnable
                    )
            }


        scheduledAdvertisingWatchdog =
            null


        scheduledAdvertisingRetry
            ?.let { runnable ->

                recoveryHandler
                    .removeCallbacks(
                        runnable
                    )
            }


        scheduledAdvertisingRetry =
            null


        connectionsClient
            .stopAdvertising()
    }



    fun stopDiscovery() {


        discoveryActive =
            false


        discoveryStart.invalidate()


        scheduledDiscoveryWatchdog
            ?.let { runnable ->

                recoveryHandler
                    .removeCallbacks(
                        runnable
                    )
            }


        scheduledDiscoveryWatchdog =
            null


        scheduledDiscoveryRetry
            ?.let { runnable ->

                recoveryHandler
                    .removeCallbacks(
                        runnable
                    )
            }


        scheduledDiscoveryRetry =
            null


        connectionsClient
            .stopDiscovery()
    }



    fun disconnectAll() {


        connectionsClient
            .stopAllEndpoints()


        connectedEndpoints.clear()

        pendingEndpoints.clear()

        discoveredEndpointNames.clear()

        peerRoutes.clear()

        pendingAuthentications.clear()

        verifiedEndpoints.clear()

        transferWatchdogs.values.forEach(recoveryHandler::removeCallbacks)
        transferWatchdogs.clear()
        connectionWatchdogs.values.forEach(recoveryHandler::removeCallbacks)
        connectionWatchdogs.clear()
        outboxes.clear()
        connectionSessions.clear()
        receiveBudgets.clear()
        recentVaccineTransfers.clear()
        backgroundOffsets.clear()
        peerCooldownState.clearAll()
        peerRetries.clear()
        attemptCandidates.clear()
        scheduledCooldownRetries.values.forEach(recoveryHandler::removeCallbacks)
        scheduledCooldownRetries.clear()
    }



    fun stopEverything() {


        /*
         * Disable recovery BEFORE stopping endpoints. stopAllEndpoints()
         * can lead to disconnect callbacks, and those callbacks must not
         * restart Nearby while the owner is being disposed.
         */
        maintainNearbySessions =
            false
        mediumMonitor.stop()
        idleRefreshRunnable?.let(recoveryHandler::removeCallbacks)
        idleRefreshRunnable = null


        recoveryEpoch +=
            1L


        scheduledRecoveryRunnable
            ?.let { runnable ->

                recoveryHandler
                    .removeCallbacks(
                        runnable
                    )
            }


        scheduledRestartRunnable
            ?.let { runnable ->

                recoveryHandler
                    .removeCallbacks(
                        runnable
                    )
            }


        scheduledRecoveryRunnable =
            null


        scheduledRestartRunnable =
            null


        recoveryHandler.removeCallbacksAndMessages(null)
        stopAdvertising()

        stopDiscovery()

        disconnectAll()
    }

}
