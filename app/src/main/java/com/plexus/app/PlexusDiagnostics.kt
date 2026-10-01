package com.plexus.app

import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest

/** Temporary release diagnostics. No payload data, transport decisions or durable state live here. */
@Keep
internal object PlexusDiagnostics {
    const val TAG = "PLEXUS_DIAG"
    @Volatile private var enabled = false
    private val printedBuild = AtomicBoolean()
    private val sequence = AtomicLong()
    private val current = ThreadLocal<Ingress?>()
    private val active = AtomicInteger()
    private val pending = AtomicInteger()
    private val maximum = AtomicInteger()
    private val roomOperations = AtomicInteger()
    private var heartbeatJob: Job? = null
    private var stateJob: Job? = null
    private var mainHandler: Handler? = null
    private var mainPulse: Runnable? = null
    private val mainAge = AtomicLong()
    private val bgAge = AtomicLong()
    private val heartbeatGeneration = AtomicLong()
    private val internetMessages = AtomicLong()
    private val trackedMessageIds = linkedSetOf<String>()
    private val historicalProofs = AtomicLong()
    private val historicalReportAt = AtomicLong(SystemClock.elapsedRealtime())
    @Volatile private var state = NearbyState(0, emptyList())

    data class QueueState(val endpoint: String, val session: Long, val control: Int,
        val message: Int, val background: Int, val payload: Long?, val priority: Int?, val since: Long?) {
        fun render(now: Long = SystemClock.elapsedRealtime()) =
            "endpoint=${endpoint.take(8)} session=$session CONTROL=$control MESSAGE=$message BACKGROUND=$background inFlight=${payload ?: "none"} priority=${priority ?: "none"} inFlightAgeMs=${since?.let { maxOf(0L, now - it) } ?: 0}"
    }
    data class NearbyState(val verifiedPeers: Int, val queues: List<QueueState>) {
        fun render(now: Long) = "verifiedPeers=$verifiedPeers queues=[${queues.joinToString(";") { it.render(now) }}]"
    }

    class Ingress(val id: String, val source: String) {
        val since = SystemClock.elapsedRealtime()
        val references = AtomicInteger(1)
    }
    class Timing(val stage: String, val operation: String, val handle: String, val room: Boolean) {
        val since = SystemClock.elapsedRealtime()
    }

    fun printBuildOnce() {
        if (printedBuild.compareAndSet(false, true)) Log.println(Log.INFO, TAG,
            "PLEXUS_BUILD=${BuildConfig.PLEXUS_BUILD_ID} variant=${BuildConfig.BUILD_TYPE} pid=${Process.myPid()}")
    }

    internal fun counts() = "activeIngressJobs=${active.get()} pendingIngressJobs=${pending.get()} maxObservedIngressJobs=${maximum.get()} roomOps=${roomOperations.get()}"
    // Release R8 rules strip Log.i/d/v; println keeps this temporary INFO diagnostic stream.
    private fun event(message: String) {
        if (enabled && (message.startsWith("MAIN_HEARTBEAT ") || message.startsWith("BG_HEARTBEAT "))) lifecycle(message)
    }

