package com.plexus.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Local recipient proofs remain pending until their exact authenticated AWS ACK is committed.
 * All coordinator state is confined to the runtime's Main dispatcher; Room handles its own IO.
 * No network-participation/FGS state is involved.
 */
internal class ProofPublicationOutbox(
    private val dao: AntiPacketDao,
    private val localNodeId: String,
    private val scope: CoroutineScope,
    private val offer: (NearbyAntiPacket, Boolean) -> Boolean
) {
    private val authenticated = MutableStateFlow(false)
    private val retryGeneration = MutableStateFlow(0L)
    private val submitted = mutableSetOf<String>()
    private var observer: Job? = null

    fun connected() {
        authenticated.value = true
        retryPending()
        if (observer?.isActive == true) return
        observer = scope.launch {
            combine(dao.observeInternetPending(localNodeId, System.currentTimeMillis()),
                authenticated, retryGeneration) { rows, online, _ -> rows to online }
                .collect { (rows, online) ->
                    if (!online) return@collect
                    val live = rows.filter { it.expiresAt > System.currentTimeMillis() }
                    submitted.retainAll(live.map { it.messageId }.toSet())
                    // Leave room for fresh proofs. ACK-driven Room invalidations advance this
                    // oldest-first window, without a historical vaccine scan or paced delays.
                    for (row in live.take(RECOVERY_WINDOW)) {
                        if (row.messageId in submitted) continue
                        PlexusDiagnostics.message("PROOF_NET_PENDING", row.messageId)
                        if (!offer(row.toNearbyAntiPacket(), true)) break
                        submitted.add(row.messageId)
                    }
                }
        }
    }

    fun disconnected() {
        authenticated.value = false
        submitted.forEach { PlexusDiagnostics.message("PROOF_NET_RETRY", it) }
        submitted.clear()
    }

    fun retryPending() {
        if (!authenticated.value) return
        submitted.clear()
        retryGeneration.value++
    }

    suspend fun offerFresh(packet: NearbyAntiPacket) {
        val row = dao.getAntiPacket(packet.messageId) ?: return
        if (row.recipientNodeId != localNodeId || row.internetPublishedAt != null ||
            row.expiresAt <= System.currentTimeMillis()) return
        // Always offer the persisted representation, including after a duplicate signing race.
        PlexusDiagnostics.message("PROOF_NET_PENDING", row.messageId)
        if (authenticated.value && offer(row.toNearbyAntiPacket(), false)) submitted.add(row.messageId)
    }

    suspend fun acknowledge(packet: NearbyAntiPacket) {
        if (packet.recipientNodeId != localNodeId) return
        try {
            val updated = dao.markInternetPublished(packet.messageId, packet.sourceNodeId,
                packet.recipientNodeId, packet.signature, packet.deliveredAt, packet.expiresAt,
                System.currentTimeMillis())
            if (updated > 0) PlexusDiagnostics.message("PROOF_NET_ACKED", packet.messageId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An ACK that could not be committed leaves a durable retry obligation.
            PlexusDiagnostics.lifecycle("PROOF_NET_ACK_COMMIT_FAILED")
        }
    }

    /** Success means every live local proof is AWS-ACKed, not merely offered. */
    suspend fun flush(timeoutMillis: Long = 8_000L): Boolean {
        val result = withTimeoutOrNull(timeoutMillis) {
            combine(dao.observeInternetPending(localNodeId, System.currentTimeMillis()),
                authenticated) { rows, online ->
                rows.any { it.expiresAt > System.currentTimeMillis() } to online
            }.first { (pending, online) -> !pending || !online }
        }
        val complete = result != null && !result.first
        if (!complete) submitted.take(RECOVERY_WINDOW).forEach {
            PlexusDiagnostics.message("PROOF_NET_RETRY", it)
        }
        return complete
    }
    companion object { private const val RECOVERY_WINDOW = 16 }
}