    // Message correlation also runs with network participation OFF. Never emit the original ID.
    internal fun shortId(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
    fun message(stage: String, id: String, detail: String = "") {
        val correlation = shortId(id)
        val tracked = synchronized(trackedMessageIds) {
            // A proof, retry or historical offer must never enroll its own ID.
            if (stage == "OUTGOING_MESSAGE_CREATED" || stage == "RECEIVER_PACKET_INGRESS") {
                if (trackedMessageIds.add(correlation) && trackedMessageIds.size > 256) {
                    trackedMessageIds.remove(trackedMessageIds.first())
                }
            }
            correlation in trackedMessageIds
        }
        if (!tracked) {
            if (stage == "DELIVERY_PROOF_RECEIVED") historicalProofs.incrementAndGet()
            reportHistoricalProofs()
            return
        }
        lifecycle("$stage id=$correlation $detail".trimEnd())
    }
    fun lifecycle(event: String) {
        reportHistoricalProofs()
        Log.println(Log.INFO, TAG, event)
    }
    private fun reportHistoricalProofs() {
        val now = SystemClock.elapsedRealtime()
        val previous = historicalReportAt.get()
        if (now - previous < 5000 || !historicalReportAt.compareAndSet(previous, now)) return
        val count = historicalProofs.getAndSet(0)
        if (count > 0) Log.println(Log.INFO, TAG, "HISTORICAL_PROOFS_SUPPRESSED count=$count")
    }
    fun internetPacketReceived(id: String) {
        internetMessages.incrementAndGet()
        message("RECEIVER_PACKET_INGRESS", id, "source=INTERNET")
    }
    fun internetMessageCount(): Long = internetMessages.get()

    fun callbackEnter(endpoint: String, payload: Long, session: Long): Ingress? {
        if (!enabled) return null
        val trace = Ingress(sequence.incrementAndGet().toString(36), "NEARBY")
        return trace
    }

    fun callbackResult(trace: Ingress?, category: String) {
        if (trace != null && (category.endsWith("_EXCEPTION") || category == "RX_CALLBACK_REJECTED_MAIN_POST")) {
            Log.w(TAG, "$category handle=${trace.id}")
        }
    }

    private fun release(trace: Ingress) {
        trace.references.decrementAndGet()
    }

    fun <T> ingress(trace: Ingress?, block: () -> T): T {
        if (trace == null) return block()
        val previous = current.get()
        current.set(trace)
        try { return block() } finally { current.set(previous); release(trace) }
    }

    /** Same Main.immediate dispatcher as ingress already uses; counters also cover pre-start cancellation. */
    fun launch(scope: CoroutineScope, source: String = "NEARBY", block: suspend CoroutineScope.() -> Unit): Job {
        val inherited = current.get()
        if (!enabled && inherited == null) return scope.launch(Dispatchers.Main.immediate, block = block)
        val trace = inherited?.also { it.references.incrementAndGet() }
            ?: Ingress(sequence.incrementAndGet().toString(36), source)
        pending.incrementAndGet()
        maximum.updateAndGet { maxOf(it, active.get() + pending.get()) }
        val started = AtomicBoolean()
        val job = scope.launch(Dispatchers.Main.immediate + current.asContextElement(trace)) {
            started.set(true)
            pending.decrementAndGet()
            active.incrementAndGet()
            try { block() } finally {
                active.decrementAndGet()
                release(trace)
            }
        }
        job.invokeOnCompletion {
            if (started.compareAndSet(false, true)) {
                pending.decrementAndGet()
                release(trace)
            }
        }
        return job
    }

    fun begin(stage: String, operation: String, room: Boolean = false): Timing? {
        if (!enabled) return null
        val timing = Timing(stage, operation, current.get()?.id ?: "none", room)
        if (room) roomOperations.incrementAndGet()
        return timing
    }
    fun end(timing: Timing?) {
        if (timing == null) return
        if (timing.room) roomOperations.decrementAndGet()
        val elapsed = SystemClock.elapsedRealtime() - timing.since
        val threshold = when { elapsed >= 1000 -> 1000; elapsed >= 500 -> 500; elapsed > 100 -> 100; else -> null }
        if (threshold != null && enabled) Log.w(TAG,
            "SLOW_OPERATION handle=${timing.handle} op=${timing.operation} durationMs=$elapsed thresholdMs=$threshold")
    }
    fun <T> timed(stage: String, operation: String, block: () -> T): T {
        val timing = begin(stage, operation)
        try { return block() } finally { end(timing) }
    }
    suspend fun <T> room(operation: String, block: suspend () -> T): T {
        val timing = begin("ROOM", operation, room = true)
        try { return block() } finally { end(timing) }
    }
    fun <T> proofOffer(transport: String, messageId: String? = null, block: () -> T): T {
        val result = block()
        val status = when (result) { true -> "ACCEPTED"; false -> "DEFERRED"; null -> "UNAVAILABLE"; else -> "ATTEMPTED" }
        if (transport == "NEARBY" && messageId != null) {
            message("DELIVERY_PROOF_OFFERED", messageId, "transport=NEARBY status=$status")
        }
        return result
    }
    @Suppress("UNUSED_PARAMETER")
    fun outbox(category: String, snapshot: String) { /* Existing warning/error paths retain failures. */ }
    fun updateNearbyState(snapshot: NearbyState) { if (enabled) state = snapshot }

    fun start(scope: CoroutineScope, snapshotOnMain: () -> NearbyState) {
        if (heartbeatJob != null) return
        enabled = true
        val generation = heartbeatGeneration.incrementAndGet()
        val handler = Handler(Looper.getMainLooper())
        mainHandler = handler
        val waiting = AtomicBoolean()
        val expected = AtomicLong()
        val pulseSequence = AtomicLong()
        val pulse = Runnable {
            if (enabled && heartbeatGeneration.get() == generation) {
                val now = SystemClock.elapsedRealtime()
                mainAge.set(now)
                event("MAIN_HEARTBEAT seq=${pulseSequence.get()} delayMs=${now - expected.get()}")
                state = snapshotOnMain()
            }
            waiting.set(false)
        }
        mainPulse = pulse
        mainAge.set(SystemClock.elapsedRealtime())
        bgAge.set(mainAge.get())
        state = snapshotOnMain()
        heartbeatJob = scope.launch(Dispatchers.Default) {
            var seq = 0L
            while (isActive) {
                delay(5000)
                val now = SystemClock.elapsedRealtime()
                if (heartbeatGeneration.get() != generation) return@launch
                val previousPulse = bgAge.getAndSet(now)
                event("BG_HEARTBEAT seq=${++seq} intervalMs=${now - previousPulse}")
                if (waiting.compareAndSet(false, true)) {
                    expected.set(now)
                    pulseSequence.set(seq)
                    handler.post(pulse)
                }
            }
        }
        stateJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(5000)
                if (heartbeatGeneration.get() != generation) return@launch
                val now = SystemClock.elapsedRealtime()
                val process = ActivityManager.RunningAppProcessInfo()
                val importance = try {
                    ActivityManager.getMyMemoryState(process)
                    process.importance.toString()
                } catch (_: Exception) {
                    event("PROCESS_STATE_UNAVAILABLE")
                    "unknown"
                }
                if (heartbeatGeneration.get() != generation) return@launch
                val captured = state
                if (captured.verifiedPeers > 0) event("STATE pid=${Process.myPid()} networkParticipation=ON importance=$importance mainHeartbeatAgeMs=${maxOf(0L, now - mainAge.get())} bgHeartbeatAgeMs=${maxOf(0L, now - bgAge.get())} ${counts()} ${captured.render(now)}")
            }
        }
    }

    fun stop() {
        event("NETWORK_DIAGNOSTICS_STOPPED ${counts()}")
        enabled = false
        heartbeatGeneration.incrementAndGet()
        heartbeatJob?.cancel()
        heartbeatJob = null
        stateJob?.cancel()
        stateJob = null
        mainPulse?.let { mainHandler?.removeCallbacks(it) }
        mainPulse = null
        mainHandler = null
    }
}
